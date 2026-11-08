-- Oracle conformance: analytic (window) functions and MATCH_RECOGNIZE
SELECT order_id, ROW_NUMBER() OVER (ORDER BY order_date) rn FROM orders;
SELECT order_id, RANK() OVER (PARTITION BY region ORDER BY amount DESC) rk, DENSE_RANK() OVER (PARTITION BY region ORDER BY amount DESC) drk FROM orders;
SELECT order_id, SUM(amount) OVER (PARTITION BY customer_id ORDER BY order_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) running FROM orders;
SELECT order_id, AVG(amount) OVER (ORDER BY order_date RANGE BETWEEN INTERVAL '7' DAY PRECEDING AND CURRENT ROW) avg7 FROM orders;
SELECT order_id, LAG(amount, 1, 0) OVER (ORDER BY order_date) prev_amt, LEAD(amount) OVER (ORDER BY order_date) next_amt FROM orders;
SELECT order_id, LAG(amount IGNORE NULLS) OVER (ORDER BY order_date) FROM orders;
SELECT order_id, FIRST_VALUE(amount) OVER (PARTITION BY region ORDER BY order_date) fv, LAST_VALUE(amount IGNORE NULLS) OVER (PARTITION BY region ORDER BY order_date ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) lv FROM orders;
SELECT order_id, NTH_VALUE(amount, 2) FROM FIRST RESPECT NULLS OVER (ORDER BY amount) FROM orders;
SELECT order_id, NTILE(4) OVER (ORDER BY amount) quartile, PERCENT_RANK() OVER (ORDER BY amount) pr, CUME_DIST() OVER (ORDER BY amount) cd FROM orders;
SELECT order_id, RATIO_TO_REPORT(amount) OVER (PARTITION BY region) pct_share FROM orders;
SELECT order_id, COUNT(*) OVER () total FROM orders;
SELECT order_id, SUM(amount) OVER (ORDER BY order_date ROWS 2 PRECEDING) FROM orders;
SELECT order_id, SUM(amount) OVER (ORDER BY order_date ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING EXCLUDE CURRENT ROW) FROM orders;
SELECT order_id, SUM(amount) OVER (ORDER BY order_date GROUPS BETWEEN 1 PRECEDING AND CURRENT ROW) FROM orders;
SELECT order_id, LISTAGG(product_id, ',') WITHIN GROUP (ORDER BY product_id) OVER (PARTITION BY customer_id) FROM orders;
SELECT * FROM (SELECT o.*, ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY order_date DESC) rn FROM orders o) WHERE rn = 1;
SELECT region, SUM(SUM(amount)) OVER (ORDER BY region) FROM orders GROUP BY region;
SELECT order_id, MAX(amount) KEEP (DENSE_RANK FIRST ORDER BY order_date) OVER (PARTITION BY customer_id) FROM orders;
SELECT * FROM ticker
  MATCH_RECOGNIZE (
    PARTITION BY symbol
    ORDER BY tstamp
    MEASURES STRT.tstamp AS start_tstamp,
             LAST(DOWN.tstamp) AS bottom_tstamp,
             LAST(UP.tstamp) AS end_tstamp,
             MATCH_NUMBER() AS match_num,
             CLASSIFIER() AS var_match
    ONE ROW PER MATCH
    AFTER MATCH SKIP TO LAST UP
    PATTERN (STRT DOWN+ UP+)
    DEFINE
      DOWN AS DOWN.price < PREV(DOWN.price),
      UP AS UP.price > PREV(UP.price)
  ) mr
ORDER BY mr.symbol, mr.start_tstamp;
SELECT * FROM events MATCH_RECOGNIZE (
  ORDER BY ts
  MEASURES FIRST(a.ts) AS s, COUNT(*) AS n
  ALL ROWS PER MATCH
  PATTERN (a b{2,} c?)
  DEFINE b AS b.val > a.val, c AS c.val < 0
);
SELECT order_id, amount, SUM(amount) OVER w AS s FROM orders WINDOW w AS (PARTITION BY region ORDER BY order_date);
