-- sql_mode=ORACLE: PL/SQL-style blocks, packages, %TYPE, sequences, etc.
SET SQL_MODE = ORACLE;
CREATE TABLE emp (empno NUMBER(6) PRIMARY KEY, ename VARCHAR2(50), sal NUMBER(10,2), deptno NUMBER(4), hired DATE, notes CLOB, pic BLOB, code RAW(16));
CREATE SEQUENCE emp_seq START WITH 1 INCREMENT BY 1;
INSERT INTO emp (empno, ename, sal) VALUES (emp_seq.nextval, 'SMITH', 800);
SELECT emp_seq.currval FROM DUAL;
SELECT ename || ' earns ' || sal AS line FROM emp;
SELECT NVL(sal, 0), DECODE(deptno, 10, 'ACC', 20, 'RES', 'OTHER'), TO_CHAR(hired, 'YYYY-MM-DD'), SUBSTR(ename, 1, 3) FROM emp;
SELECT * FROM emp WHERE ROWNUM <= 5;
SELECT CAST(sal AS VARCHAR(20)) FROM emp;
DELIMITER /
CREATE OR REPLACE PROCEDURE raise_salary(p_empno IN NUMBER, p_pct IN NUMBER DEFAULT 10, p_new OUT NUMBER)
AS
    v_sal emp.sal%TYPE;
    v_row emp%ROWTYPE;
    e_not_found EXCEPTION;
BEGIN
    SELECT sal INTO v_sal FROM emp WHERE empno = p_empno;
    p_new := v_sal * (1 + p_pct / 100);
    UPDATE emp SET sal = p_new WHERE empno = p_empno;
    IF SQL%ROWCOUNT = 0 THEN
        RAISE e_not_found;
    END IF;
EXCEPTION
    WHEN NO_DATA_FOUND THEN
        p_new := NULL;
    WHEN e_not_found THEN
        RAISE_APPLICATION_ERROR(-20001, 'employee missing');
    WHEN OTHERS THEN
        RAISE;
END;
/
CREATE OR REPLACE FUNCTION annual_sal(p_empno NUMBER) RETURN NUMBER
IS
    v NUMBER;
BEGIN
    SELECT sal * 12 INTO v FROM emp WHERE empno = p_empno;
    RETURN v;
END annual_sal;
/
CREATE OR REPLACE PACKAGE hr_pkg AS
    FUNCTION headcount(p_dept NUMBER) RETURN INT;
    PROCEDURE hire(p_name VARCHAR2, p_dept NUMBER);
END hr_pkg;
/
CREATE OR REPLACE PACKAGE BODY hr_pkg AS
    g_calls INT := 0;
    FUNCTION headcount(p_dept NUMBER) RETURN INT AS
        v_cnt INT;
    BEGIN
        SELECT COUNT(*) INTO v_cnt FROM emp WHERE deptno = p_dept;
        RETURN v_cnt;
    END;
    PROCEDURE hire(p_name VARCHAR2, p_dept NUMBER) AS
    BEGIN
        g_calls := g_calls + 1;
        INSERT INTO emp (empno, ename, deptno, hired) VALUES (emp_seq.nextval, p_name, p_dept, SYSDATE);
    END;
END hr_pkg;
/
CREATE OR REPLACE PROCEDURE list_emps
AS
    CURSOR c_emp IS SELECT empno, ename FROM emp ORDER BY empno;
    v_rec c_emp%ROWTYPE;
    i INT := 0;
BEGIN
    OPEN c_emp;
    LOOP
        FETCH c_emp INTO v_rec;
        EXIT WHEN c_emp%NOTFOUND;
        i := i + 1;
    END LOOP;
    CLOSE c_emp;
    FOR r IN (SELECT ename FROM emp) LOOP
        NULL;
    END LOOP;
    FOR j IN 1..3 LOOP
        i := i + j;
    END LOOP;
    WHILE i > 0 LOOP
        i := i - 1;
    END LOOP;
    <<outer_block>>
    BEGIN
        EXECUTE IMMEDIATE 'UPDATE emp SET sal = sal WHERE empno = :1' USING 1;
    END outer_block;
END;
/
DECLARE
    v_cnt NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_cnt FROM emp;
    IF v_cnt > 0 THEN
        hr_pkg.hire('JONES', 20);
    ELSIF v_cnt = 0 THEN
        NULL;
    ELSE
        raise_salary(1, 5, v_cnt);
    END IF;
END;
/
CREATE OR REPLACE TRIGGER emp_bi BEFORE INSERT ON emp FOR EACH ROW
BEGIN
    IF :NEW.hired IS NULL THEN
        :NEW.hired := SYSDATE;
    END IF;
END;
/
DELIMITER ;
CALL raise_salary(1, 10, @n);
SELECT hr_pkg.headcount(20) FROM DUAL;
DROP PACKAGE BODY IF EXISTS hr_pkg;
DROP PACKAGE IF EXISTS hr_pkg;
SET SQL_MODE = DEFAULT;
