-- Weekly leaderboard for a fictional puzzle game (sample script, H2)
CREATE LOCAL TEMPORARY TABLE tmp_week_scores AS
SELECT s.player_id, s.level_id, MAX(s.score) AS best
  FROM game.scores s
 WHERE s.played_at >= DATEADD('DAY', -7, CURRENT_DATE)
 GROUP BY s.player_id, s.level_id;

MERGE INTO game.weekly_board b
USING (SELECT player_id, SUM(best) AS total, COUNT(*) AS levels
         FROM tmp_week_scores GROUP BY player_id) w
   ON b.player_id = w.player_id
WHEN MATCHED THEN UPDATE SET b.total = w.total, b.levels = w.levels
WHEN NOT MATCHED THEN INSERT (player_id, total, levels) VALUES (w.player_id, w.total, w.levels);

SELECT p.nickname, b.total,
       RANK() OVER (ORDER BY b.total DESC) AS place,
       (SELECT COUNT(*) FROM game.achievements a WHERE a.player_id = p.id) AS badges
  FROM game.weekly_board b
  JOIN game.players p ON p.id = b.player_id
 WHERE p.banned = FALSE
   AND EXISTS (SELECT 1 FROM tmp_week_scores t WHERE t.player_id = p.id)
 ORDER BY place
 LIMIT 50;

UPDATE game.players SET last_rank = (
  SELECT r.place FROM (SELECT player_id, RANK() OVER (ORDER BY total DESC) AS place
                         FROM game.weekly_board) r
   WHERE r.player_id = game.players.id);

DROP TABLE tmp_week_scores;
