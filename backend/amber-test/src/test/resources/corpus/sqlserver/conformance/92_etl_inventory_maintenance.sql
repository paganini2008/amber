-- Inventory reconciliation and maintenance job, deployed via sqlcmd
:setvar TargetDb "inventory_db"
USE [$(TargetDb)];
GO
IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = N'recon')
    EXEC (N'CREATE SCHEMA recon AUTHORIZATION dbo;');
GO
IF OBJECT_ID(N'recon.StockSnapshot', N'U') IS NULL
BEGIN
    CREATE TABLE recon.StockSnapshot (
        snapshot_id   INT IDENTITY(1,1) NOT NULL CONSTRAINT pk_stock_snapshot PRIMARY KEY,
        warehouse_id  SMALLINT NOT NULL,
        item_id       INT NOT NULL,
        qty_system    INT NOT NULL,
        qty_counted   INT NULL,
        diff          AS (qty_counted - qty_system),
        taken_at      DATETIME2(0) NOT NULL CONSTRAINT df_stock_taken DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT uq_stock_snapshot UNIQUE (warehouse_id, item_id, taken_at)
    );
END;
GO
CREATE OR ALTER FUNCTION recon.fn_item_movements (@item_id INT, @since DATETIME2(0))
RETURNS TABLE
AS
RETURN
    SELECT m.warehouse_id, SUM(CASE m.direction WHEN 'IN' THEN m.qty ELSE -m.qty END) AS net_qty
    FROM inv.Movements AS m
    WHERE m.item_id = @item_id AND m.moved_at >= @since
    GROUP BY m.warehouse_id;
GO
CREATE OR ALTER TRIGGER inv.trg_movements_guard ON inv.Movements
AFTER INSERT
AS
BEGIN
    SET NOCOUNT ON;
    IF EXISTS (SELECT 1 FROM inserted WHERE qty <= 0)
    BEGIN
        ROLLBACK TRANSACTION;
        THROW 51000, N'Movement quantity must be positive.', 1;
    END;
END;
GO
DECLARE @since DATETIME2(0) = DATEADD(DAY, -1, CAST(CAST(SYSUTCDATETIME() AS DATE) AS DATETIME2(0)));
DECLARE @recon TABLE (warehouse_id SMALLINT, item_id INT, expected INT, actual INT);

WITH counted AS (
    SELECT c.warehouse_id, c.item_id, c.counted_qty,
           ROW_NUMBER() OVER (PARTITION BY c.warehouse_id, c.item_id ORDER BY c.counted_at DESC) AS rn
    FROM inv.CycleCounts AS c
    WHERE c.counted_at >= @since
),
latest AS (SELECT warehouse_id, item_id, counted_qty FROM counted WHERE rn = 1)
INSERT INTO @recon (warehouse_id, item_id, expected, actual)
SELECT s.warehouse_id, s.item_id, s.on_hand, l.counted_qty
FROM inv.Stock AS s
JOIN latest AS l ON l.warehouse_id = s.warehouse_id AND l.item_id = s.item_id;

INSERT INTO recon.StockSnapshot (warehouse_id, item_id, qty_system, qty_counted)
OUTPUT inserted.snapshot_id, inserted.diff
SELECT warehouse_id, item_id, expected, actual FROM @recon WHERE expected <> actual;

UPDATE s
SET s.on_hand = r.actual, s.last_recon_at = SYSUTCDATETIME()
OUTPUT deleted.on_hand AS old_qty, inserted.on_hand AS new_qty, inserted.item_id
FROM inv.Stock AS s
INNER JOIN @recon AS r ON r.warehouse_id = s.warehouse_id AND r.item_id = s.item_id
WHERE r.expected <> r.actual;

SELECT w.warehouse_name,
       COUNT(*) AS items_off,
       SUM(ABS(r.actual - r.expected)) AS abs_units,
       STRING_AGG(CAST(r.item_id AS VARCHAR(12)), ',') WITHIN GROUP (ORDER BY r.item_id) AS item_list
FROM @recon AS r
JOIN inv.Warehouses AS w ON w.warehouse_id = r.warehouse_id
WHERE r.actual <> r.expected
GROUP BY ROLLUP (w.warehouse_name);
GO
-- index maintenance with dynamic SQL
DECLARE @tbl SYSNAME, @idx SYSNAME, @frag FLOAT, @cmd NVARCHAR(1000);
DECLARE frag_cur CURSOR LOCAL STATIC FOR
    SELECT OBJECT_NAME(ps.object_id), i.name, ps.avg_fragmentation_in_percent
    FROM sys.dm_db_index_physical_stats(DB_ID(), NULL, NULL, NULL, 'LIMITED') AS ps
    JOIN sys.indexes AS i ON i.object_id = ps.object_id AND i.index_id = ps.index_id
    WHERE ps.avg_fragmentation_in_percent > 10 AND i.name IS NOT NULL;
OPEN frag_cur;
FETCH NEXT FROM frag_cur INTO @tbl, @idx, @frag;
WHILE @@FETCH_STATUS = 0
BEGIN
    SET @cmd = N'ALTER INDEX ' + QUOTENAME(@idx) + N' ON inv.' + QUOTENAME(@tbl)
             + CASE WHEN @frag > 30 THEN N' REBUILD WITH (ONLINE = ON);' ELSE N' REORGANIZE;' END;
    BEGIN TRY
        EXEC sp_executesql @cmd;
    END TRY
    BEGIN CATCH
        PRINT N'Failed: ' + @cmd + N' -> ' + ERROR_MESSAGE();
    END CATCH;
    FETCH NEXT FROM frag_cur INTO @tbl, @idx, @frag;
END;
CLOSE frag_cur;
DEALLOCATE frag_cur;
GO
-- purge old snapshots in chunks
DECLARE @deleted INT = 1;
WHILE @deleted > 0
BEGIN
    DELETE TOP (5000) FROM recon.StockSnapshot WHERE taken_at < DATEADD(MONTH, -6, SYSUTCDATETIME());
    SET @deleted = @@ROWCOUNT;
    WAITFOR DELAY '00:00:00.200';
END;
GO
GRANT SELECT ON SCHEMA::recon TO inventory_readers;
GRANT SELECT ON OBJECT::recon.fn_item_movements TO inventory_readers;
GO
