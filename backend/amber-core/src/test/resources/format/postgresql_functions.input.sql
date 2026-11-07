\set ON_ERROR_STOP on
\connect reporting
/* setup /* nested comment */ still a comment */
create or replace function calc_score(p_user int, p_weight numeric default 1.0) returns numeric language plpgsql stable as $body$
declare
  v numeric;
begin
  select sum(points) into v from events where user_id = p_user;   -- keep as written
  return coalesce(v, 0) * p_weight;
end;
$body$;
do $$ begin raise notice 'hello; -- inside dollar quotes'; end $$;
select u.id, u.name::text as name, calc_score(u.id) score, e'tab\there' as t, array_agg(distinct g.tag order by g.tag) tags from users u left join user_tags g using (id) where u.created_at >= now() - interval '30 days' and u.flags @> array['active'] and not u.deleted group by u.id, u.name having count(*) > 1 order by score desc nulls last limit 50 offset $1;
insert into user_stats (user_id, score, refreshed) select id, calc_score(id), now() from users on conflict (user_id) do update set score = excluded.score, refreshed = excluded.refreshed returning user_id, score;
with recursive tree(id, parent_id, depth) as (select id, parent_id, 0 from nodes where parent_id is null union all select n.id, n.parent_id, t.depth + 1 from nodes n join tree t on t.id = n.parent_id) select * from tree where depth < 5;
