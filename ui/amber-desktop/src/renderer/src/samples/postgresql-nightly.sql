-- Nightly rollup for a fictional bookshop (sample script, PostgreSQL)
-- Stages raw orders, enriches them, and refreshes reporting tables.

CREATE SCHEMA IF NOT EXISTS report;

CREATE TEMP TABLE tmp_orders AS
SELECT o.order_id, o.customer_id, o.placed_at::date AS order_day, o.total_amount
FROM shop.orders o
WHERE o.placed_at >= current_date - INTERVAL '1 day'
  AND o.status <> 'cancelled';

CREATE INDEX idx_tmp_orders_customer ON tmp_orders (customer_id);

CREATE TEMP TABLE tmp_customer_tier (
  customer_id bigint PRIMARY KEY,
  tier text NOT NULL
);

INSERT INTO tmp_customer_tier (customer_id, tier)
WITH spend AS (
  SELECT customer_id, SUM(total_amount) AS lifetime_spend
  FROM shop.orders
  GROUP BY customer_id
), ranked AS (
  SELECT customer_id,
         CASE WHEN lifetime_spend > 1000 THEN 'gold'
              WHEN lifetime_spend > 200 THEN 'silver'
              ELSE 'bronze' END AS tier
  FROM spend
)
SELECT customer_id, tier FROM ranked;

UPDATE tmp_orders t
SET total_amount = t.total_amount * 0.95
FROM tmp_customer_tier c
WHERE c.customer_id = t.customer_id
  AND c.tier = 'gold';

DELETE FROM report.daily_revenue
WHERE revenue_day = current_date - 1;

INSERT INTO report.daily_revenue (revenue_day, tier, orders, revenue)
SELECT t.order_day, c.tier, COUNT(*), SUM(t.total_amount)
FROM tmp_orders t
JOIN tmp_customer_tier c ON c.customer_id = t.customer_id
GROUP BY t.order_day, c.tier;

MERGE INTO report.customer_snapshot s
USING tmp_customer_tier c ON s.customer_id = c.customer_id
WHEN MATCHED THEN UPDATE SET tier = c.tier, refreshed_at = now()
WHEN NOT MATCHED THEN INSERT (customer_id, tier, refreshed_at) VALUES (c.customer_id, c.tier, now());

CREATE OR REPLACE VIEW report.v_top_customers AS
SELECT s.customer_id, s.tier, a.city
FROM report.customer_snapshot s
LEFT JOIN shop.addresses a ON a.customer_id = s.customer_id
WHERE s.tier = 'gold'
  AND a.country = 'NZ';

SELECT tier, COUNT(*) FROM report.customer_snapshot WHERE tier LIKE '%old' GROUP BY tier;

TRUNCATE TABLE tmp_customer_tier;
DROP TABLE tmp_orders;
DROP TABLE tmp_customer_tier;
