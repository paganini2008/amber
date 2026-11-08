-- Triggers, events, views
DELIMITER ;;
CREATE TRIGGER trg_orders_bi BEFORE INSERT ON orders FOR EACH ROW
BEGIN
    IF NEW.qty <= 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'qty must be positive';
    END IF;
    SET NEW.order_date = COALESCE(NEW.order_date, CURDATE());
END;;
CREATE TRIGGER trg_orders_au AFTER UPDATE ON orders FOR EACH ROW FOLLOWS trg_orders_bi
BEGIN
    INSERT INTO order_audit (order_id, old_qty, new_qty, changed_at) VALUES (OLD.order_id, OLD.qty, NEW.qty, NOW());
END;;
CREATE DEFINER=CURRENT_USER TRIGGER IF NOT EXISTS trg_orders_ad AFTER DELETE ON orders FOR EACH ROW PRECEDES trg_orders_au
INSERT INTO order_audit (order_id, old_qty, changed_at) VALUES (OLD.order_id, OLD.qty, NOW());;
DELIMITER ;
CREATE TRIGGER trg_cust_bu BEFORE UPDATE ON customers FOR EACH ROW SET NEW.updated_at = NOW();
DROP TRIGGER IF EXISTS sales_db.trg_cust_bu;
SET GLOBAL event_scheduler = ON;
CREATE EVENT IF NOT EXISTS ev_purge_audit
    ON SCHEDULE EVERY 1 DAY STARTS '2024-01-01 03:00:00' ENDS CURRENT_TIMESTAMP + INTERVAL 1 YEAR
    ON COMPLETION PRESERVE ENABLE
    COMMENT 'nightly purge'
    DO DELETE FROM order_audit WHERE changed_at < NOW() - INTERVAL 90 DAY;
DELIMITER |
CREATE EVENT ev_rollup ON SCHEDULE AT CURRENT_TIMESTAMP + INTERVAL 1 HOUR
DO
BEGIN
    CALL sp_refresh_totals(CURDATE() - INTERVAL 1 DAY, @r, @n);
    INSERT INTO job_log (job, rows_done) VALUES ('rollup', @r);
END |
DELIMITER ;
ALTER EVENT ev_purge_audit DISABLE;
ALTER EVENT ev_purge_audit ON SCHEDULE EVERY 12 HOUR RENAME TO ev_purge_audit2;
DROP EVENT IF EXISTS ev_purge_audit2;
CREATE VIEW v_active_customers AS SELECT customer_id, email FROM customers WHERE status = 'active';
CREATE OR REPLACE ALGORITHM = MERGE DEFINER = `admin`@`localhost` SQL SECURITY INVOKER VIEW v_cust_orders (cid, email, orders_cnt) AS
    SELECT c.customer_id, c.email, COUNT(o.order_id) FROM customers c LEFT JOIN orders o USING (customer_id) GROUP BY c.customer_id, c.email;
CREATE VIEW v_big_orders AS SELECT * FROM orders WHERE line_total > 1000 WITH CASCADED CHECK OPTION;
CREATE VIEW v_local AS SELECT * FROM v_big_orders WHERE qty > 1 WITH LOCAL CHECK OPTION;
CREATE VIEW v_cte AS WITH t AS (SELECT 1 AS x) SELECT x FROM t;
ALTER VIEW v_active_customers AS SELECT customer_id, email, display_name FROM customers WHERE status = 'active';
DROP VIEW IF EXISTS v_local, v_cte RESTRICT;
