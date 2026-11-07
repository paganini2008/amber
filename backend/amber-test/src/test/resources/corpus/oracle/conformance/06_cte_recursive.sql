-- Oracle conformance: CTEs incl. recursive (subquery factoring)
WITH big_orders AS (SELECT * FROM orders WHERE amount > 1000)
SELECT * FROM big_orders;
WITH a AS (SELECT 1 x FROM dual), b AS (SELECT x + 1 y FROM a)
SELECT * FROM a, b;
WITH dept_costs (dept_name, total) AS (
  SELECT d.dept_name, SUM(e.salary) FROM employees e JOIN departments d ON d.dept_id = e.dept_id GROUP BY d.dept_name
), avg_cost AS (SELECT AVG(total) avg_total FROM dept_costs)
SELECT * FROM dept_costs WHERE total > (SELECT avg_total FROM avg_cost) ORDER BY dept_name;
WITH org (emp_id, manager_id, lvl, path) AS (
  SELECT emp_id, manager_id, 1, CAST(family_name AS VARCHAR2(4000)) FROM employees WHERE manager_id IS NULL
  UNION ALL
  SELECT e.emp_id, e.manager_id, o.lvl + 1, o.path || '/' || e.family_name
  FROM employees e JOIN org o ON e.manager_id = o.emp_id
)
SEARCH DEPTH FIRST BY emp_id SET order_seq
CYCLE emp_id SET is_cycle TO 'Y' DEFAULT 'N'
SELECT * FROM org ORDER BY order_seq;
WITH nums (n) AS (SELECT 1 FROM dual UNION ALL SELECT n + 1 FROM nums WHERE n < 100)
SELECT SUM(n) FROM nums;
WITH org (emp_id, manager_id) AS (
  SELECT emp_id, manager_id FROM employees WHERE emp_id = 1
  UNION ALL
  SELECT e.emp_id, e.manager_id FROM employees e, org o WHERE e.manager_id = o.emp_id
) SEARCH BREADTH FIRST BY emp_id SET ord
SELECT emp_id FROM org ORDER BY ord;
WITH
  FUNCTION fmt_amount(p NUMBER) RETURN VARCHAR2 IS
  BEGIN
    RETURN TO_CHAR(p, 'FM999G999D00');
  END;
SELECT fmt_amount(amount) FROM orders
/
WITH
  PROCEDURE log_it(p VARCHAR2) IS BEGIN NULL; END;
  FUNCTION twice(p NUMBER) RETURN NUMBER IS BEGIN log_it('x'); RETURN p * 2; END;
SELECT twice(amount) FROM orders
/
INSERT INTO order_summary (customer_id, total)
WITH t AS (SELECT customer_id, SUM(amount) total FROM orders GROUP BY customer_id)
SELECT customer_id, total FROM t;
CREATE VIEW v_top_customers AS
WITH t AS (SELECT customer_id, SUM(amount) total FROM orders GROUP BY customer_id)
SELECT * FROM t WHERE total > 10000;
SELECT * FROM (WITH x AS (SELECT 1 a FROM dual) SELECT a FROM x);
WITH x AS (SELECT /*+ MATERIALIZE */ * FROM orders) SELECT COUNT(*) FROM x;
