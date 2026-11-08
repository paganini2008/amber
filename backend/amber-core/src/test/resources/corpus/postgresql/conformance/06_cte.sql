-- CTEs incl. recursive, SEARCH/CYCLE, materialization, data-modifying
WITH t AS (SELECT customer_id, sum(amount) total FROM sales.orders GROUP BY customer_id) SELECT * FROM t WHERE total > 100;
WITH a AS (SELECT 1 AS x), b AS (SELECT x + 1 AS y FROM a) SELECT * FROM a, b;
WITH t(id, label) AS (VALUES (1, 'a'), (2, 'b')) SELECT * FROM t;
WITH RECURSIVE nums(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM nums WHERE n < 10) SELECT n FROM nums;
WITH RECURSIVE tree AS (
  SELECT employee_id, manager_id, name, 1 AS depth FROM hr.employee WHERE manager_id IS NULL
  UNION ALL
  SELECT e.employee_id, e.manager_id, e.name, t.depth + 1 FROM hr.employee e JOIN tree t ON e.manager_id = t.employee_id
) SELECT * FROM tree ORDER BY depth;
WITH RECURSIVE tree AS (
  SELECT node_id, parent_id FROM graph.node WHERE parent_id IS NULL
  UNION ALL
  SELECT n.node_id, n.parent_id FROM graph.node n JOIN tree t ON n.parent_id = t.node_id
) SEARCH DEPTH FIRST BY node_id SET ordercol
SELECT * FROM tree ORDER BY ordercol;
WITH RECURSIVE tree AS (
  SELECT node_id, parent_id FROM graph.node WHERE parent_id IS NULL
  UNION ALL
  SELECT n.node_id, n.parent_id FROM graph.node n JOIN tree t ON n.parent_id = t.node_id
) SEARCH BREADTH FIRST BY node_id SET ordercol
SELECT * FROM tree ORDER BY ordercol;
WITH RECURSIVE g(src, dst) AS (
  SELECT src, dst FROM graph.edge
  UNION ALL
  SELECT g.src, e.dst FROM graph.edge e JOIN g ON e.src = g.dst
) CYCLE src SET is_cycle USING path
SELECT * FROM g;
WITH RECURSIVE g(src, dst) AS (
  SELECT src, dst FROM graph.edge
  UNION ALL
  SELECT g.src, e.dst FROM graph.edge e JOIN g ON e.src = g.dst
) CYCLE src SET is_cycle TO true DEFAULT false USING path
SELECT * FROM g;
WITH w AS MATERIALIZED (SELECT * FROM sales.orders) SELECT * FROM w WHERE order_id = 1;
WITH w AS NOT MATERIALIZED (SELECT * FROM sales.orders) SELECT * FROM w WHERE order_id = 1;
WITH moved AS (DELETE FROM sales.orders WHERE status = 'ARCHIVED' RETURNING *) INSERT INTO sales.orders_archive SELECT * FROM moved;
WITH upd AS (UPDATE sales.orders SET status = 'DONE' WHERE shipped RETURNING order_id) SELECT count(*) FROM upd;
WITH ins AS (INSERT INTO audit.log(msg) VALUES ('x') RETURNING id) SELECT id FROM ins;
INSERT INTO sales.summary WITH s AS (SELECT 1 AS a) SELECT a FROM s;
WITH s AS (SELECT 1 AS a) INSERT INTO sales.summary SELECT a FROM s;
WITH s AS (SELECT order_id FROM sales.orders WHERE amount = 0) UPDATE sales.orders o SET status = 'VOID' FROM s WHERE o.order_id = s.order_id;
WITH s AS (SELECT order_id FROM sales.orders WHERE amount < 0) DELETE FROM sales.orders o USING s WHERE o.order_id = s.order_id;
SELECT * FROM (WITH x AS (SELECT 1 AS v) SELECT v FROM x) q;
