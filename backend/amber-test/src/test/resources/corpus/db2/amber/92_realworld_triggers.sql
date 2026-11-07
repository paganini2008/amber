-- =====================================================================
-- Auditing and business-rule triggers for the HR schema
-- Run with: db2 -td@ -vf 92_realworld_triggers.sql
-- =====================================================================
--#SET TERMINATOR @
SET SCHEMA hr@

CREATE TABLE hr.audit_trail (
    audit_id    BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1 INCREMENT BY 1 CACHE 100),
    table_name  VARCHAR(128) NOT NULL,
    operation   CHAR(1) NOT NULL CHECK (operation IN ('I', 'U', 'D')),
    pk_value    VARCHAR(64) NOT NULL,
    column_name VARCHAR(128),
    old_value   VARCHAR(1000),
    new_value   VARCHAR(1000),
    changed_by  VARCHAR(128) NOT NULL DEFAULT SESSION_USER,
    changed_ts  TIMESTAMP NOT NULL DEFAULT CURRENT TIMESTAMP,
    PRIMARY KEY (audit_id)
) IN ts_audit COMPRESS YES@

-- Defaults and normalisation before insert
CREATE OR REPLACE TRIGGER hr.employee_bi
    NO CASCADE BEFORE INSERT ON hr.employee
    REFERENCING NEW AS n
    FOR EACH ROW MODE DB2SQL
BEGIN ATOMIC
    SET n.lastname = UPPER(TRIM(n.lastname)),
        n.firstnme = INITCAP(TRIM(n.firstnme)),
        n.email = LOWER(COALESCE(n.email, n.firstnme || '.' || n.lastname || '@example.com'));
    IF n.hiredate IS NULL THEN
        SET n.hiredate = CURRENT DATE;
    END IF;
    IF n.hiredate > CURRENT DATE + 6 MONTHS THEN
        SIGNAL SQLSTATE '75200' SET MESSAGE_TEXT = 'Hire date too far in the future';
    END IF;
    IF n.empno IS NULL THEN
        SET n.empno = DIGITS(NEXT VALUE FOR hr.seq_empno);
    END IF;
END@

-- Salary rules before update
CREATE OR REPLACE TRIGGER hr.employee_bu_salary
    NO CASCADE BEFORE UPDATE OF salary, bonus, comm ON hr.employee
    REFERENCING OLD AS o NEW AS n
    FOR EACH ROW MODE DB2SQL
    WHEN (n.salary <> o.salary OR n.bonus IS DISTINCT FROM o.bonus)
BEGIN ATOMIC
    DECLARE v_max_raise DECIMAL(5, 2);
    SET v_max_raise = (SELECT max_raise_pct FROM hr.job_rules r WHERE r.job = n.job);
    IF n.salary > o.salary * (1 + COALESCE(v_max_raise, 10) / 100) THEN
        SIGNAL SQLSTATE '75201'
            SET MESSAGE_TEXT = 'Raise for ' || n.empno || ' exceeds ' || CHAR(COALESCE(v_max_raise, 10)) || '%';
    END IF;
    IF n.salary < 0 OR n.bonus < 0 THEN
        SIGNAL SQLSTATE '75202' SET MESSAGE_TEXT = 'Negative compensation';
    END IF;
END@

-- Column-level audit after update
CREATE OR REPLACE TRIGGER hr.employee_au_audit
    AFTER UPDATE ON hr.employee
    REFERENCING OLD AS o NEW AS n
    FOR EACH ROW MODE DB2SQL
BEGIN ATOMIC
    IF o.salary <> n.salary THEN
        INSERT INTO hr.audit_trail (table_name, operation, pk_value, column_name, old_value, new_value)
        VALUES ('EMPLOYEE', 'U', n.empno, 'SALARY', CHAR(o.salary), CHAR(n.salary));
    END IF;
    IF o.workdept IS DISTINCT FROM n.workdept THEN
        INSERT INTO hr.audit_trail (table_name, operation, pk_value, column_name, old_value, new_value)
        VALUES ('EMPLOYEE', 'U', n.empno, 'WORKDEPT', o.workdept, n.workdept);
        UPDATE hr.department d
           SET headcount = headcount + CASE WHEN d.deptno = n.workdept THEN 1 ELSE -1 END
         WHERE d.deptno IN (o.workdept, n.workdept);
    END IF;
    IF o.job <> n.job THEN
        INSERT INTO hr.audit_trail (table_name, operation, pk_value, column_name, old_value, new_value)
        VALUES ('EMPLOYEE', 'U', n.empno, 'JOB', o.job, n.job);
    END IF;
END@

-- Statement-level summary after delete
CREATE OR REPLACE TRIGGER hr.employee_ad
    AFTER DELETE ON hr.employee
    REFERENCING OLD TABLE AS gone
    FOR EACH STATEMENT MODE DB2SQL
BEGIN ATOMIC
    INSERT INTO hr.audit_trail (table_name, operation, pk_value, old_value)
    SELECT 'EMPLOYEE', 'D', g.empno, g.lastname || ', ' || g.firstnme FROM gone g;
    UPDATE hr.department d
       SET headcount = headcount - (SELECT COUNT(*) FROM gone g WHERE g.workdept = d.deptno)
     WHERE d.deptno IN (SELECT workdept FROM gone);
END@

-- Bulk insert notification
CREATE OR REPLACE TRIGGER hr.employee_ai_stmt
    AFTER INSERT ON hr.employee
    REFERENCING NEW_TABLE AS added
    FOR EACH STATEMENT MODE DB2SQL
    WHEN ((SELECT COUNT(*) FROM added) > 100)
    INSERT INTO hr.notifications (topic, payload, created_ts)
    VALUES ('BULK_HIRE', (SELECT CHAR(COUNT(*)) FROM added), CURRENT TIMESTAMP)@

-- Updatable view with INSTEAD OF triggers
CREATE OR REPLACE VIEW hr.v_employee_public (empno, name, dept, phone) AS
    SELECT empno, firstnme || ' ' || lastname, workdept, phoneno
    FROM hr.employee
    WHERE status <> 'TERMINATED'@

CREATE OR REPLACE TRIGGER hr.v_employee_public_iu
    INSTEAD OF UPDATE ON hr.v_employee_public
    REFERENCING OLD AS o NEW AS n
    FOR EACH ROW MODE DB2SQL
BEGIN ATOMIC
    UPDATE hr.employee e
       SET e.phoneno = n.phone,
           e.workdept = n.dept
     WHERE e.empno = o.empno;
END@

CREATE OR REPLACE TRIGGER hr.v_employee_public_id
    INSTEAD OF DELETE ON hr.v_employee_public
    REFERENCING OLD AS o
    FOR EACH ROW MODE DB2SQL
    UPDATE hr.employee SET status = 'TERMINATED', term_date = CURRENT DATE WHERE empno = o.empno@

-- Compiled trigger (SQL PL with loop) for multiple events
CREATE OR REPLACE TRIGGER hr.project_aiud
    AFTER INSERT OR UPDATE OR DELETE ON hr.project
    REFERENCING NEW AS n OLD AS o
    FOR EACH ROW
BEGIN
    DECLARE v_op CHAR(1);
    DECLARE v_key VARCHAR(64);
    DECLARE i INTEGER DEFAULT 0;
    IF INSERTING THEN
        SET v_op = 'I';
        SET v_key = n.projno;
    ELSEIF UPDATING THEN
        SET v_op = 'U';
        SET v_key = n.projno;
    ELSEIF DELETING THEN
        SET v_op = 'D';
        SET v_key = o.projno;
    END IF;
    INSERT INTO hr.audit_trail (table_name, operation, pk_value) VALUES ('PROJECT', v_op, v_key);
    WHILE i < 3 DO
        SET i = i + 1;
    END WHILE;
END@

COMMENT ON TRIGGER hr.employee_bu_salary IS 'Enforces job-specific raise limits'@
--#SET TERMINATOR ;
COMMIT;
