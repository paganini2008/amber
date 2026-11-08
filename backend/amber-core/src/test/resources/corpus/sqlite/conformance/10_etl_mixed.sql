-- Realistic nightly ETL job for a small SQLite reporting store
PRAGMA foreign_keys = ON;
BEGIN IMMEDIATE TRANSACTION;

CREATE TEMP TABLE IF NOT EXISTS stage_sale (
  sale_ref TEXT NOT NULL,
  store_code TEXT NOT NULL,
  sold_on TEXT NOT NULL,
  sku TEXT NOT NULL,
  qty INTEGER NOT NULL CHECK (qty > 0),
  unit_price REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS dim_store (
  store_id INTEGER PRIMARY KEY,
  store_code TEXT NOT NULL UNIQUE,
  first_seen TEXT NOT NULL DEFAULT (date('now'))
) STRICT;

CREATE TABLE IF NOT EXISTS fact_sale (
  sale_ref TEXT PRIMARY KEY,
  store_id INTEGER NOT NULL REFERENCES dim_store(store_id),
  sold_on TEXT NOT NULL,
  sku TEXT NOT NULL,
  qty INTEGER NOT NULL,
  revenue REAL GENERATED ALWAYS AS (qty * unit_price) STORED,
  unit_price REAL NOT NULL,
  loaded_at TEXT DEFAULT CURRENT_TIMESTAMP
) WITHOUT ROWID;

INSERT INTO dim_store (store_code)
SELECT DISTINCT store_code FROM stage_sale WHERE true
ON CONFLICT (store_code) DO NOTHING;

WITH dedup AS (
  SELECT s.*, row_number() OVER (PARTITION BY sale_ref ORDER BY sold_on DESC) AS rn
  FROM stage_sale AS s
)
INSERT INTO fact_sale (sale_ref, store_id, sold_on, sku, qty, unit_price)
SELECT d.sale_ref, ds.store_id, d.sold_on, d.sku, d.qty, d.unit_price
FROM dedup d JOIN dim_store ds ON ds.store_code = d.store_code
WHERE d.rn = 1
ON CONFLICT (sale_ref) DO UPDATE SET
  qty = excluded.qty,
  unit_price = excluded.unit_price,
  loaded_at = CURRENT_TIMESTAMP
WHERE excluded.qty <> fact_sale.qty OR excluded.unit_price <> fact_sale.unit_price;

CREATE TABLE IF NOT EXISTS daily_store_total (store_id INTEGER, sold_on TEXT, revenue REAL, running_revenue REAL, PRIMARY KEY (store_id, sold_on));

DELETE FROM daily_store_total WHERE sold_on >= date('now', '-7 days');

INSERT INTO daily_store_total (store_id, sold_on, revenue, running_revenue)
SELECT store_id, sold_on, day_rev,
       sum(day_rev) OVER (PARTITION BY store_id ORDER BY sold_on ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
FROM (SELECT store_id, sold_on, sum(revenue) AS day_rev FROM fact_sale WHERE sold_on >= date('now', '-7 days') GROUP BY store_id, sold_on);

UPDATE dim_store SET first_seen = f.min_day
FROM (SELECT store_id, min(sold_on) AS min_day FROM fact_sale GROUP BY store_id) AS f
WHERE dim_store.store_id = f.store_id AND f.min_day < dim_store.first_seen;

SAVEPOINT cleanup;
DELETE FROM stage_sale;
DROP TABLE IF EXISTS temp.stage_sale;
RELEASE cleanup;

COMMIT;
ANALYZE;
PRAGMA optimize;
