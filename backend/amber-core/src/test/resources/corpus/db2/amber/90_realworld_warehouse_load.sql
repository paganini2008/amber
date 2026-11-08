-- =====================================================================
-- Nightly warehouse load: staging -> dimensions -> facts -> aggregates
-- Run with: db2 -tvf 90_realworld_warehouse_load.sql
-- =====================================================================
CONNECT TO dwh;
SET SCHEMA dwh;
SET CURRENT DEGREE = 'ANY';
SET CURRENT QUERY OPTIMIZATION = 7;

-- Working tables ----------------------------------------------------------
DECLARE GLOBAL TEMPORARY TABLE session.load_window (
    batch_id     INTEGER NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end   TIMESTAMP NOT NULL
) ON COMMIT PRESERVE ROWS NOT LOGGED WITH REPLACE;

DECLARE GLOBAL TEMPORARY TABLE session.stg_orders_clean AS (
    SELECT o.order_id, o.customer_id, o.order_ts, o.amount, o.currency, o.channel
    FROM stg.orders o
) WITH NO DATA ON COMMIT PRESERVE ROWS NOT LOGGED WITH REPLACE;

DECLARE GLOBAL TEMPORARY TABLE session.rejects (
    source_table VARCHAR(128), source_key VARCHAR(64), reason VARCHAR(400)
) ON COMMIT PRESERVE ROWS NOT LOGGED WITH REPLACE;

INSERT INTO session.load_window (batch_id, window_start, window_end)
SELECT COALESCE(MAX(batch_id), 0) + 1,
       COALESCE(MAX(window_end), TIMESTAMP '2000-01-01-00.00.00'),
       CURRENT TIMESTAMP - 5 MINUTES
FROM etl.batch_log
WHERE status = 'DONE';

INSERT INTO etl.batch_log (batch_id, window_start, window_end, status, started_ts)
SELECT batch_id, window_start, window_end, 'RUNNING', CURRENT TIMESTAMP FROM session.load_window;

-- Cleanse staging ---------------------------------------------------------
INSERT INTO session.rejects (source_table, source_key, reason)
SELECT 'STG.ORDERS', CHAR(o.order_id),
       CASE
           WHEN o.customer_id IS NULL THEN 'missing customer'
           WHEN o.amount < 0 THEN 'negative amount'
           WHEN o.currency NOT IN (SELECT currency_code FROM dwh.dim_currency) THEN 'unknown currency ' || COALESCE(o.currency, '?')
           ELSE 'out of window'
       END
FROM stg.orders o, session.load_window w
WHERE o.customer_id IS NULL
   OR o.amount < 0
   OR o.currency NOT IN (SELECT currency_code FROM dwh.dim_currency)
   OR o.order_ts NOT BETWEEN w.window_start AND w.window_end;

INSERT INTO session.stg_orders_clean
WITH ranked AS (
    SELECT o.*,
           ROW_NUMBER() OVER (PARTITION BY o.order_id ORDER BY o.load_ts DESC) AS rn
    FROM stg.orders o, session.load_window w
    WHERE o.order_ts BETWEEN w.window_start AND w.window_end
      AND o.customer_id IS NOT NULL
      AND o.amount >= 0
)
SELECT order_id, customer_id, order_ts, amount, UPPER(TRIM(currency)), COALESCE(NULLIF(TRIM(channel), ''), 'UNKNOWN')
FROM ranked
WHERE rn = 1;

-- Dimensions: SCD type 2 for customers --------------------------------------
UPDATE dwh.dim_customer d
SET valid_to = CURRENT DATE - 1 DAY,
    is_current = 'N'
WHERE d.is_current = 'Y'
  AND EXISTS (
      SELECT 1 FROM stg.customers s
      WHERE s.customer_id = d.customer_id
        AND (s.name <> d.name OR s.segment <> d.segment OR s.country IS DISTINCT FROM d.country)
  );

INSERT INTO dwh.dim_customer (customer_sk, customer_id, name, segment, country, valid_from, valid_to, is_current)
SELECT NEXT VALUE FOR dwh.seq_customer_sk, s.customer_id, s.name, s.segment, s.country,
       CURRENT DATE, DATE '9999-12-31', 'Y'
FROM stg.customers s
WHERE NOT EXISTS (
    SELECT 1 FROM dwh.dim_customer d WHERE d.customer_id = s.customer_id AND d.is_current = 'Y'
);

MERGE INTO dwh.dim_product AS t
USING (
    SELECT p.product_id, p.name, p.category, p.list_price,
           LAG(p.list_price) OVER (PARTITION BY p.product_id ORDER BY p.load_ts) AS prev_price
    FROM stg.products p
) AS s
ON t.product_id = s.product_id
WHEN MATCHED AND s.list_price IS NULL THEN
    SIGNAL SQLSTATE '75010' SET MESSAGE_TEXT = 'Product without price'
WHEN MATCHED AND (t.name <> s.name OR t.category <> s.category OR t.list_price <> s.list_price) THEN
    UPDATE SET t.name = s.name, t.category = s.category, t.list_price = s.list_price, t.updated_ts = CURRENT TIMESTAMP
WHEN NOT MATCHED THEN
    INSERT (product_id, name, category, list_price, created_ts)
    VALUES (s.product_id, s.name, s.category, s.list_price, CURRENT TIMESTAMP)
ELSE IGNORE;

-- Facts ---------------------------------------------------------------------
INSERT INTO dwh.fact_orders (order_id, customer_sk, date_sk, amount_eur, channel, batch_id)
SELECT c.order_id,
       d.customer_sk,
       INTEGER(VARCHAR_FORMAT(c.order_ts, 'YYYYMMDD')),
       DECIMAL(c.amount * COALESCE(fx.rate, 1), 15, 2),
       c.channel,
       w.batch_id
FROM session.stg_orders_clean c
JOIN dwh.dim_customer d ON d.customer_id = c.customer_id AND d.is_current = 'Y'
LEFT OUTER JOIN dwh.fx_rates fx ON fx.currency_code = c.currency AND fx.rate_date = DATE(c.order_ts)
CROSS JOIN session.load_window w;

DELETE FROM dwh.fact_orders f
WHERE f.batch_id < (SELECT batch_id - 400 FROM session.load_window)
  AND f.date_sk < INTEGER(VARCHAR_FORMAT(CURRENT DATE - 3 YEARS, 'YYYYMMDD'));

-- Aggregates ------------------------------------------------------------------
REFRESH TABLE dwh.mqt_daily_sales;

WITH daily AS (
    SELECT f.date_sk, d.segment, SUM(f.amount_eur) AS revenue, COUNT(*) AS orders
    FROM dwh.fact_orders f
    JOIN dwh.dim_customer d ON d.customer_sk = f.customer_sk
    GROUP BY f.date_sk, d.segment
),
moving AS (
    SELECT date_sk, segment, revenue, orders,
           AVG(revenue) OVER (PARTITION BY segment ORDER BY date_sk ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS revenue_7d,
           SUM(orders) OVER (PARTITION BY segment ORDER BY date_sk ROWS UNBOUNDED PRECEDING) AS orders_cum,
           RANK() OVER (PARTITION BY date_sk ORDER BY revenue DESC) AS segment_rank
    FROM daily
)
SELECT m.*, DECIMAL(revenue * 100.0 / NULLIF(SUM(revenue) OVER (PARTITION BY date_sk), 0), 5, 2) AS pct_of_day
FROM moving m
WHERE date_sk >= INTEGER(VARCHAR_FORMAT(CURRENT DATE - 30 DAYS, 'YYYYMMDD'))
ORDER BY date_sk DESC, segment_rank
FETCH FIRST 500 ROWS ONLY
WITH UR;

INSERT INTO dwh.agg_segment_month (month_start, segment, revenue, customers, top_products)
SELECT DATE(VARCHAR_FORMAT(o.order_ts, 'YYYY-MM') || '-01'),
       d.segment,
       SUM(f.amount_eur),
       COUNT(DISTINCT f.customer_sk),
       SUBSTR(LISTAGG(DISTINCT p.name, ', ') WITHIN GROUP (ORDER BY p.name), 1, 1000)
FROM dwh.fact_orders f
JOIN dwh.dim_customer d ON d.customer_sk = f.customer_sk
JOIN session.stg_orders_clean o ON o.order_id = f.order_id
LEFT JOIN dwh.fact_order_lines l ON l.order_id = f.order_id
LEFT JOIN dwh.dim_product p ON p.product_id = l.product_id
GROUP BY GROUPING SETS ((DATE(VARCHAR_FORMAT(o.order_ts, 'YYYY-MM') || '-01'), d.segment),
                        (DATE(VARCHAR_FORMAT(o.order_ts, 'YYYY-MM') || '-01')));

SELECT region, country, SUM(amount_eur) AS revenue, GROUPING(region) AS g_region, GROUPING(country) AS g_country
FROM dwh.fact_orders f JOIN dwh.dim_customer d ON d.customer_sk = f.customer_sk
GROUP BY ROLLUP (d.region, d.country)
HAVING SUM(amount_eur) > 0
ORDER BY region NULLS LAST, country NULLS LAST;

-- Close the batch -------------------------------------------------------------
UPDATE etl.batch_log b
SET status = 'DONE',
    finished_ts = CURRENT TIMESTAMP,
    rows_loaded = (SELECT COUNT(*) FROM session.stg_orders_clean),
    rows_rejected = (SELECT COUNT(*) FROM session.rejects)
WHERE b.batch_id = (SELECT batch_id FROM session.load_window);

INSERT INTO etl.reject_log (batch_id, source_table, source_key, reason, logged_ts)
SELECT w.batch_id, r.source_table, r.source_key, r.reason, CURRENT TIMESTAMP
FROM session.rejects r, session.load_window w;

SELECT batch_id, rows_loaded, rows_rejected, TIMESTAMPDIFF(2, CHAR(finished_ts - started_ts)) AS seconds
FROM FINAL TABLE (
    UPDATE etl.batch_log SET verified = 'Y' WHERE status = 'DONE' AND verified = 'N'
);

TRUNCATE TABLE stg.orders IMMEDIATE;
TRUNCATE TABLE stg.customers REUSE STORAGE IMMEDIATE;
COMMIT;
CALL SYSPROC.ADMIN_CMD('RUNSTATS ON TABLE dwh.fact_orders WITH DISTRIBUTION AND SAMPLED DETAILED INDEXES ALL');
CONNECT RESET;
