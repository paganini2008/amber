-- CTEs and window functions
WITH big AS (SELECT * FROM account_event WHERE amount > 1000) SELECT count(*) FROM big;
WITH a(x) AS (SELECT 1), b AS (SELECT x + 1 AS y FROM a) SELECT * FROM a, b;
WITH RECURSIVE cnt(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM cnt WHERE n < 100) SELECT n FROM cnt;
WITH RECURSIVE org(emp_id, mgr_id, depth) AS (
  SELECT emp_id, mgr_id, 0 FROM employee WHERE mgr_id IS NULL
  UNION ALL
  SELECT e.emp_id, e.mgr_id, o.depth + 1 FROM employee e JOIN org o ON e.mgr_id = o.emp_id
  ORDER BY 3 DESC LIMIT 1000
) SELECT * FROM org;
WITH heavy AS MATERIALIZED (SELECT customer_id, sum(amount) s FROM account_event GROUP BY 1) SELECT * FROM heavy WHERE s > 0;
WITH light AS NOT MATERIALIZED (SELECT * FROM customer) SELECT * FROM light;
WITH tmp AS (SELECT customer_id FROM customer) INSERT INTO scratch_ids SELECT customer_id FROM tmp;
WITH stale AS (SELECT customer_id FROM customer WHERE is_active = 0) DELETE FROM account_event WHERE customer_id IN stale;
WITH upd AS (SELECT 1 AS id) UPDATE customer SET balance = 0 WHERE customer_id IN (SELECT id FROM upd);
SELECT customer_id, amount, row_number() OVER (PARTITION BY customer_id ORDER BY amount DESC) AS rn FROM account_event;
SELECT customer_id, rank() OVER w, dense_rank() OVER w, percent_rank() OVER w, cume_dist() OVER w, ntile(4) OVER w FROM account_event WINDOW w AS (ORDER BY amount);
SELECT amount, lag(amount, 1, 0) OVER (ORDER BY event_id), lead(amount) OVER (ORDER BY event_id) FROM account_event;
SELECT amount, first_value(amount) OVER (ORDER BY event_id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM account_event;
SELECT amount, last_value(amount) OVER (ORDER BY event_id RANGE BETWEEN 1 PRECEDING AND 1 FOLLOWING) FROM account_event;
SELECT amount, nth_value(amount, 2) OVER (ORDER BY event_id GROUPS BETWEEN 1 PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE CURRENT ROW) FROM account_event;
SELECT sum(amount) OVER (ORDER BY event_id ROWS 2 PRECEDING EXCLUDE TIES) FROM account_event;
SELECT sum(amount) OVER (ORDER BY event_id ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING EXCLUDE GROUP) FROM account_event;
SELECT sum(amount) OVER (ORDER BY event_id ROWS UNBOUNDED PRECEDING EXCLUDE NO OTHERS) FROM account_event;
SELECT count(*) FILTER (WHERE amount > 0) OVER (PARTITION BY region_code) FROM account_event;
SELECT sum(amount) OVER win1 FROM account_event WINDOW win1 AS (PARTITION BY region_code), win2 AS (win1 ORDER BY event_id);
SELECT sum(amount) OVER (win1 ORDER BY event_id) FROM account_event WINDOW win1 AS (PARTITION BY region_code);
SELECT avg(amount) OVER () FROM account_event;
SELECT customer_id, amount FROM (SELECT customer_id, amount, row_number() OVER (PARTITION BY customer_id ORDER BY amount DESC) rn FROM account_event) WHERE rn = 1;
