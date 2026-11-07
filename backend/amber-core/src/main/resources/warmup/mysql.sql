-- Warm-up script (synthetic). Exercises common MySQL/MariaDB paths once at engine start.
DROP TEMPORARY TABLE IF EXISTS tmp_visits;
CREATE TEMPORARY TABLE tmp_visits AS
SELECT v.page_id, COUNT(*) AS hits, MAX(v.visited_at) AS last_visit
  FROM web.visit v
  JOIN web.page p ON p.page_id = v.page_id
 WHERE v.visited_at >= NOW() - INTERVAL 1 DAY
 GROUP BY v.page_id;
INSERT INTO web.page_stats (page_id, hits, last_visit)
SELECT page_id, hits, last_visit FROM tmp_visits
ON DUPLICATE KEY UPDATE hits = VALUES(hits), last_visit = VALUES(last_visit);
UPDATE web.page p JOIN tmp_visits t ON t.page_id = p.page_id SET p.popular = (t.hits > 1000);
DELIMITER $$
CREATE PROCEDURE web.purge_old(IN days INT)
BEGIN
  DELETE FROM web.visit WHERE visited_at < NOW() - INTERVAL days DAY;
END$$
DELIMITER ;
CALL web.purge_old(30);
