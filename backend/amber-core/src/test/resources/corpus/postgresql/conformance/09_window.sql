-- Window functions
SELECT order_id, row_number() OVER (ORDER BY order_date) FROM sales.orders;
SELECT order_id, rank() OVER (PARTITION BY customer_id ORDER BY amount DESC), dense_rank() OVER (PARTITION BY customer_id ORDER BY amount DESC) FROM sales.orders;
SELECT order_id, sum(amount) OVER (PARTITION BY customer_id ORDER BY order_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM sales.orders;
SELECT avg(amount) OVER (ORDER BY order_date ROWS BETWEEN 3 PRECEDING AND 3 FOLLOWING) FROM sales.orders;
SELECT sum(amount) OVER (ORDER BY order_date RANGE BETWEEN INTERVAL '7 days' PRECEDING AND CURRENT ROW) FROM sales.orders;
SELECT sum(amount) OVER (ORDER BY seq GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING EXCLUDE CURRENT ROW) FROM sales.orders;
SELECT sum(amount) OVER (ORDER BY seq ROWS UNBOUNDED PRECEDING EXCLUDE TIES) FROM sales.orders;
SELECT sum(amount) OVER (ORDER BY seq ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING EXCLUDE GROUP) FROM sales.orders;
SELECT sum(amount) OVER (ORDER BY seq RANGE CURRENT ROW EXCLUDE NO OTHERS) FROM sales.orders;
SELECT lag(amount) OVER w, lead(amount, 2, 0) OVER w FROM sales.orders WINDOW w AS (PARTITION BY customer_id ORDER BY order_date);
SELECT first_value(amount) OVER w, last_value(amount) OVER w, nth_value(amount, 2) OVER w FROM sales.orders WINDOW w AS (ORDER BY order_date ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING);
SELECT ntile(4) OVER (ORDER BY amount), percent_rank() OVER (ORDER BY amount), cume_dist() OVER (ORDER BY amount) FROM sales.orders;
SELECT count(*) FILTER (WHERE amount > 10) OVER (PARTITION BY customer_id) FROM sales.orders;
SELECT sum(amount) OVER (w ORDER BY order_date) FROM sales.orders WINDOW w AS (PARTITION BY customer_id);
SELECT * FROM (SELECT o.*, row_number() OVER (PARTITION BY customer_id ORDER BY order_date DESC) rn FROM sales.orders o) t WHERE rn = 1;
SELECT sum(sum(amount)) OVER (ORDER BY region) FROM sales.orders GROUP BY region;
SELECT order_id, sum(amount) OVER () AS grand_total FROM sales.orders;
SELECT a, b FROM t WINDOW w1 AS (PARTITION BY a), w2 AS (w1 ORDER BY b);
