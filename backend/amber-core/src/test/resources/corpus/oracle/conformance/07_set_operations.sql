-- Oracle conformance: set operations
SELECT customer_id FROM orders UNION SELECT customer_id FROM returns;
SELECT customer_id FROM orders UNION ALL SELECT customer_id FROM returns;
SELECT customer_id FROM orders INTERSECT SELECT customer_id FROM returns;
SELECT customer_id FROM orders MINUS SELECT customer_id FROM returns;
SELECT customer_id FROM orders EXCEPT SELECT customer_id FROM returns;
SELECT customer_id FROM orders EXCEPT ALL SELECT customer_id FROM returns;
SELECT customer_id FROM orders INTERSECT ALL SELECT customer_id FROM returns;
SELECT customer_id FROM orders MINUS ALL SELECT customer_id FROM returns;
(SELECT customer_id FROM orders) UNION (SELECT customer_id FROM returns) ORDER BY 1;
SELECT a, b FROM t1 UNION ALL SELECT a, b FROM t2 UNION ALL SELECT a, b FROM t3 ORDER BY a, b;
SELECT a FROM t1 UNION (SELECT a FROM t2 INTERSECT SELECT a FROM t3);
SELECT 'X' AS src, a FROM t1 UNION ALL SELECT 'Y', a FROM t2 ORDER BY src FETCH FIRST 3 ROWS ONLY;
SELECT * FROM (SELECT a FROM t1 UNION SELECT a FROM t2) u WHERE u.a > 0;
INSERT INTO all_ids (id) SELECT id FROM t1 UNION SELECT id FROM t2;
