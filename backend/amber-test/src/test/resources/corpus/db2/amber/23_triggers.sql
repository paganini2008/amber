CREATE TRIGGER app.trg_orders_bi
    NO CASCADE BEFORE INSERT ON app.orders
    REFERENCING NEW AS n
    FOR EACH ROW MODE DB2SQL
    SET n.created_ts = CURRENT TIMESTAMP;
CREATE TRIGGER app.trg_orders_bu BEFORE UPDATE OF amount, status ON app.orders
    REFERENCING OLD AS o NEW AS n
    FOR EACH ROW
    WHEN (n.amount < 0)
    SIGNAL SQLSTATE '75000' SET MESSAGE_TEXT = 'Negative amount';
CREATE OR REPLACE TRIGGER app.trg_orders_au
    AFTER UPDATE ON app.orders
    REFERENCING OLD AS o NEW AS n
    FOR EACH ROW MODE DB2SQL
    WHEN (o.status <> n.status)
BEGIN ATOMIC
    INSERT INTO app.order_history (order_id, old_status, new_status, changed_ts)
        VALUES (n.order_id, o.status, n.status, CURRENT TIMESTAMP);
    IF n.status = 'C' THEN
        UPDATE app.customers SET open_orders = open_orders - 1 WHERE customer_id = n.customer_id;
    END IF;
END;
CREATE TRIGGER app.trg_orders_ad AFTER DELETE ON app.orders
    REFERENCING OLD_TABLE AS deleted
    FOR EACH STATEMENT MODE DB2SQL
    INSERT INTO app.audit (tab, cnt, ts) SELECT 'ORDERS', COUNT(*), CURRENT TIMESTAMP FROM deleted;
CREATE TRIGGER app.trg_stmt AFTER INSERT ON app.orders
    REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT
BEGIN ATOMIC
    UPDATE app.stats SET total = total + (SELECT SUM(amount) FROM new_rows);
END;
CREATE TRIGGER app.trg_view_ins INSTEAD OF INSERT ON app.v_orders
    REFERENCING NEW AS n
    FOR EACH ROW
    INSERT INTO app.orders (order_id, amount) VALUES (n.id, n.amt);
CREATE TRIGGER app.trg_multi AFTER INSERT OR UPDATE OR DELETE ON app.customers
    REFERENCING NEW AS n OLD AS o
    FOR EACH ROW
BEGIN
    DECLARE v_op CHAR(1);
    IF INSERTING THEN
        SET v_op = 'I';
    ELSEIF UPDATING THEN
        SET v_op = 'U';
    ELSE
        SET v_op = 'D';
    END IF;
    INSERT INTO app.cust_log (op, id, ts) VALUES (v_op, COALESCE(n.customer_id, o.customer_id), CURRENT TIMESTAMP);
END;
CREATE TRIGGER app.trg_secured NO CASCADE BEFORE INSERT ON app.employee REFERENCING NEW AS n FOR EACH ROW MODE DB2SQL SECURED
BEGIN ATOMIC
    SET n.empno = UPPER(n.empno);
    SET (n.created, n.creator) = (CURRENT TIMESTAMP, SESSION_USER);
END;
CREATE TRIGGER app.trg_seq BEFORE INSERT ON app.orders REFERENCING NEW AS n FOR EACH ROW
    SET n.order_id = NEXT VALUE FOR app.order_seq;
