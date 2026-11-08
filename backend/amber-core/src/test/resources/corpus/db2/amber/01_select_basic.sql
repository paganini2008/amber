-- Basic queries
SELECT a FROM s.t;
SELECT * FROM employee;
SELECT e.* FROM hr.employee e;
SELECT DISTINCT workdept FROM employee;
SELECT ALL workdept FROM employee;
SELECT empno, lastname AS name, salary * 1.1 AS raise, bonus total FROM employee WHERE salary > 50000;
SELECT 1 FROM sysibm.sysdummy1;
SELECT CURRENT DATE, CURRENT TIME, CURRENT TIMESTAMP FROM sysibm.sysdummy1;
SELECT CURRENT TIMESTAMP(6), CURRENT SCHEMA, CURRENT USER, CURRENT SERVER, USER, SESSION_USER FROM sysibm.sysdummy1;
SELECT CURRENT_DATE, CURRENT_TIMESTAMP, CURRENT PATH, CURRENT TIMEZONE, CURRENT TIME ZONE FROM sysibm.sysdummy1;
SELECT CURRENT DEGREE, CURRENT ISOLATION, CURRENT MEMBER, CURRENT LOCK TIMEOUT, CURRENT QUERY OPTIMIZATION FROM sysibm.sysdummy1;
SELECT name, type, value, status, date, year, user, data, comment, position FROM app.settings;
SELECT t.name, t.type, t.timestamp, t.time, t.level, t.key, t.index FROM app.audit t;
SELECT "Order", "select", "Mixed Case Col" FROM "MySchema"."My Table";
SELECT col@1, #tmp, $amount, emp_no FROM legacy.t1;
SELECT a, b
FROM t
WHERE a = 1 AND (b = 2 OR c IS NULL) AND NOT d = 3;
SELECT COUNT(*) FROM employee;
SELECT empno FROM employee WHERE workdept IN ('A00', 'B01') AND hiredate < '2000-01-01';
VALUES 1;
VALUES (1, 'a'), (2, 'b'), (3, 'c');
VALUES CURRENT DATE;
SELECT * FROM (VALUES (1, 'one'), (2, 'two')) AS v (id, label);
(SELECT a FROM t1);
SELECT x.a FROM t1 x WHERE x.a > :hv AND x.b = :hv2 :ind2 AND x.c = ?;
SELECT a INTO :out1, :out2 :ind FROM t WHERE id = :id;
