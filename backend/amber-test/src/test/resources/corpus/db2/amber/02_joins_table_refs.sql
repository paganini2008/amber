SELECT e.empno, d.deptname FROM employee e INNER JOIN department d ON e.workdept = d.deptno;
SELECT e.empno, d.deptname FROM employee e JOIN department d ON e.workdept = d.deptno;
SELECT * FROM a LEFT OUTER JOIN b ON a.id = b.id RIGHT JOIN c ON b.id = c.id FULL OUTER JOIN d ON c.id = d.id;
SELECT * FROM a LEFT JOIN b ON a.id = b.id AND b.flag = 'Y' FULL JOIN c ON c.id = a.id;
SELECT * FROM a CROSS JOIN b;
SELECT * FROM a, b, c WHERE a.id = b.id AND b.id = c.id;
SELECT * FROM (a JOIN b ON a.id = b.id) LEFT JOIN c ON c.id = a.id;
SELECT * FROM a JOIN (b JOIN c ON b.x = c.x) ON a.id = b.id;
SELECT d.deptno, x.maxsal
FROM department d,
     LATERAL (SELECT MAX(salary) AS maxsal FROM employee e WHERE e.workdept = d.deptno) AS x;
SELECT d.deptno, x.cnt FROM department d, TABLE (SELECT COUNT(*) AS cnt FROM employee e WHERE e.workdept = d.deptno) x;
SELECT * FROM TABLE (app.get_orders(2024, 'OPEN')) AS o (id, amount);
SELECT t.* FROM TABLE(MON_GET_TABLE('', '', -2)) AS t ORDER BY t.rows_read DESC FETCH FIRST 10 ROWS ONLY;
SELECT * FROM TABLE(SYSPROC.ADMIN_GET_TAB_INFO('APP', 'ORDERS')) t;
SELECT * FROM (SELECT a, b FROM t WHERE c = 1) AS sub (x, y) WHERE x > 0;
SELECT * FROM (SELECT a FROM t ORDER BY a FETCH FIRST 5 ROWS ONLY) s;
SELECT * FROM UNNEST(:arr) WITH ORDINALITY AS u (val, idx);
SELECT * FROM policy FOR SYSTEM_TIME AS OF TIMESTAMP '2023-01-01-00.00.00' p;
SELECT * FROM policy FOR BUSINESS_TIME FROM '2023-01-01' TO '2024-01-01' WHERE id = 1;
SELECT * FROM policy FOR SYSTEM_TIME BETWEEN CURRENT TIMESTAMP - 1 YEAR AND CURRENT TIMESTAMP;
SELECT AVG(salary) FROM employee TABLESAMPLE BERNOULLI (10) REPEATABLE (42);
SELECT COUNT(*) FROM sales TABLESAMPLE SYSTEM (5);
SELECT o.id, c.name
FROM app.orders o
    INNER JOIN app.customers c ON c.id = o.customer_id
    LEFT OUTER JOIN app.order_notes n ON n.order_id = o.id
WHERE n.order_id IS NULL;
