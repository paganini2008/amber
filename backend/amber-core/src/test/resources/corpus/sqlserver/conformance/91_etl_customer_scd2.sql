-- Customer dimension SCD type 2 load with cursor-based exception handling and dynamic SQL
SET ANSI_NULLS ON;
GO
SET QUOTED_IDENTIFIER ON;
GO
CREATE OR ALTER PROCEDURE dw.usp_load_dim_customer
    @source_schema SYSNAME = N'staging',
    @debug BIT = 0
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;

    DECLARE @sql NVARCHAR(MAX), @now DATETIME2(0) = SYSUTCDATETIME(), @cnt INT;

    CREATE TABLE #src (
        customer_code VARCHAR(20) NOT NULL PRIMARY KEY,
        display_name NVARCHAR(200) NOT NULL,
        segment VARCHAR(20) NULL,
        city NVARCHAR(100) NULL,
        attr_hash AS CAST(HASHBYTES('MD5', CONCAT(display_name, N'|', segment, N'|', city)) AS BINARY(16)) PERSISTED
    );

    SET @sql = N'INSERT INTO #src (customer_code, display_name, segment, city)
                 SELECT customer_code, display_name, segment, city
                 FROM ' + QUOTENAME(@source_schema) + N'.CustomerExtract
                 WHERE extract_date = @d;';

    IF @debug = 1 PRINT @sql;

    EXEC sys.sp_executesql @sql, N'@d DATE', @d = @now;

    SELECT @cnt = COUNT(*) FROM #src;
    IF @cnt = 0
    BEGIN
        RAISERROR (N'No rows in extract for %s', 10, 1, @source_schema) WITH NOWAIT;
        RETURN 1;
    END;

    BEGIN TRY
        BEGIN TRAN;

        -- expire changed rows
        UPDATE d
           SET d.valid_to = @now, d.is_current = 0
          FROM dw.DimCustomer AS d
          JOIN #src AS s ON s.customer_code = d.customer_code
         WHERE d.is_current = 1
           AND d.attr_hash <> s.attr_hash;

        -- insert new versions and brand-new customers
        INSERT INTO dw.DimCustomer (customer_code, display_name, segment, city, attr_hash, valid_from, valid_to, is_current)
        SELECT s.customer_code, s.display_name, s.segment, s.city, s.attr_hash, @now, '9999-12-31', 1
        FROM #src AS s
        LEFT JOIN dw.DimCustomer AS d ON d.customer_code = s.customer_code AND d.is_current = 1
        WHERE d.customer_key IS NULL;

        COMMIT;
    END TRY
    BEGIN CATCH
        IF XACT_STATE() <> 0 ROLLBACK;
        DECLARE @err NVARCHAR(2048) = ERROR_MESSAGE();
        RAISERROR (@err, 16, 1);
        RETURN -1;
    END CATCH;

    -- per-segment report with cursor
    DECLARE @segment VARCHAR(20), @seg_count INT;
    DECLARE seg_cur CURSOR LOCAL FAST_FORWARD FOR
        SELECT segment, COUNT(*) FROM dw.DimCustomer WHERE is_current = 1 GROUP BY segment ORDER BY segment;
    OPEN seg_cur;
    FETCH NEXT FROM seg_cur INTO @segment, @seg_count;
    WHILE @@FETCH_STATUS = 0
    BEGIN
        PRINT CONCAT(N'segment ', ISNULL(@segment, N'(none)'), N': ', @seg_count);
        FETCH NEXT FROM seg_cur INTO @segment, @seg_count;
    END;
    CLOSE seg_cur;
    DEALLOCATE seg_cur;

    -- pivot of segments by city for the report table
    TRUNCATE TABLE rpt.CustomerSegmentByCity;
    INSERT INTO rpt.CustomerSegmentByCity (city, retail, wholesale, online)
    SELECT city, ISNULL([RETAIL], 0), ISNULL([WHOLESALE], 0), ISNULL([ONLINE], 0)
    FROM (SELECT city, segment, customer_key FROM dw.DimCustomer WHERE is_current = 1) AS s
    PIVOT (COUNT(customer_key) FOR segment IN ([RETAIL], [WHOLESALE], [ONLINE])) AS p;

    -- latest change per customer via OUTER APPLY
    SELECT TOP (50) c.customer_code, h.prev_name, c.display_name
    FROM dw.DimCustomer AS c
    OUTER APPLY (SELECT TOP (1) h.display_name AS prev_name
                 FROM dw.DimCustomer AS h
                 WHERE h.customer_code = c.customer_code AND h.is_current = 0
                 ORDER BY h.valid_to DESC) AS h
    WHERE c.is_current = 1 AND h.prev_name IS NOT NULL
    ORDER BY c.valid_from DESC;

    DROP TABLE #src;
    RETURN 0;
END;
GO
EXEC dw.usp_load_dim_customer @debug = 1;
GO
-- export JSON snapshot for API cache
DECLARE @payload NVARCHAR(MAX) = (
    SELECT customer_code AS code, display_name AS [name], segment, city AS [address.city]
    FROM dw.DimCustomer WHERE is_current = 1
    FOR JSON PATH, ROOT('customers')
);
INSERT INTO api.Snapshot (snapshot_name, body, created_at) VALUES (N'customers', @payload, SYSUTCDATETIME());
GO
