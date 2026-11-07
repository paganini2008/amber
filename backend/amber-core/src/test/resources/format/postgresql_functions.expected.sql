\set ON_ERROR_STOP on
\connect reporting
/* setup /* nested comment */ still a comment */
CREATE OR REPLACE FUNCTION calc_score(p_user INT, p_weight NUMERIC DEFAULT 1.0) RETURNS NUMERIC language plpgsql stable AS $body$
declare
  v numeric;
begin
  select sum(points) into v from events where user_id = p_user;   -- keep as written
  return coalesce(v, 0) * p_weight;
end;
$body$;
DO $$ begin raise notice 'hello; -- inside dollar quotes'; end $$;
SELECT
    u.id,
    u.name::text AS name,
    calc_score(u.id) score,
    e'tab\there' AS t,
    array_agg(DISTINCT g.tag ORDER BY g.tag) tags
FROM users u
LEFT JOIN user_tags g USING (id)
WHERE u.created_at >= now() - INTERVAL '30 days'
    AND u.flags @> array['active']
    AND NOT u.deleted
GROUP BY u.id, u.name
HAVING count(*) > 1
ORDER BY score DESC NULLS last
LIMIT 50
OFFSET $1;
INSERT INTO user_stats (user_id, score, refreshed)
SELECT id, calc_score(id), now()
FROM users
ON CONFLICT (user_id) DO UPDATE
SET
    score = excluded.score,
    refreshed = excluded.refreshed
RETURNING user_id, score;
WITH RECURSIVE tree(id, parent_id, depth) AS (
    SELECT id, parent_id, 0
    FROM nodes
    WHERE parent_id IS NULL
    UNION ALL
    SELECT n.id, n.parent_id, t.depth + 1
    FROM nodes n
    JOIN tree t ON t.id = n.parent_id
)
SELECT *
FROM tree
WHERE depth < 5;
