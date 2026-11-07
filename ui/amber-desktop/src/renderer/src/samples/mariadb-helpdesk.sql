-- SLA breach report for a fictional helpdesk (sample script, MariaDB)
CREATE OR REPLACE TABLE tmp_open_tickets ENGINE=MEMORY AS
SELECT t.id, t.queue_id, t.priority, t.opened_at,
       TIMESTAMPDIFF(HOUR, t.opened_at, NOW()) AS age_h
  FROM desk.tickets t
 WHERE t.closed_at IS NULL;

INSERT INTO desk.sla_breaches (ticket_id, queue_name, priority, age_h, noted_at)
SELECT o.id, q.name, o.priority, o.age_h, NOW()
  FROM tmp_open_tickets o
  JOIN desk.queues q ON q.id = o.queue_id
  LEFT JOIN desk.sla_rules r ON r.queue_id = o.queue_id AND r.priority = o.priority
 WHERE o.age_h > COALESCE(r.max_hours, 48)
   AND o.id NOT IN (SELECT ticket_id FROM desk.sla_breaches);

WITH RECURSIVE queue_tree AS (
  SELECT id, name, parent_id, 0 AS depth FROM desk.queues WHERE parent_id IS NULL
  UNION ALL
  SELECT q.id, q.name, q.parent_id, qt.depth + 1
    FROM desk.queues q JOIN queue_tree qt ON q.parent_id = qt.id
)
SELECT qt.name, qt.depth, COUNT(b.ticket_id) AS breaches
  FROM queue_tree qt
  LEFT JOIN desk.sla_breaches b ON b.queue_name = qt.name
 GROUP BY qt.name, qt.depth
 ORDER BY qt.depth, breaches DESC;

UPDATE desk.agents a
   SET a.open_load = (SELECT COUNT(*) FROM tmp_open_tickets o
                       JOIN desk.assignments s ON s.ticket_id = o.id
                      WHERE s.agent_id = a.id);

DELIMITER //
CREATE OR REPLACE PROCEDURE desk.escalate_old()
BEGIN
  UPDATE desk.tickets SET priority = priority + 1
   WHERE closed_at IS NULL AND opened_at < NOW() - INTERVAL 7 DAY;
END //
DELIMITER ;

DROP TABLE tmp_open_tickets;
