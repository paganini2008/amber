/* Nightly shipment load (synthetic test script for Amber): batches, temp tables, table
   variables, SELECT INTO, UPDATE-by-alias, MERGE, a procedure with TRY/CATCH, a cursor
   loop and dynamic SQL, then a view and cleanup. */
:setvar TargetDb logistics
USE logistics;
GO

SET NOCOUNT ON;
IF OBJECT_ID('tempdb..#shipments') IS NOT NULL DROP TABLE #shipments;

CREATE TABLE #shipments (
    shipment_id  BIGINT        NOT NULL PRIMARY KEY,
    carrier_code VARCHAR(10)   NOT NULL,
    weight_kg    DECIMAL(12,3) NULL,
    zone         VARCHAR(8)    NULL
);

DECLARE @late TABLE (shipment_id BIGINT PRIMARY KEY, days_late INT);

WITH recent AS (
    SELECT s.shipment_id, s.carrier_code, s.weight_kg, s.dest_postcode
      FROM ops.shipment AS s
     WHERE s.shipped_on >= DATEADD(DAY, -7, CAST(GETDATE() AS DATE))
), zones AS (
    SELECT z.postcode, z.zone FROM ref.delivery_zone z
)
INSERT INTO #shipments (shipment_id, carrier_code, weight_kg, zone)
SELECT r.shipment_id, r.carrier_code, r.weight_kg, z.zone
  FROM recent r
  LEFT JOIN zones z ON z.postcode = r.dest_postcode;

INSERT INTO @late (shipment_id, days_late)
SELECT t.shipment_id, DATEDIFF(DAY, t.promised_on, t.delivered_on)
  FROM ops.tracking t
 WHERE t.delivered_on > t.promised_on;

UPDATE s
   SET s.weight_kg = ISNULL(s.weight_kg, c.default_weight_kg)
  FROM #shipments s
  JOIN ref.carrier c ON c.carrier_code = s.carrier_code;

DELETE s FROM #shipments s WHERE s.zone IS NULL;

SELECT s.carrier_code, COUNT(*) AS shipments, SUM(l.days_late) AS days_late
  INTO #carrier_kpi
  FROM #shipments s
  LEFT JOIN @late l ON l.shipment_id = s.shipment_id
 GROUP BY s.carrier_code;

MERGE dw.carrier_kpi AS k
USING #carrier_kpi AS src ON k.carrier_code = src.carrier_code
WHEN MATCHED THEN UPDATE SET k.shipments = src.shipments, k.days_late = src.days_late
WHEN NOT MATCHED THEN INSERT (carrier_code, shipments, days_late)
                      VALUES (src.carrier_code, src.shipments, src.days_late);
GO

CREATE OR ALTER PROCEDURE dw.usp_rebuild_zone_stats
    @zone VARCHAR(8)
AS
BEGIN
    SET NOCOUNT ON;
    DECLARE @carrier VARCHAR(10), @sql NVARCHAR(400);
    BEGIN TRY
        BEGIN TRANSACTION;
        DECLARE carrier_cur CURSOR LOCAL FAST_FORWARD FOR
            SELECT DISTINCT carrier_code FROM dw.carrier_kpi;
        OPEN carrier_cur;
        FETCH NEXT FROM carrier_cur INTO @carrier;
        WHILE @@FETCH_STATUS = 0
        BEGIN
            IF EXISTS (SELECT 1 FROM dw.zone_stats WHERE zone = @zone AND carrier_code = @carrier)
                UPDATE dw.zone_stats SET refreshed_at = SYSUTCDATETIME()
                 WHERE zone = @zone AND carrier_code = @carrier;
            ELSE
                INSERT INTO dw.zone_stats (zone, carrier_code, refreshed_at)
                VALUES (@zone, @carrier, SYSUTCDATETIME());
            FETCH NEXT FROM carrier_cur INTO @carrier;
        END;
        CLOSE carrier_cur;
        DEALLOCATE carrier_cur;
        SET @sql = N'UPDATE STATISTICS dw.zone_stats';
        EXEC sp_executesql @sql;
        COMMIT TRANSACTION;
    END TRY
    BEGIN CATCH
        IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
        EXEC dw.usp_log_error @source = N'usp_rebuild_zone_stats';
        THROW;
    END CATCH
END
GO

CREATE OR ALTER VIEW dw.v_carrier_scorecard AS
SELECT k.carrier_code, k.shipments, k.days_late, c.carrier_name
  FROM dw.carrier_kpi k
  JOIN ref.carrier c ON c.carrier_code = k.carrier_code;
GO

EXEC dw.usp_rebuild_zone_stats @zone = 'NORTH';
DROP TABLE #shipments, #carrier_kpi;
GO
