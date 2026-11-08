-- Oracle conformance: triggers
CREATE OR REPLACE TRIGGER orders_bi
BEFORE INSERT ON orders
FOR EACH ROW
WHEN (NEW.order_id IS NULL)
BEGIN
  :NEW.order_id := orders_seq.NEXTVAL;
  :NEW.created_at := SYSTIMESTAMP;
END;
/
CREATE OR REPLACE TRIGGER orders_audit_trg
AFTER INSERT OR UPDATE OF status, amount OR DELETE ON orders
REFERENCING OLD AS o NEW AS n
FOR EACH ROW
DECLARE
  v_action VARCHAR2(1);
BEGIN
  v_action := CASE WHEN INSERTING THEN 'I' WHEN UPDATING THEN 'U' ELSE 'D' END;
  IF UPDATING('STATUS') THEN
    NULL;
  END IF;
  INSERT INTO orders_audit (order_id, action, old_amount, new_amount, changed_by, changed_at)
  VALUES (NVL(:n.order_id, :o.order_id), v_action, :o.amount, :n.amount, USER, SYSDATE);
END orders_audit_trg;
/
CREATE OR REPLACE TRIGGER orders_stmt_trg
AFTER UPDATE ON orders
BEGIN
  DBMS_OUTPUT.PUT_LINE('orders updated');
END;
/
CREATE OR REPLACE TRIGGER v_orders_ioi
INSTEAD OF INSERT ON v_open_orders
FOR EACH ROW
BEGIN
  INSERT INTO orders (order_id, amount, status) VALUES (:NEW.id, :NEW.amt, 'OPEN');
END;
/
CREATE OR REPLACE TRIGGER orders_compound
FOR UPDATE OF amount ON orders
COMPOUND TRIGGER
  TYPE id_tab IS TABLE OF orders.order_id%TYPE;
  g_ids id_tab := id_tab();
  BEFORE STATEMENT IS
  BEGIN
    g_ids.DELETE;
  END BEFORE STATEMENT;
  AFTER EACH ROW IS
  BEGIN
    g_ids.EXTEND;
    g_ids(g_ids.LAST) := :NEW.order_id;
  END AFTER EACH ROW;
  AFTER STATEMENT IS
  BEGIN
    FORALL i IN 1 .. g_ids.COUNT
      INSERT INTO recalc_queue (order_id) VALUES (g_ids(i));
  END AFTER STATEMENT;
END orders_compound;
/
CREATE OR REPLACE TRIGGER ddl_audit_trg
AFTER CREATE OR ALTER OR DROP ON SCHEMA
BEGIN
  INSERT INTO ddl_log (evt, obj_type, obj_name, at)
  VALUES (ORA_SYSEVENT, ORA_DICT_OBJ_TYPE, ORA_DICT_OBJ_NAME, SYSDATE);
END;
/
CREATE OR REPLACE TRIGGER logon_trg
AFTER LOGON ON DATABASE
BEGIN
  EXECUTE IMMEDIATE 'ALTER SESSION SET NLS_SORT = BINARY_CI';
END;
/
CREATE OR REPLACE TRIGGER orders_follows
BEFORE UPDATE ON orders FOR EACH ROW
FOLLOWS orders_bi
ENABLE
BEGIN
  :NEW.updated_at := SYSDATE;
END;
/
CREATE OR REPLACE TRIGGER nested_trg
INSTEAD OF INSERT ON NESTED TABLE prev_addresses OF v_people
FOR EACH ROW
BEGIN
  NULL;
END;
/
ALTER TRIGGER orders_bi DISABLE;
ALTER TRIGGER orders_bi ENABLE;
ALTER TABLE orders DISABLE ALL TRIGGERS;
DROP TRIGGER orders_stmt_trg;
