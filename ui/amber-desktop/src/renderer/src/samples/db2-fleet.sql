-- Maintenance planning for a fictional delivery fleet (sample script, Db2)
DECLARE GLOBAL TEMPORARY TABLE session.due_service (
  vehicle_id INTEGER, depot_id INTEGER, km_since INTEGER
) ON COMMIT PRESERVE ROWS NOT LOGGED;

INSERT INTO session.due_service (vehicle_id, depot_id, km_since)
SELECT v.vehicle_id, v.depot_id, v.odometer_km - COALESCE(s.last_km, 0)
  FROM fleet.vehicles v
  LEFT JOIN (SELECT vehicle_id, MAX(odometer_km) AS last_km
               FROM fleet.service_log GROUP BY vehicle_id) s
    ON s.vehicle_id = v.vehicle_id
 WHERE v.retired = 0;

MERGE INTO fleet.service_plan p
USING (SELECT d.vehicle_id, d.depot_id, d.km_since
         FROM session.due_service d
        WHERE d.km_since > (SELECT interval_km FROM fleet.service_rules r FETCH FIRST 1 ROW ONLY)) x
   ON p.vehicle_id = x.vehicle_id
WHEN MATCHED THEN UPDATE SET p.km_since = x.km_since, p.planned_on = CURRENT DATE + 7 DAYS
WHEN NOT MATCHED THEN INSERT (vehicle_id, depot_id, km_since, planned_on)
     VALUES (x.vehicle_id, x.depot_id, x.km_since, CURRENT DATE + 7 DAYS);

CREATE OR REPLACE VIEW fleet.v_depot_load AS
SELECT dp.depot_name, COUNT(*) AS planned, SUM(p.km_since) AS km_total
  FROM fleet.service_plan p
  JOIN fleet.depots dp ON dp.depot_id = p.depot_id
 GROUP BY dp.depot_name;

SELECT *
  FROM fleet.v_depot_load
 WHERE planned > 0
UNION
SELECT 'unassigned', COUNT(*), SUM(km_since)
  FROM session.due_service
 WHERE depot_id IS NULL;

DELETE FROM fleet.service_plan
 WHERE vehicle_id IN (SELECT vehicle_id FROM fleet.vehicles WHERE retired = 1);
COMMIT;
