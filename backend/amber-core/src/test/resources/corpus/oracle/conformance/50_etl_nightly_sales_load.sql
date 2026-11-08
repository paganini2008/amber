--------------------------------------------------------------------------------
-- Nightly sales load: staging -> warehouse (SQL*Plus driver script)
--------------------------------------------------------------------------------
SET ECHO OFF
SET FEEDBACK ON
SET SERVEROUTPUT ON SIZE UNLIMITED
SET VERIFY OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
DEFINE load_date = '&1'
SPOOL nightly_sales_&load_date..log

PROMPT === Step 1: prepare work tables ===
BEGIN
  EXECUTE IMMEDIATE 'DROP TABLE wrk_sales_clean PURGE';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -942 THEN
      RAISE;
    END IF;
END;
/

CREATE TABLE wrk_sales_clean NOLOGGING AS
SELECT s.txn_id,
       s.store_code,
       TRIM(UPPER(s.sku)) AS sku,
       TO_DATE(s.txn_date_str, 'YYYYMMDD') AS txn_date,
       NVL(s.qty, 0) AS qty,
       ROUND(s.unit_price * NVL(fx.rate, 1), 2) AS unit_price_eur
FROM   stg_sales s
LEFT   JOIN fx_rates fx
       ON fx.currency_code = s.currency_code
      AND fx.rate_date = TO_DATE('&load_date', 'YYYY-MM-DD')
WHERE  s.load_batch = (SELECT MAX(load_batch) FROM stg_sales)
AND    REGEXP_LIKE(s.txn_date_str, '^[0-9]{8}$');

CREATE INDEX wrk_sales_clean_ix ON wrk_sales_clean (store_code, sku) NOLOGGING;

BEGIN
  DBMS_STATS.GATHER_TABLE_STATS(ownname => USER, tabname => 'WRK_SALES_CLEAN', estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE);
END;
/

PROMPT === Step 2: reject bad rows ===
INSERT INTO etl_rejects (txn_id, reason, rejected_at)
SELECT txn_id, 'UNKNOWN_SKU', SYSTIMESTAMP
FROM   wrk_sales_clean w
WHERE  NOT EXISTS (SELECT 1 FROM dim_product p WHERE p.sku = w.sku);

DELETE FROM wrk_sales_clean w
WHERE  NOT EXISTS (SELECT 1 FROM dim_product p WHERE p.sku = w.sku);

PROMPT === Step 3: upsert daily fact ===
MERGE /*+ PARALLEL(f 4) */ INTO fact_sales_daily f
USING (
  WITH agg AS (
    SELECT st.store_key,
           p.product_key,
           w.txn_date,
           SUM(w.qty) AS qty,
           SUM(w.qty * w.unit_price_eur) AS revenue
    FROM   wrk_sales_clean w
    JOIN   dim_store st ON st.store_code = w.store_code
    JOIN   dim_product p ON p.sku = w.sku
    GROUP  BY st.store_key, p.product_key, w.txn_date
  )
  SELECT a.*, RANK() OVER (PARTITION BY a.store_key, a.txn_date ORDER BY a.revenue DESC) AS rev_rank
  FROM   agg a
) src
ON (f.store_key = src.store_key AND f.product_key = src.product_key AND f.sale_date = src.txn_date)
WHEN MATCHED THEN
  UPDATE SET f.qty = src.qty, f.revenue = src.revenue, f.rev_rank = src.rev_rank, f.updated_at = SYSTIMESTAMP
  WHERE f.qty <> src.qty OR f.revenue <> src.revenue
WHEN NOT MATCHED THEN
  INSERT (store_key, product_key, sale_date, qty, revenue, rev_rank, created_at)
  VALUES (src.store_key, src.product_key, src.txn_date, src.qty, src.revenue, src.rev_rank, SYSTIMESTAMP);

PROMPT === Step 4: refresh aggregates ===
DECLARE
  v_rows   PLS_INTEGER;
  v_start  TIMESTAMP := SYSTIMESTAMP;
BEGIN
  DBMS_MVIEW.REFRESH(list => 'MV_SALES_MONTHLY', method => 'F', atomic_refresh => FALSE);
  SELECT COUNT(*) INTO v_rows FROM fact_sales_daily WHERE sale_date = TO_DATE('&load_date', 'YYYY-MM-DD');
  INSERT INTO etl_run_log (job_name, run_date, rows_loaded, started_at, finished_at, status)
  VALUES ('NIGHTLY_SALES', TO_DATE('&load_date', 'YYYY-MM-DD'), v_rows, v_start, SYSTIMESTAMP, 'OK');
  COMMIT;
  DBMS_OUTPUT.PUT_LINE('Loaded ' || v_rows || ' rows');
EXCEPTION
  WHEN OTHERS THEN
    ROLLBACK;
    INSERT INTO etl_run_log (job_name, run_date, started_at, finished_at, status, error_text)
    VALUES ('NIGHTLY_SALES', TO_DATE('&load_date', 'YYYY-MM-DD'), v_start, SYSTIMESTAMP, 'FAILED', SUBSTR(SQLERRM, 1, 4000));
    COMMIT;
    RAISE;
END;
/

PROMPT === Step 5: report ===
COLUMN store_code FORMAT A10
COLUMN revenue FORMAT 999,999,990.00
SELECT st.store_code,
       SUM(f.revenue) AS revenue,
       SUM(f.revenue) - LAG(SUM(f.revenue)) OVER (PARTITION BY st.store_code ORDER BY f.sale_date) AS delta
FROM   fact_sales_daily f
JOIN   dim_store st ON st.store_key = f.store_key
WHERE  f.sale_date BETWEEN TO_DATE('&load_date', 'YYYY-MM-DD') - 1 AND TO_DATE('&load_date', 'YYYY-MM-DD')
GROUP  BY st.store_code, f.sale_date
ORDER  BY revenue DESC
FETCH  FIRST 20 ROWS ONLY;

PROMPT === Step 6: cleanup ===
DROP TABLE wrk_sales_clean PURGE;
TRUNCATE TABLE stg_sales;
SPOOL OFF
EXIT
