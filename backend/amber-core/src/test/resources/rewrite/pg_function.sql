create table events (id bigint primary key, happened_at timestamp not null, kind text);

create or replace function purge_events() returns void as $$
declare
  v bigint;
begin
  delete from events where id not in (select e2.id from events e2 where e2.kind = 'keep');
  update events set kind = 'old' where happened_at::date < date '2020-01-01';
  select count(*) into strict v from events where happened_at::date = date '2024-01-01';
end;
$$ language plpgsql;
