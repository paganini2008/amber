-- Weekly inventory reconciliation for a fictional warehouse (sample script, SQL Server)
CREATE PROCEDURE dbo.usp_reconcile_inventory
  @week_start DATE
AS
BEGIN
  SET NOCOUNT ON;

  CREATE TABLE #movements (
    sku VARCHAR(32) NOT NULL,
    qty INT NOT NULL
  );

  INSERT INTO #movements (sku, qty)
  SELECT m.sku, SUM(m.qty)
  FROM wh.stock_movements m
  WHERE m.moved_on >= @week_start
  GROUP BY m.sku;

  IF EXISTS (SELECT 1 FROM #movements WHERE qty < 0)
  BEGIN
    INSERT INTO wh.alerts (sku, message)
    SELECT sku, 'negative weekly movement' FROM #movements WHERE qty < 0;
  END
  ELSE
  BEGIN
    UPDATE wh.alerts SET resolved = 1 WHERE resolved = 0;
  END

  DECLARE @batch INT = 0;
  WHILE @batch < 3
  BEGIN
    UPDATE s
    SET s.on_hand = s.on_hand + mv.qty
    FROM wh.stock s
    JOIN #movements mv ON mv.sku = s.sku;
    SET @batch = @batch + 1;
  END

  BEGIN TRY
    INSERT INTO wh.audit_log (run_on, rows_moved)
    SELECT GETDATE(), COUNT(*) FROM #movements;
  END TRY
  BEGIN CATCH
    INSERT INTO wh.audit_log (run_on, rows_moved) VALUES (GETDATE(), -1);
  END CATCH

  DROP TABLE #movements;
END;
GO

EXEC dbo.usp_reconcile_inventory @week_start = '2026-01-05';
GO
