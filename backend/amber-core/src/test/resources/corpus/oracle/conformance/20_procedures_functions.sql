-- Oracle conformance: standalone procedures and functions (PL/SQL)
CREATE OR REPLACE PROCEDURE raise_salary (
  p_emp_id IN employees.employee_id%TYPE,
  p_pct    IN NUMBER DEFAULT 5,
  p_new    OUT NUMBER
) AS
  v_old employees.salary%TYPE;
BEGIN
  SELECT salary INTO v_old FROM employees WHERE employee_id = p_emp_id FOR UPDATE;
  p_new := ROUND(v_old * (1 + p_pct / 100), 2);
  UPDATE employees SET salary = p_new WHERE employee_id = p_emp_id;
EXCEPTION
  WHEN NO_DATA_FOUND THEN
    RAISE_APPLICATION_ERROR(-20001, 'Employee ' || p_emp_id || ' not found');
END raise_salary;
/
CREATE OR REPLACE FUNCTION get_customer_total (p_customer_id IN NUMBER) RETURN NUMBER
  DETERMINISTIC
IS
  v_total NUMBER := 0;
BEGIN
  SELECT NVL(SUM(amount), 0) INTO v_total FROM orders WHERE customer_id = p_customer_id;
  RETURN v_total;
END;
/
CREATE OR REPLACE FUNCTION split_csv (p_list IN VARCHAR2) RETURN sys.odcivarchar2list PIPELINED IS
BEGIN
  FOR r IN (SELECT REGEXP_SUBSTR(p_list, '[^,]+', 1, LEVEL) AS item FROM dual CONNECT BY REGEXP_SUBSTR(p_list, '[^,]+', 1, LEVEL) IS NOT NULL) LOOP
    PIPE ROW (r.item);
  END LOOP;
  RETURN;
END split_csv;
/
CREATE OR REPLACE FUNCTION cached_rate (p_code VARCHAR2) RETURN NUMBER RESULT_CACHE PARALLEL_ENABLE IS
  v NUMBER;
BEGIN
  SELECT rate INTO v FROM fx_rates WHERE code = p_code;
  RETURN v;
END;
/
CREATE OR REPLACE PROCEDURE log_message (p_msg VARCHAR2) AUTHID CURRENT_USER IS
  PRAGMA AUTONOMOUS_TRANSACTION;
BEGIN
  INSERT INTO app_log (logged_at, msg) VALUES (SYSTIMESTAMP, SUBSTR(p_msg, 1, 4000));
  COMMIT;
END log_message;
/
CREATE OR REPLACE PROCEDURE no_args IS BEGIN NULL; END;
/
CREATE OR REPLACE PROCEDURE in_out_demo (p_val IN OUT NOCOPY VARCHAR2, p_cur OUT SYS_REFCURSOR) IS
BEGIN
  p_val := UPPER(p_val);
  OPEN p_cur FOR SELECT * FROM orders WHERE status = p_val;
END;
/
CREATE OR REPLACE FUNCTION is_weekend (p_d DATE) RETURN BOOLEAN IS
BEGIN
  RETURN TO_CHAR(p_d, 'DY', 'NLS_DATE_LANGUAGE=ENGLISH') IN ('SAT', 'SUN');
END;
/
CREATE OR REPLACE FUNCTION ext_fn (x BINARY_INTEGER) RETURN BINARY_INTEGER AS LANGUAGE C NAME "ext_fn" LIBRARY ext_lib PARAMETERS (x INT);
/
CREATE OR REPLACE PROCEDURE java_proc (p VARCHAR2) AS LANGUAGE JAVA NAME 'com.example.Util.run(java.lang.String)';
/
ALTER PROCEDURE raise_salary COMPILE;
ALTER FUNCTION get_customer_total COMPILE DEBUG;
DROP PROCEDURE no_args;
DROP FUNCTION is_weekend;
CALL log_message('hello');
EXEC log_message('hello from exec')
EXECUTE raise_salary(100, 10, :new_sal)
BEGIN raise_salary(p_emp_id => 100, p_new => :new_sal); END;
/
SELECT get_customer_total(42) FROM dual;
SELECT * FROM TABLE(split_csv('a,b,c'));
SELECT * FROM split_csv('a,b,c');
