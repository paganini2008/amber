/* =========================================================================
   Nightly sales load: staging -> warehouse
   Run with sqlcmd -v LoadDate="2024-06-30"
   ========================================================================= */
:setvar LoadDate "2024-06-30"
:on error exit
SET NOCOUNT ON;
SET XACT_ABORT ON;
GO
USE warehouse;
GO
IF OBJECT_ID(N'etl.usp_load_daily_sales', N'P') IS NOT NULL
    DROP PROCEDURE etl.usp_load_daily_sales;
GO
CREATE PROCEDURE etl.usp_load_daily_sales
    @load_date DATE,
    @batch_id INT OUTPUT
AS
BEGIN
    SET NOCOUNT ON;
    DECLARE @rows_inserted INT = 0, @rows_updated INT = 0, @started DATETIME2(3) = SYSUTCDATETIME();
    DECLARE @merge_log TABLE (action_name NVARCHAR(10), sale_key BIGINT);

    INSERT INTO etl.BatchLog (job_name, load_date, started_at, status)
    VALUES (N'daily_sales', @load_date, @started, 'RUNNING');
    SET @batch_id = SCOPE_IDENTITY();

    BEGIN TRY
        IF OBJECT_ID('tempdb..#clean_sales') IS NOT NULL DROP TABLE #clean_sales;

        CREATE TABLE #clean_sales (
            source_id     BIGINT        NOT NULL PRIMARY KEY,
            store_code    VARCHAR(10)   NOT NULL,
            product_sku   VARCHAR(30)   NOT NULL,
            sold_at       DATETIME2(0)  NOT NULL,
            qty           INT           NOT NULL,
            unit_price    DECIMAL(12,2) NOT NULL,
            row_hash      VARBINARY(32) NULL
        );

        ;WITH ranked AS (
            SELECT s.*,
                   ROW_NUMBER() OVER (PARTITION BY s.source_id ORDER BY s.received_at DESC) AS rn
            FROM staging.RawSales AS s
            WHERE s.sold_at >= @load_date AND s.sold_at < DATEADD(DAY, 1, @load_date)
        )
        INSERT INTO #clean_sales (source_id, store_code, product_sku, sold_at, qty, unit_price, row_hash)
        SELECT r.source_id,
               UPPER(LTRIM(RTRIM(r.store_code))),
               r.product_sku,
               r.sold_at,
               TRY_CAST(r.qty AS INT),
               ISNULL(TRY_CONVERT(DECIMAL(12, 2), r.unit_price), 0),
               HASHBYTES('SHA2_256', CONCAT(r.store_code, '|', r.product_sku, '|', r.qty, '|', r.unit_price))
        FROM ranked AS r
        WHERE r.rn = 1 AND TRY_CAST(r.qty AS INT) IS NOT NULL;

        CREATE NONCLUSTERED INDEX ix_clean_sku ON #clean_sales (product_sku) INCLUDE (qty, unit_price);

        -- reject rows with unknown products
        INSERT INTO etl.RejectedRows (batch_id, source_id, reason)
        SELECT @batch_id, c.source_id, N'unknown sku ' + c.product_sku
        FROM #clean_sales AS c
        WHERE NOT EXISTS (SELECT 1 FROM dw.DimProduct AS p WHERE p.sku = c.product_sku AND p.is_current = 1);

        DELETE c
        FROM #clean_sales AS c
        LEFT JOIN dw.DimProduct AS p ON p.sku = c.product_sku AND p.is_current = 1
        WHERE p.product_key IS NULL;

        BEGIN TRANSACTION;

        MERGE dw.FactSales WITH (HOLDLOCK) AS tgt
        USING (
            SELECT c.source_id, st.store_key, p.product_key,
                   CONVERT(INT, FORMAT(c.sold_at, 'yyyyMMdd')) AS date_key,
                   c.qty, c.unit_price, c.qty * c.unit_price AS amount, c.row_hash
            FROM #clean_sales AS c
            INNER JOIN dw.DimStore AS st ON st.store_code = c.store_code
            INNER JOIN dw.DimProduct AS p ON p.sku = c.product_sku AND p.is_current = 1
        ) AS src
        ON tgt.source_id = src.source_id
        WHEN MATCHED AND tgt.row_hash <> src.row_hash THEN
            UPDATE SET tgt.qty = src.qty, tgt.unit_price = src.unit_price, tgt.amount = src.amount,
                       tgt.row_hash = src.row_hash, tgt.batch_id = @batch_id
        WHEN NOT MATCHED BY TARGET THEN
            INSERT (source_id, store_key, product_key, date_key, qty, unit_price, amount, row_hash, batch_id)
            VALUES (src.source_id, src.store_key, src.product_key, src.date_key, src.qty, src.unit_price, src.amount, src.row_hash, @batch_id)
        OUTPUT $action, inserted.sale_key INTO @merge_log (action_name, sale_key);

        SELECT @rows_inserted = SUM(CASE WHEN action_name = N'INSERT' THEN 1 ELSE 0 END),
               @rows_updated  = SUM(CASE WHEN action_name = N'UPDATE' THEN 1 ELSE 0 END)
        FROM @merge_log;

        UPDATE b
        SET b.status = 'OK', b.finished_at = SYSUTCDATETIME(),
            b.rows_inserted = ISNULL(@rows_inserted, 0), b.rows_updated = ISNULL(@rows_updated, 0)
        FROM etl.BatchLog AS b
        WHERE b.batch_id = @batch_id;

        COMMIT TRANSACTION;
    END TRY
    BEGIN CATCH
        IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;

        UPDATE etl.BatchLog
        SET status = 'FAILED', finished_at = SYSUTCDATETIME(),
            error_text = CONCAT(ERROR_NUMBER(), N': ', ERROR_MESSAGE(), N' (line ', ERROR_LINE(), N')')
        WHERE batch_id = @batch_id;

        THROW;
    END CATCH;

    DROP TABLE IF EXISTS #clean_sales;
END;
GO

-- refresh daily aggregate
CREATE OR ALTER PROCEDURE etl.usp_refresh_store_daily @load_date DATE
AS
BEGIN
    SET NOCOUNT ON;
    DELETE FROM dw.AggStoreDaily WHERE date_key = CONVERT(INT, FORMAT(@load_date, 'yyyyMMdd'));

    INSERT INTO dw.AggStoreDaily (date_key, store_key, total_qty, total_amount, avg_ticket, rank_in_region)
    SELECT f.date_key, f.store_key, SUM(f.qty), SUM(f.amount),
           AVG(f.amount),
           RANK() OVER (PARTITION BY MAX(s.region_code) ORDER BY SUM(f.amount) DESC)
    FROM dw.FactSales AS f
    JOIN dw.DimStore AS s ON s.store_key = f.store_key
    WHERE f.date_key = CONVERT(INT, FORMAT(@load_date, 'yyyyMMdd'))
    GROUP BY f.date_key, f.store_key;
END
GO

DECLARE @bid INT;
EXEC etl.usp_load_daily_sales @load_date = '$(LoadDate)', @batch_id = @bid OUTPUT;
EXEC etl.usp_refresh_store_daily '$(LoadDate)';
SELECT batch_id, status, rows_inserted, rows_updated FROM etl.BatchLog WHERE batch_id = @bid;
GO

-- housekeeping
DELETE TOP (10000) FROM staging.RawSales WHERE received_at < DATEADD(DAY, -14, SYSUTCDATETIME());
UPDATE STATISTICS dw.FactSales;
GO
