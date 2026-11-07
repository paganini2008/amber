-- Monthly summary for a fictional fitness tracker app (sample script, SQLite)
CREATE TEMP TABLE tmp_month AS
SELECT w.user_id, w.kind, SUM(w.minutes) AS minutes, SUM(w.kcal) AS kcal
  FROM workouts w
 WHERE strftime('%Y-%m', w.started_at) = strftime('%Y-%m', 'now', '-1 month')
 GROUP BY w.user_id, w.kind;

INSERT OR REPLACE INTO monthly_summary (user_id, month, minutes, kcal, favourite_kind)
WITH totals AS (
  SELECT user_id, SUM(minutes) AS minutes, SUM(kcal) AS kcal FROM tmp_month GROUP BY user_id
)
SELECT t.user_id, strftime('%Y-%m', 'now', '-1 month'), t.minutes, t.kcal,
       (SELECT m.kind FROM tmp_month m WHERE m.user_id = t.user_id ORDER BY m.minutes DESC LIMIT 1)
  FROM totals t;

UPDATE users
   SET streak_days = (SELECT COUNT(DISTINCT date(w.started_at)) FROM workouts w
                       WHERE w.user_id = users.id AND w.started_at >= date('now', '-30 day'))
 WHERE id IN (SELECT user_id FROM tmp_month);

SELECT u.display_nm, s.minutes, s.kcal
  FROM monthly_summary s, users u
 WHERE s.user_id = u.id
   AND s.month = strftime('%Y-%m', 'now', '-1 month')
UNION
SELECT 'everyone', SUM(minutes), SUM(kcal) FROM tmp_month;

DROP TABLE tmp_month;
