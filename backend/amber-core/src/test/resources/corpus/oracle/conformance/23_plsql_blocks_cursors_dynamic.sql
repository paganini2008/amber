-- Oracle conformance: anonymous blocks, control flow, cursors, collections, dynamic SQL, exceptions
SET SERVEROUTPUT ON SIZE UNLIMITED
DECLARE
  v_count   PLS_INTEGER := 0;
  v_name    VARCHAR2(100);
  v_flag    BOOLEAN := TRUE;
  v_row     orders%ROWTYPE;
  c_limit   CONSTANT NUMBER := 100;
  CURSOR c_orders (p_status VARCHAR2) IS
    SELECT order_id, amount FROM orders WHERE status = p_status;
  TYPE t_ids IS TABLE OF orders.order_id%TYPE INDEX BY PLS_INTEGER;
  TYPE t_names IS VARRAY(5) OF VARCHAR2(30);
  TYPE t_map IS TABLE OF NUMBER INDEX BY VARCHAR2(30);
  v_ids     t_ids;
  v_names   t_names := t_names('a', 'b');
  v_map     t_map;
  v_key     VARCHAR2(30);
BEGIN
  FOR r IN c_orders('OPEN') LOOP
    v_count := v_count + 1;
    EXIT WHEN v_count > c_limit;
    CONTINUE WHEN r.amount IS NULL;
    DBMS_OUTPUT.PUT_LINE(r.order_id || ': ' || r.amount);
  END LOOP;

  OPEN c_orders('SHIPPED');
  LOOP
    FETCH c_orders INTO v_row.order_id, v_row.amount;
    EXIT WHEN c_orders%NOTFOUND;
  END LOOP;
  CLOSE c_orders;

  FOR i IN 1 .. 10 LOOP
    NULL;
  END LOOP;
  FOR i IN REVERSE 1 .. 3 LOOP
    v_count := v_count - i;
  END LOOP;

  WHILE v_flag LOOP
    v_flag := FALSE;
  END LOOP;

  <<outer_loop>>
  FOR i IN 1 .. 3 LOOP
    FOR j IN 1 .. 3 LOOP
      EXIT outer_loop WHEN i * j > 4;
    END LOOP;
  END LOOP outer_loop;

  IF v_count > 10 THEN
    v_name := 'many';
  ELSIF v_count > 0 THEN
    v_name := 'some';
  ELSE
    v_name := 'none';
  END IF;

  CASE v_name
    WHEN 'many' THEN NULL;
    WHEN 'some' THEN DBMS_OUTPUT.PUT_LINE('some');
    ELSE NULL;
  END CASE;

  SELECT order_id BULK COLLECT INTO v_ids FROM orders WHERE ROWNUM <= 1000;
  FORALL i IN INDICES OF v_ids SAVE EXCEPTIONS
    UPDATE orders SET status = 'SEEN' WHERE order_id = v_ids(i);

  v_map('alpha') := 1;
  v_map('beta') := 2;
  v_key := v_map.FIRST;
  WHILE v_key IS NOT NULL LOOP
    v_key := v_map.NEXT(v_key);
  END LOOP;
  v_names.EXTEND;
  v_names(v_names.COUNT) := 'c';
  GOTO done;
  <<done>>
  NULL;
EXCEPTION
  WHEN TOO_MANY_ROWS OR NO_DATA_FOUND THEN
    DBMS_OUTPUT.PUT_LINE('lookup failure');
  WHEN OTHERS THEN
    DBMS_OUTPUT.PUT_LINE(SQLCODE || ' ' || SQLERRM || ' ' || DBMS_UTILITY.FORMAT_ERROR_BACKTRACE);
    RAISE;
END;
/
DECLARE
  v_sql   VARCHAR2(4000);
  v_cnt   NUMBER;
  v_cur   SYS_REFCURSOR;
  v_id    NUMBER;
  v_tab   VARCHAR2(30) := 'ORDERS';
BEGIN
  EXECUTE IMMEDIATE 'TRUNCATE TABLE staging_orders';
  EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM ' || DBMS_ASSERT.SQL_OBJECT_NAME(v_tab) INTO v_cnt;
  EXECUTE IMMEDIATE 'UPDATE orders SET status = :1 WHERE order_id = :2' USING 'X', 10;
  EXECUTE IMMEDIATE 'BEGIN order_api.cancel_order(:id); END;' USING IN v_id;
  EXECUTE IMMEDIATE 'INSERT INTO t (a) VALUES (:a) RETURNING id INTO :b' USING 1 RETURNING INTO v_id;
  v_sql := q'[SELECT order_id FROM orders WHERE status = 'OPEN']';
  OPEN v_cur FOR v_sql;
  LOOP
    FETCH v_cur INTO v_id;
    EXIT WHEN v_cur%NOTFOUND;
  END LOOP;
  CLOSE v_cur;
  OPEN v_cur FOR SELECT order_id FROM orders WHERE amount > v_cnt;
  CLOSE v_cur;
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -942 THEN
      NULL;
    ELSE
      RAISE;
    END IF;
END;
/
DECLARE
  e_custom EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_custom, -20500);
  v_dummy NUMBER;
BEGIN
  BEGIN
    SELECT 1 INTO v_dummy FROM dual WHERE 1 = 0;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      v_dummy := 0;
  END;
  IF v_dummy = 0 THEN
    RAISE e_custom;
  END IF;
EXCEPTION
  WHEN e_custom THEN
    ROLLBACK;
    RAISE_APPLICATION_ERROR(-20501, 'wrapped', TRUE);
END;
/
DECLARE
  TYPE r_cust IS RECORD (id NUMBER, name VARCHAR2(100));
  v_rec r_cust;
  CURSOR c IS SELECT customer_id, name FROM customers FOR UPDATE OF name;
BEGIN
  FOR r IN c LOOP
    UPDATE customers SET name = INITCAP(r.name) WHERE CURRENT OF c;
  END LOOP;
  v_rec.id := 1;
  INSERT INTO cust_copy VALUES v_rec;
  UPDATE cust_copy SET ROW = v_rec WHERE id = v_rec.id;
  FOR r IN (SELECT * FROM customers) LOOP NULL; END LOOP;
  DBMS_SESSION.SET_IDENTIFIER('batch');
  DBMS_STATS.GATHER_TABLE_STATS(ownname => USER, tabname => 'CUSTOMERS', cascade => TRUE);
END;
/
BEGIN
  NULL;
END;
/
DECLARE
  v NUMBER;
BEGIN
  v := CASE WHEN SYSDATE > DATE '2020-01-01' THEN 1 ELSE 0 END;
  $IF DBMS_DB_VERSION.VER_LE_19 $THEN
    DBMS_OUTPUT.PUT_LINE('19c or older');
  $ELSE
    DBMS_OUTPUT.PUT_LINE('newer');
  $END
END;
/
DECLARE
  l_cnt INTEGER;
BEGIN
  FOR i IN 1 .. 3 LOOP
    l_cnt := i ** 2;
  END LOOP;
  FOR i IN 1 .. 10 BY 2 LOOP NULL; END LOOP;
END;
/
