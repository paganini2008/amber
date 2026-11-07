-- CTEs, set operations, grouping
WITH recent AS (SELECT * FROM orders WHERE order_date >= CURDATE() - INTERVAL 30 DAY)
SELECT customer_id, COUNT(*) FROM recent GROUP BY customer_id;
WITH a AS (SELECT 1 AS n), b (m) AS (SELECT n + 1 FROM a) SELECT * FROM a JOIN b;
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 100) SELECT n FROM seq;
WITH RECURSIVE org_tree AS (
  SELECT emp_id, manager_id, emp_name, 1 AS lvl, CAST(emp_name AS CHAR(500)) AS path FROM employees WHERE manager_id IS NULL
  UNION ALL
  SELECT e.emp_id, e.manager_id, e.emp_name, t.lvl + 1, CONCAT(t.path, ' > ', e.emp_name) FROM employees e JOIN org_tree t ON e.manager_id = t.emp_id
)
SELECT * FROM org_tree ORDER BY path;
INSERT INTO order_totals (customer_id, total) WITH t AS (SELECT customer_id, SUM(line_total) s FROM orders GROUP BY customer_id) SELECT customer_id, s FROM t;
UPDATE customers c JOIN (WITH t AS (SELECT customer_id, SUM(line_total) s FROM orders GROUP BY customer_id) SELECT * FROM t) x ON x.customer_id = c.customer_id SET c.balance = x.s;
WITH t AS (SELECT customer_id FROM customers WHERE status = 'banned') DELETE FROM orders WHERE customer_id IN (SELECT customer_id FROM t);
WITH t AS (SELECT customer_id FROM customers WHERE status = 'banned') UPDATE orders SET qty = 0 WHERE customer_id IN (SELECT customer_id FROM t);
SELECT customer_id FROM orders UNION SELECT customer_id FROM customers;
SELECT customer_id FROM orders UNION ALL SELECT customer_id FROM customers ORDER BY 1 LIMIT 5;
SELECT customer_id FROM orders UNION DISTINCT SELECT customer_id FROM customers;
SELECT customer_id FROM orders INTERSECT SELECT customer_id FROM customers;
SELECT customer_id FROM customers EXCEPT SELECT customer_id FROM orders;
SELECT customer_id FROM customers EXCEPT ALL SELECT customer_id FROM orders;
SELECT customer_id FROM orders INTERSECT ALL SELECT customer_id FROM customers;
SELECT customer_id FROM customers WHERE customer_id IN (SELECT customer_id FROM orders INTERSECT SELECT customer_id FROM payments);
(SELECT customer_id FROM orders ORDER BY order_date LIMIT 3) UNION (SELECT customer_id FROM customers LIMIT 2);
(SELECT 1) UNION ALL (SELECT 2) ORDER BY 1;
SELECT region, status, SUM(balance) FROM customers GROUP BY region, status WITH ROLLUP;
SELECT region, status, SUM(balance), GROUPING(region), GROUPING(status) FROM customers GROUP BY region, status WITH ROLLUP HAVING SUM(balance) > 0;
SELECT status, COUNT(*) AS cnt FROM customers GROUP BY status HAVING cnt > 1 ORDER BY cnt DESC;
SELECT YEAR(order_date) y, MONTH(order_date) m, SUM(line_total) FROM orders GROUP BY y, m ORDER BY y, m;
SELECT customer_id, ANY_VALUE(email) FROM customers GROUP BY customer_id;
SELECT BIT_OR(bitmask), STD(score), STDDEV_SAMP(score), VAR_POP(score), JSON_ARRAYAGG(email), JSON_OBJECTAGG(customer_id, email) FROM customers;
