-- Oracle conformance: packages (spec and body)
CREATE OR REPLACE PACKAGE order_api AUTHID DEFINER AS
  c_max_lines CONSTANT PLS_INTEGER := 999;
  g_debug     BOOLEAN := FALSE;
  TYPE order_rec IS RECORD (order_id orders.order_id%TYPE, amount orders.amount%TYPE);
  TYPE order_tab IS TABLE OF order_rec INDEX BY PLS_INTEGER;
  TYPE id_list IS TABLE OF NUMBER;
  TYPE ref_cur IS REF CURSOR RETURN orders%ROWTYPE;
  e_invalid_order EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_invalid_order, -20100);
  CURSOR c_open RETURN orders%ROWTYPE;
  FUNCTION create_order (p_customer_id NUMBER, p_amount NUMBER) RETURN NUMBER;
  PROCEDURE cancel_order (p_order_id NUMBER);
  PROCEDURE cancel_order (p_order_ids id_list);
  FUNCTION open_orders RETURN order_tab;
  FUNCTION total_for (p_customer_id NUMBER) RETURN NUMBER RESULT_CACHE;
END order_api;
/
CREATE OR REPLACE PACKAGE BODY order_api AS
  CURSOR c_open RETURN orders%ROWTYPE IS SELECT * FROM orders WHERE status = 'OPEN';

  PROCEDURE debug (p_msg VARCHAR2) IS
  BEGIN
    IF g_debug THEN
      DBMS_OUTPUT.PUT_LINE(TO_CHAR(SYSTIMESTAMP, 'HH24:MI:SS.FF3') || ' ' || p_msg);
    END IF;
  END debug;

  FUNCTION create_order (p_customer_id NUMBER, p_amount NUMBER) RETURN NUMBER IS
    v_id orders.order_id%TYPE;
  BEGIN
    IF p_amount <= 0 THEN
      RAISE e_invalid_order;
    END IF;
    INSERT INTO orders (order_id, customer_id, amount, status)
    VALUES (orders_seq.NEXTVAL, p_customer_id, p_amount, 'OPEN')
    RETURNING order_id INTO v_id;
    debug('created ' || v_id);
    RETURN v_id;
  END create_order;

  PROCEDURE cancel_order (p_order_id NUMBER) IS
  BEGIN
    UPDATE orders SET status = 'CANCELLED' WHERE order_id = p_order_id;
    IF SQL%ROWCOUNT = 0 THEN
      RAISE_APPLICATION_ERROR(-20101, 'No such order');
    END IF;
  END cancel_order;

  PROCEDURE cancel_order (p_order_ids id_list) IS
  BEGIN
    FORALL i IN 1 .. p_order_ids.COUNT
      UPDATE orders SET status = 'CANCELLED' WHERE order_id = p_order_ids(i);
  END cancel_order;

  FUNCTION open_orders RETURN order_tab IS
    v_tab order_tab;
  BEGIN
    SELECT order_id, amount BULK COLLECT INTO v_tab FROM orders WHERE status = 'OPEN';
    RETURN v_tab;
  END open_orders;

  FUNCTION total_for (p_customer_id NUMBER) RETURN NUMBER RESULT_CACHE IS
    v NUMBER;
  BEGIN
    SELECT SUM(amount) INTO v FROM orders WHERE customer_id = p_customer_id;
    RETURN v;
  END total_for;

BEGIN
  g_debug := SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER') = 'DEBUG';
END order_api;
/
CREATE OR REPLACE PACKAGE constants_pkg IS
  c_app_name CONSTANT VARCHAR2(30) := 'OrderHub';
  c_version  CONSTANT NUMBER := 3.2;
END;
/
CREATE OR REPLACE PACKAGE accessible_pkg ACCESSIBLE BY (PACKAGE order_api) AS
  PROCEDURE helper;
END accessible_pkg;
/
ALTER PACKAGE order_api COMPILE;
ALTER PACKAGE order_api COMPILE BODY;
DROP PACKAGE BODY constants_pkg;
DROP PACKAGE constants_pkg;
