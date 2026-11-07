-- CTEs and window functions
WITH paid AS (SELECT * FROM sales.order_header WHERE status = 'PAID') SELECT customer_id, SUM(total) FROM paid GROUP BY customer_id;
WITH a(x) AS (SELECT 1), b AS (SELECT x + 1 AS y FROM a) SELECT * FROM a CROSS JOIN b;
WITH RECURSIVE nums(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM nums WHERE n < 50) SELECT n FROM nums;
WITH RECURSIVE chain(id, parent_id, lvl) AS (
  SELECT id, parent_id, 1 FROM audit.category WHERE parent_id IS NULL
  UNION ALL
  SELECT c.id, c.parent_id, ch.lvl + 1 FROM audit.category c JOIN chain ch ON c.parent_id = ch.id
) SELECT * FROM chain ORDER BY lvl;
SELECT order_id, ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY ordered_at DESC) AS rn FROM sales.order_header;
SELECT order_id, RANK() OVER w, DENSE_RANK() OVER w, PERCENT_RANK() OVER w, CUME_DIST() OVER w, NTILE(4) OVER w FROM sales.order_header WINDOW w AS (ORDER BY total);
SELECT order_id, LAG(total, 1, 0) OVER (ORDER BY order_id), LEAD(total) IGNORE NULLS OVER (ORDER BY order_id) FROM sales.order_header;
SELECT order_id, FIRST_VALUE(total) OVER (PARTITION BY customer_id ORDER BY order_id ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) FROM sales.order_header;
SELECT order_id, NTH_VALUE(total, 2) FROM LAST OVER (ORDER BY order_id RANGE BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING) FROM sales.order_header;
SELECT order_id, SUM(total) OVER (ORDER BY order_id ROWS BETWEEN 2 PRECEDING AND CURRENT ROW EXCLUDE CURRENT ROW) FROM sales.order_header;
SELECT order_id, AVG(total) OVER (ORDER BY ordered_at GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING EXCLUDE TIES) FROM sales.order_header;
SELECT order_id, RATIO_TO_REPORT(total) OVER (PARTITION BY customer_id) FROM sales.order_header;
SELECT customer_id, total, SUM(total) OVER (PARTITION BY customer_id) AS cust_total, COUNT(*) OVER () AS all_cnt FROM sales.order_header;
SELECT * FROM (SELECT o.*, ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY total DESC) rn FROM sales.order_header o) WHERE rn <= 3;
SELECT customer_id, total FROM sales.order_header QUALIFY ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY total DESC) = 1;
