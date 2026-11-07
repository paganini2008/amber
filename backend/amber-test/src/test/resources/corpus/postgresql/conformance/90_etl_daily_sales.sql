-- Long realistic ETL: daily sales load (psql script)
\set ON_ERROR_STOP on
\set run_date '2024-06-30'

BEGIN;

SET LOCAL search_path = etl, sales, public;
SET LOCAL work_mem = '256MB';

-- 1. Stage raw data into a temp table
CREATE TEMP TABLE tmp_raw_sales (
    src_order_ref  text,
    customer_code  text,
    sku            text,
    qty            integer,
    unit_price     numeric(12,4),
    sold_at        timestamptz,
    payload        jsonb
) ON COMMIT DROP;

\copy tmp_raw_sales FROM 'exports/sales_20240630.csv' WITH (FORMAT csv, HEADER true)

INSERT INTO etl.load_audit (run_date, step, row_count, started_at)
SELECT :'run_date'::date, 'stage', count(*), clock_timestamp() FROM tmp_raw_sales;

-- 2. Clean and deduplicate
CREATE TEMP TABLE tmp_clean ON COMMIT DROP AS
WITH ranked AS (
    SELECT r.*,
           row_number() OVER (PARTITION BY src_order_ref, sku ORDER BY sold_at DESC) AS rn
    FROM tmp_raw_sales r
    WHERE qty > 0
      AND unit_price IS NOT NULL
      AND sold_at >= :'run_date'::date
      AND sold_at <  :'run_date'::date + INTERVAL '1 day'
)
SELECT src_order_ref,
       upper(trim(customer_code)) AS customer_code,
       sku,
       qty,
       unit_price,
       qty * unit_price AS line_total,
       sold_at,
       coalesce(payload ->> 'channel', 'unknown') AS channel,
       (payload -> 'promo' ->> 'code') AS promo_code
FROM ranked
WHERE rn = 1;

CREATE INDEX ON tmp_clean (customer_code);
ANALYZE tmp_clean;

-- 3. Upsert customers
INSERT INTO sales.customer AS c (customer_code, name, first_seen)
SELECT DISTINCT ON (t.customer_code) t.customer_code, 'Unknown ' || t.customer_code, t.sold_at
FROM tmp_clean t
ORDER BY t.customer_code, t.sold_at
ON CONFLICT (customer_code) DO UPDATE
   SET first_seen = least(c.first_seen, EXCLUDED.first_seen)
   WHERE c.first_seen > EXCLUDED.first_seen;

-- 4. Merge order lines
MERGE INTO sales.order_line AS tgt
USING (
    SELECT t.src_order_ref, t.sku, t.qty, t.unit_price, t.line_total, c.customer_id, t.channel
    FROM tmp_clean t
    JOIN sales.customer c ON c.customer_code = t.customer_code
) AS src
ON tgt.src_order_ref = src.src_order_ref AND tgt.sku = src.sku
WHEN MATCHED AND (tgt.qty, tgt.unit_price) IS DISTINCT FROM (src.qty, src.unit_price) THEN
    UPDATE SET qty = src.qty, unit_price = src.unit_price, line_total = src.line_total, updated_at = now()
WHEN NOT MATCHED THEN
    INSERT (src_order_ref, sku, qty, unit_price, line_total, customer_id, channel, load_date)
    VALUES (src.src_order_ref, src.sku, src.qty, src.unit_price, src.line_total, src.customer_id, src.channel, :'run_date');

-- 5. Daily aggregates with window functions
DELETE FROM mart.daily_customer_sales WHERE sales_date = :'run_date'::date;

INSERT INTO mart.daily_customer_sales (sales_date, customer_id, total, order_count, rank_in_region, running_month_total)
SELECT d.sales_date, d.customer_id, d.total, d.order_count,
       rank() OVER (PARTITION BY d.region_code ORDER BY d.total DESC),
       sum(d.total) OVER (PARTITION BY d.customer_id, date_trunc('month', d.sales_date)
                          ORDER BY d.sales_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
FROM (
    SELECT ol.load_date AS sales_date, ol.customer_id, c.region_code,
           sum(ol.line_total) AS total,
           count(DISTINCT ol.src_order_ref) AS order_count
    FROM sales.order_line ol
    JOIN sales.customer c USING (customer_id)
    WHERE ol.load_date = :'run_date'::date
    GROUP BY ol.load_date, ol.customer_id, c.region_code
) d;

-- 6. Region rollup
INSERT INTO mart.region_rollup (sales_date, region_code, channel, total, is_subtotal)
SELECT :'run_date'::date, c.region_code, ol.channel, sum(ol.line_total), GROUPING(c.region_code, ol.channel) > 0
FROM sales.order_line ol
JOIN sales.customer c ON c.customer_id = ol.customer_id
WHERE ol.load_date = :'run_date'::date
GROUP BY ROLLUP (c.region_code, ol.channel);

-- 7. Promo usage report via LATERAL
CREATE TEMP TABLE tmp_promo ON COMMIT DROP AS
SELECT p.promo_code, x.top_sku, x.qty
FROM (SELECT DISTINCT promo_code FROM tmp_clean WHERE promo_code IS NOT NULL) p
CROSS JOIN LATERAL (
    SELECT sku AS top_sku, sum(qty) AS qty
    FROM tmp_clean t
    WHERE t.promo_code = p.promo_code
    GROUP BY sku
    ORDER BY sum(qty) DESC
    LIMIT 1
) x;

UPDATE mart.promo p
SET last_top_sku = t.top_sku, last_used = :'run_date'::date
FROM tmp_promo t
WHERE p.promo_code = t.promo_code;

-- 8. Housekeeping via dynamic SQL
DO $$
DECLARE
    part_name text := format('order_line_%s', to_char(current_date + 31, 'YYYYMM'));
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = part_name) THEN
        EXECUTE format(
            'CREATE TABLE sales.%I PARTITION OF sales.order_line FOR VALUES FROM (%L) TO (%L)',
            part_name,
            date_trunc('month', current_date + 31)::date,
            (date_trunc('month', current_date + 31) + interval '1 month')::date);
    END IF;
EXCEPTION WHEN duplicate_table THEN
    RAISE NOTICE 'partition % already exists', part_name;
END
$$;

UPDATE etl.load_audit
SET finished_at = clock_timestamp(),
    row_count = (SELECT count(*) FROM tmp_clean)
WHERE run_date = :'run_date'::date AND step = 'stage';

COMMIT;

REFRESH MATERIALIZED VIEW CONCURRENTLY mart.mv_customer_lifetime;
VACUUM (ANALYZE) sales.order_line;
