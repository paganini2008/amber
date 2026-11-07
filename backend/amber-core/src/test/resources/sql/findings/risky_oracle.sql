-- Synthetic script for Amber's findings rules: every statement carries one known problem.
select o.id, c.name from orders o, customers c;
select o.id from orders o left join refunds r on r.order_id = o.id where r.status = 'DONE';
select o.id from orders o left join refunds r on r.order_id = o.id where r.order_id is null;
select id from customers where name like '%smith';
select id from orders where customer_id not in (select id from blocked_customers);
select a.id from orders a join orders b on b.parent_id = a.id;
select id from orders where trunc(created_at) = date '2026-01-01';
select id from orders union select id from archived_orders;
create table orders_copy as select * from orders;
select distinct region from customers group by region;
select * from (select id from orders order by id) x;
insert into audit_log select * from (select id, note from staging_notes where flagged = 1) s;
insert into audit_log select * from (select id, note from staging_notes where flagged = 1) s;
-- amber:ignore AMB-S009
select id from orders union select id from archived_orders;
begin
  for r in (select id from orders) loop
    update orders set seen = 1 where id = r.id;
    commit;
  end loop;
  execute immediate 'truncate table staging_notes';
end;
/
