-- =====================================================================
-- Order processing package: SQL PL procedures and functions.
-- Run with: db2 -td@ -vf 91_realworld_sqlpl_procedures.sql
-- =====================================================================
--#SET TERMINATOR @

SET SCHEMA shop@
SET PATH = SYSTEM PATH, shop@

CREATE OR REPLACE VARIABLE shop.g_debug SMALLINT DEFAULT 0@

CREATE OR REPLACE PROCEDURE shop.log_event (
    IN p_proc   VARCHAR(128),
    IN p_level  CHAR(1),
    IN p_msg    VARCHAR(2000)
)
    SPECIFIC shop_log_event
    LANGUAGE SQL
    MODIFIES SQL DATA
    AUTONOMOUS
BEGIN
    INSERT INTO shop.event_log (proc_name, lvl, msg, logged_ts, logged_by)
    VALUES (p_proc, p_level, SUBSTR(p_msg, 1, 2000), CURRENT TIMESTAMP, SESSION_USER);
END@

CREATE OR REPLACE FUNCTION shop.order_total (p_order_id BIGINT)
    RETURNS DECIMAL(15, 2)
    LANGUAGE SQL
    READS SQL DATA
    NOT DETERMINISTIC
    NO EXTERNAL ACTION
BEGIN ATOMIC
    DECLARE v_total DECIMAL(15, 2) DEFAULT 0;
    SET v_total = (SELECT COALESCE(SUM(l.qty * l.unit_price * (1 - l.discount_pct / 100)), 0)
                   FROM shop.order_lines l
                   WHERE l.order_id = p_order_id);
    RETURN v_total;
END@

CREATE OR REPLACE PROCEDURE shop.process_orders (
    IN  p_max_orders INTEGER,
    OUT p_processed  INTEGER,
    OUT p_failed     INTEGER
)
    SPECIFIC shop_process_orders
    LANGUAGE SQL
    DYNAMIC RESULT SETS 1
    MODIFIES SQL DATA
main: BEGIN
    DECLARE SQLSTATE   CHAR(5) DEFAULT '00000';
    DECLARE SQLCODE    INTEGER DEFAULT 0;
    DECLARE v_order_id BIGINT;
    DECLARE v_cust_id  INTEGER;
    DECLARE v_total    DECIMAL(15, 2);
    DECLARE v_credit   DECIMAL(15, 2);
    DECLARE v_msg      VARCHAR(2000);
    DECLARE v_eof      SMALLINT DEFAULT 0;
    DECLARE v_count    INTEGER DEFAULT 0;

    DECLARE credit_exceeded CONDITION FOR SQLSTATE '75100';

    DECLARE c_orders CURSOR WITH HOLD FOR
        SELECT o.order_id, o.customer_id
        FROM shop.orders o
        WHERE o.status = 'NEW'
        ORDER BY o.priority DESC, o.order_ts
        FETCH FIRST 10000 ROWS ONLY
        FOR UPDATE OF status;

    DECLARE c_summary CURSOR WITH RETURN TO CALLER FOR
        SELECT status, COUNT(*) AS cnt FROM shop.orders GROUP BY status ORDER BY status;

    DECLARE CONTINUE HANDLER FOR NOT FOUND
        SET v_eof = 1;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        GET DIAGNOSTICS EXCEPTION 1 v_msg = MESSAGE_TEXT;
        ROLLBACK;
        CALL shop.log_event('PROCESS_ORDERS', 'E', 'Aborted: ' || COALESCE(v_msg, 'unknown') || ' SQLSTATE=' || SQLSTATE);
        RESIGNAL;
    END;

    SET p_processed = 0;
    SET p_failed = 0;

    IF p_max_orders IS NULL OR p_max_orders <= 0 THEN
        SIGNAL SQLSTATE '75001' SET MESSAGE_TEXT = 'p_max_orders must be positive';
    END IF;

    OPEN c_orders;
    order_loop: LOOP
        FETCH c_orders INTO v_order_id, v_cust_id;
        IF v_eof = 1 OR v_count >= p_max_orders THEN
            LEAVE order_loop;
        END IF;
        SET v_count = v_count + 1;

        one_order: BEGIN
            DECLARE CONTINUE HANDLER FOR credit_exceeded
            BEGIN
                UPDATE shop.orders SET status = 'HOLD', note = 'credit limit' WHERE CURRENT OF c_orders;
                SET p_failed = p_failed + 1;
            END;
            DECLARE CONTINUE HANDLER FOR SQLSTATE '23505'
                SET p_failed = p_failed + 1;

            SET v_total = shop.order_total(v_order_id);
            SELECT c.credit_limit - COALESCE(c.open_balance, 0)
              INTO v_credit
              FROM shop.customers c
             WHERE c.customer_id = v_cust_id
              WITH RS;

            IF v_total > v_credit THEN
                SIGNAL credit_exceeded SET MESSAGE_TEXT = 'Order ' || CHAR(v_order_id) || ' exceeds credit';
            ELSEIF v_total = 0 THEN
                UPDATE shop.orders SET status = 'EMPTY' WHERE CURRENT OF c_orders;
            ELSE
                UPDATE shop.orders
                   SET status = 'RELEASED', total = v_total, released_ts = CURRENT TIMESTAMP
                 WHERE CURRENT OF c_orders;
                UPDATE shop.customers
                   SET open_balance = COALESCE(open_balance, 0) + v_total
                 WHERE customer_id = v_cust_id;
                INSERT INTO shop.shipments (order_id, created_ts, due_date)
                VALUES (v_order_id, CURRENT TIMESTAMP, CURRENT DATE + 2 DAYS);
                SET p_processed = p_processed + 1;
            END IF;
        END one_order;

        IF MOD(v_count, 500) = 0 THEN
            COMMIT;
            IF shop.g_debug > 0 THEN
                CALL shop.log_event('PROCESS_ORDERS', 'D', 'Committed after ' || CHAR(v_count) || ' orders');
            END IF;
        END IF;
    END LOOP order_loop;
    CLOSE c_orders;

    CALL shop.log_event('PROCESS_ORDERS', 'I',
        'Processed=' || CHAR(p_processed) || ' failed=' || CHAR(p_failed));
    OPEN c_summary;
END main@

CREATE OR REPLACE PROCEDURE shop.purge_table (
    IN p_schema   VARCHAR(128),
    IN p_table    VARCHAR(128),
    IN p_date_col VARCHAR(128),
    IN p_days     INTEGER,
    OUT p_deleted BIGINT
)
    LANGUAGE SQL
BEGIN
    DECLARE v_sql     VARCHAR(4000);
    DECLARE v_batch   INTEGER DEFAULT 0;
    DECLARE v_rows    INTEGER DEFAULT 1;
    DECLARE v_exists  INTEGER;

    SET p_deleted = 0;
    SELECT COUNT(*) INTO v_exists
      FROM syscat.columns
     WHERE tabschema = UPPER(p_schema) AND tabname = UPPER(p_table) AND colname = UPPER(p_date_col);
    IF v_exists = 0 THEN
        SIGNAL SQLSTATE '75002' SET MESSAGE_TEXT = 'Unknown table or column';
    END IF;

    SET v_sql = 'DELETE FROM (SELECT 1 FROM "' || p_schema || '"."' || p_table || '"'
             || ' WHERE "' || p_date_col || '" < CURRENT DATE - ' || CHAR(p_days) || ' DAYS'
             || ' FETCH FIRST 10000 ROWS ONLY)';
    PREPARE s_del FROM v_sql;

    WHILE v_rows > 0 DO
        EXECUTE s_del;
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        SET p_deleted = p_deleted + v_rows;
        SET v_batch = v_batch + 1;
        COMMIT;
    END WHILE;

    EXECUTE IMMEDIATE 'CALL SYSPROC.ADMIN_CMD(''RUNSTATS ON TABLE ' || p_schema || '.' || p_table || ' AND INDEXES ALL'')';
END@

CREATE OR REPLACE PROCEDURE shop.rebuild_category_paths ()
    LANGUAGE SQL
BEGIN
    DECLARE v_level INTEGER DEFAULT 0;
    DECLARE v_changed INTEGER DEFAULT 1;

    UPDATE shop.categories SET path = '/' || name, depth = 0 WHERE parent_id IS NULL;

    REPEAT
        SET v_level = v_level + 1;
        UPDATE shop.categories c
           SET (path, depth) = (SELECT p.path || '/' || c.name, p.depth + 1
                                  FROM shop.categories p
                                 WHERE p.category_id = c.parent_id)
         WHERE c.parent_id IN (SELECT category_id FROM shop.categories WHERE depth = v_level - 1);
        GET DIAGNOSTICS v_changed = ROW_COUNT;
    UNTIL v_changed = 0 OR v_level > 20
    END REPEAT;

    cat_loop: FOR cat AS cur_cat CURSOR FOR
        SELECT category_id, path FROM shop.categories WHERE depth = 0 ORDER BY path
    DO
        CASE
            WHEN cat.path LIKE '/Archive%' THEN
                UPDATE shop.categories SET visible = 'N' WHERE path LIKE cat.path || '/%';
            WHEN cat.path LIKE '/Sale%' THEN
                CALL shop.log_event('REBUILD', 'I', 'Sale tree: ' || cat.path);
            ELSE
                ITERATE cat_loop;
        END CASE;
    END FOR cat_loop;
END@

CREATE OR REPLACE PROCEDURE shop.monthly_report (IN p_month DATE)
    LANGUAGE SQL
    DYNAMIC RESULT SETS 1
    READS SQL DATA
BEGIN
    DECLARE v_from DATE;
    DECLARE v_to   DATE;
    DECLARE c_report CURSOR WITH RETURN FOR
        WITH lines AS (
            SELECT o.customer_id, l.product_id, l.qty * l.unit_price AS revenue
            FROM shop.orders o
            JOIN shop.order_lines l ON l.order_id = o.order_id
            WHERE o.order_ts >= TIMESTAMP(v_from) AND o.order_ts < TIMESTAMP(v_to)
        )
        SELECT p.category, COUNT(DISTINCT customer_id) AS customers, SUM(revenue) AS revenue,
               RANK() OVER (ORDER BY SUM(revenue) DESC) AS rnk
        FROM lines
        JOIN shop.products p ON p.product_id = lines.product_id
        GROUP BY p.category
        ORDER BY rnk;

    SET v_from = p_month - (DAY(p_month) - 1) DAYS;
    SET v_to = v_from + 1 MONTH;
    OPEN c_report;
END@

GRANT EXECUTE ON PROCEDURE shop.process_orders TO ROLE order_admin@
GRANT EXECUTE ON FUNCTION shop.order_total TO PUBLIC@
COMMENT ON PROCEDURE shop.process_orders IS 'Releases NEW orders within credit limits'@

CALL shop.process_orders(1000, ?, ?)@

--#SET TERMINATOR ;
COMMIT;
