-- Subqueries and common table expressions
SELECT * FROM test WHERE id IN (SELECT id FROM other);
SELECT * FROM test WHERE id NOT IN (SELECT id FROM other WHERE other.x IS NOT NULL);
SELECT * FROM test t WHERE EXISTS (SELECT 1 FROM other o WHERE o.id = t.id);
SELECT * FROM test t WHERE NOT EXISTS (SELECT 1 FROM other o WHERE o.id = t.id);
SELECT * FROM test WHERE UNIQUE (SELECT a FROM other);
SELECT name, (SELECT COUNT(*) FROM orders o WHERE o.customer_id = c.id) AS order_count FROM customers c;
SELECT * FROM test WHERE amount > ALL (SELECT amount FROM other);
SELECT * FROM test WHERE amount = ANY (SELECT amount FROM other);
SELECT * FROM test WHERE amount < SOME (SELECT amount FROM other);
SELECT * FROM test WHERE id = ANY (?);
SELECT * FROM (SELECT id, name FROM test) AS sub WHERE sub.id > 10;
SELECT * FROM (SELECT * FROM (SELECT 1 AS x) a) b;
WITH cte AS (SELECT 1 AS n) SELECT * FROM cte;
WITH a AS (SELECT 1 x), b (y) AS (SELECT x + 1 FROM a) SELECT * FROM a, b;
WITH RECURSIVE t(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM t WHERE n < 100
)
SELECT SUM(n) FROM t;
WITH RECURSIVE tree(id, parent_id, depth, path) AS (
    SELECT id, parent_id, 0, CAST(name AS VARCHAR(1000)) FROM category WHERE parent_id IS NULL
    UNION ALL
    SELECT c.id, c.parent_id, tree.depth + 1, tree.path || '/' || c.name
    FROM category c JOIN tree ON c.parent_id = tree.id
)
SELECT * FROM tree ORDER BY path;
WITH cte AS (SELECT id FROM test WHERE x = 1)
INSERT INTO archive SELECT * FROM test WHERE id IN (SELECT id FROM cte);
WITH cte AS (SELECT id FROM test WHERE x = 1)
UPDATE test SET flag = TRUE WHERE id IN (SELECT id FROM cte);
WITH cte AS (SELECT id FROM test WHERE x = 1)
DELETE FROM test WHERE id IN (SELECT id FROM cte);
SELECT * FROM FINAL TABLE (INSERT INTO test(name) VALUES ('new'));
SELECT id FROM NEW TABLE (INSERT INTO test(name) SELECT name FROM staging);
SELECT * FROM OLD TABLE (DELETE FROM test WHERE id < 10);
SELECT * FROM OLD TABLE (UPDATE test SET name = 'x' WHERE id = 1);
SELECT * FROM FINAL TABLE (UPDATE test SET counter = counter + 1 WHERE id = 1);
SELECT * FROM FINAL TABLE (MERGE INTO test KEY (id) VALUES (1, 'a'));
SELECT ARRAY(SELECT id FROM test ORDER BY id);
SELECT (SELECT MAX(id) FROM test) + 1;
SELECT * FROM test WHERE (a, b) IN (SELECT a, b FROM other);
SELECT * FROM test WHERE (a, b) = (1, 2);
SELECT * FROM test WHERE ROW(a, b) <> ROW(3, 4);
