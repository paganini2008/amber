-- =====================================================================
-- Nightly ETL: staging -> fact tables, with CTEs, window functions,
-- MERGE upserts and data change delta tables
-- =====================================================================
SET AUTOCOMMIT OFF;
SET @run_date = CURRENT_DATE - INTERVAL '1' DAY;

CREATE LOCAL TEMPORARY TABLE IF NOT EXISTS stg_sales (
    src_id      VARCHAR(64),
    sold_at     TIMESTAMP,
    store_code  VARCHAR(10),
    sku         VARCHAR(40),
    qty         INT,
    amount      DECIMAL(12, 2)
) ON COMMIT PRESERVE ROWS;

INSERT INTO stg_sales
SELECT * FROM CSVREAD('/data/import/sales_' || FORMATDATETIME(@run_date, 'yyyyMMdd') || '.csv',
                      NULL, 'charset=UTF-8 fieldSeparator=;');

-- de-duplicate the staging rows, keeping the latest version of each source id
DELETE FROM stg_sales s
WHERE EXISTS (
    SELECT 1 FROM stg_sales d
    WHERE d.src_id = s.src_id AND d.sold_at > s.sold_at
);

MERGE INTO dim_store d
USING (SELECT DISTINCT store_code FROM stg_sales) s
ON d.store_code = s.store_code
WHEN NOT MATCHED THEN
    INSERT (store_code, created_at) VALUES (s.store_code, CURRENT_TIMESTAMP);

INSERT INTO fact_sales (sale_date, store_id, product_id, qty, amount, rank_in_store, running_amount)
WITH cleaned AS (
    SELECT CAST(sold_at AS DATE) AS sale_date,
           TRIM(UPPER(store_code)) AS store_code,
           sku,
           COALESCE(qty, 0) AS qty,
           COALESCE(amount, 0.00) AS amount
    FROM stg_sales
    WHERE sold_at >= @run_date AND sold_at < @run_date + INTERVAL '1' DAY
),
resolved AS (
    SELECT c.*, st.id AS store_id, p.id AS product_id
    FROM cleaned c
    JOIN dim_store st ON st.store_code = c.store_code
    LEFT JOIN dim_product p ON p.sku = c.sku
)
SELECT sale_date,
       store_id,
       product_id,
       SUM(qty),
       SUM(amount),
       RANK() OVER (PARTITION BY store_id ORDER BY SUM(amount) DESC),
       SUM(SUM(amount)) OVER (PARTITION BY store_id ORDER BY product_id
                              ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
FROM resolved
WHERE product_id IS NOT NULL
GROUP BY sale_date, store_id, product_id;

-- rows without a known product go to the reject table
INSERT INTO etl_reject (src_id, reason, payload)
SELECT src_id, 'UNKNOWN_SKU',
       JSON_OBJECT('sku': sku, 'store': store_code, 'qty': qty ABSENT ON NULL)
FROM stg_sales s
WHERE NOT EXISTS (SELECT 1 FROM dim_product p WHERE p.sku = s.sku);

-- daily aggregate with month-to-date and moving average
MERGE INTO agg_daily_store a
USING (
    SELECT sale_date, store_id,
           SUM(amount) AS day_amount,
           SUM(SUM(amount)) OVER (PARTITION BY store_id, YEAR(sale_date), MONTH(sale_date)
                                  ORDER BY sale_date) AS mtd_amount,
           AVG(SUM(amount)) OVER (PARTITION BY store_id ORDER BY sale_date
                                  RANGE BETWEEN INTERVAL '6' DAY PRECEDING AND CURRENT ROW) AS avg_7d
    FROM fact_sales
    WHERE sale_date >= DATE_TRUNC('MONTH', @run_date)
    GROUP BY sale_date, store_id
) s
ON a.sale_date = s.sale_date AND a.store_id = s.store_id
WHEN MATCHED THEN UPDATE SET day_amount = s.day_amount, mtd_amount = s.mtd_amount, avg_7d = s.avg_7d
WHEN NOT MATCHED THEN INSERT (sale_date, store_id, day_amount, mtd_amount, avg_7d)
    VALUES (s.sale_date, s.store_id, s.day_amount, s.mtd_amount, s.avg_7d);

-- top 3 products per store, written to a report table via a delta table
INSERT INTO report_top_products (store_id, product_id, amount, pos)
SELECT store_id, product_id, amount, pos FROM (
    SELECT store_id, product_id, SUM(amount) AS amount,
           ROW_NUMBER() OVER (PARTITION BY store_id ORDER BY SUM(amount) DESC NULLS LAST) AS pos
    FROM fact_sales
    WHERE sale_date = @run_date
    GROUP BY store_id, product_id
) ranked
WHERE pos <= 3;

SELECT COUNT(*) AS inserted_rows
FROM FINAL TABLE (
    INSERT INTO etl_log (run_date, step, finished_at)
    VALUES (@run_date, 'sales', CURRENT_TIMESTAMP)
);

SELECT store_id, sale_date, day_amount,
       LAG(day_amount) OVER w AS prev_day,
       day_amount - LAG(day_amount, 1, 0) OVER w AS delta
FROM agg_daily_store
WINDOW w AS (PARTITION BY store_id ORDER BY sale_date)
QUALIFY delta < 0
ORDER BY store_id, sale_date
FETCH FIRST 100 ROWS ONLY;

MERGE INTO etl_watermark KEY (job_name) VALUES ('sales', @run_date, CURRENT_TIMESTAMP);

TRUNCATE TABLE stg_sales;
COMMIT;
