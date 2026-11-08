SELECT * FROM employee WHERE workdept IN (SELECT deptno FROM department WHERE admrdept = 'A00');
SELECT * FROM employee e WHERE EXISTS (SELECT 1 FROM project p WHERE p.respemp = e.empno);
SELECT * FROM employee e WHERE NOT EXISTS (SELECT 1 FROM emp_act a WHERE a.empno = e.empno);
SELECT empno, (SELECT deptname FROM department d WHERE d.deptno = e.workdept) AS dname FROM employee e;
SELECT * FROM employee WHERE salary > ALL (SELECT salary FROM employee WHERE workdept = 'D11');
SELECT * FROM employee WHERE salary > ANY (SELECT salary FROM employee WHERE workdept = 'E21');
SELECT * FROM employee WHERE salary = SOME (SELECT salary FROM bonus_table);
SELECT * FROM t WHERE (a, b) IN (SELECT x, y FROM u);
SELECT * FROM t WHERE (a, b) = (1, 2);
WITH dept_total (deptno, total) AS (
    SELECT workdept, SUM(salary) FROM employee GROUP BY workdept
)
SELECT deptno, total FROM dept_total WHERE total > (SELECT AVG(total) FROM dept_total);
WITH a AS (SELECT 1 AS x FROM sysibm.sysdummy1),
     b AS (SELECT x + 1 AS y FROM a)
SELECT * FROM a, b;
-- recursive CTE (Db2 has no RECURSIVE keyword)
WITH org (level, empno, mgrno) AS (
    SELECT 1, empno, mgrno FROM emp WHERE mgrno IS NULL
    UNION ALL
    SELECT o.level + 1, e.empno, e.mgrno FROM org o, emp e WHERE e.mgrno = o.empno AND o.level < 10
)
SELECT level, empno FROM org ORDER BY level;
WITH rpl (part, subpart, quantity) AS (
    SELECT root.part, root.subpart, root.quantity FROM partlist root WHERE root.part = '01'
    UNION ALL
    SELECT child.part, child.subpart, child.quantity FROM rpl parent, partlist child WHERE parent.subpart = child.part
)
SELECT DISTINCT part, subpart, quantity FROM rpl ORDER BY part, subpart;
SELECT * FROM t WHERE a = (SELECT MAX(a) FROM t);
SELECT * FROM t WHERE ((SELECT COUNT(*) FROM u WHERE u.id = t.id)) > 1;
