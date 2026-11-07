-------------------------------------------------------------------------------
-- Daily order ETL (synthetic test script for Amber; mixes SQL*Plus, DDL, DML,
-- PL/SQL programs and cleanup the way real batch jobs do)
-------------------------------------------------------------------------------
set serveroutput on size unlimited
set define on
whenever sqlerror exit failure rollback
define run_date=to_date('20261106','YYYYMMDD')
prompt Preparing staging objects...

drop table tmp_order purge;

create global temporary table tmp_order (
  order_id    number(18)   primary key,
  cust_id     number(18)   not null,
  region      varchar2(10),
  amount      number(18,2),
  status      varchar2(10) default 'NEW'
) on commit preserve rows;

create index idx_tmp_order_cust on tmp_order (cust_id);

insert /*+ append */ into tmp_order (order_id, cust_id, region, amount, status)
with paid_orders as (
       select o.order_id, o.cust_id, o.amount
         from sales.orders o
        where o.status = 'PAID'
          and o.order_date >= &run_date - 30
     ),
     cust_lvl as (
       select c.cust_id, c.region,
              rank() over (partition by c.region order by c.credit_score desc) as rnk
         from crm.customers c
     )
select p.order_id, p.cust_id, l.region, p.amount, 'NEW'
  from paid_orders p
  left join cust_lvl l on l.cust_id = p.cust_id
 where l.rnk <= 100
    or exists (select 1 from crm.vip_list v where v.cust_id = p.cust_id);

update tmp_order t
   set t.amount = t.amount - (select nvl(sum(r.refund_amt), 0)
                                from sales.refunds r
                               where r.order_id = t.order_id),
       t.status = 'ADJUSTED'
 where t.order_id in (select r2.order_id from sales.refunds r2);

merge into dw.order_fact f
using (select order_id, cust_id, region, amount from tmp_order) s
   on (f.order_id = s.order_id)
 when matched then update set f.amount = s.amount, f.region = s.region
 when not matched then insert (order_id, cust_id, region, amount)
                       values (s.order_id, s.cust_id, s.region, s.amount);

commit;

create or replace procedure dw.p_calc_bonus (p_month in varchar2) is
  cursor c_fact is
    select f.cust_id, sum(f.amount) as total
      from dw.order_fact f
     group by f.cust_id;
  v_sql varchar2(200);
begin
  for r in c_fact loop
    if r.total > 10000 then
      insert into dw.bonus (cust_id, bonus_month, bonus) values (r.cust_id, p_month, r.total * 0.01);
    elsif r.total > 1000 then
      update dw.bonus b set b.bonus = b.bonus + 10 where b.cust_id = r.cust_id;
    else
      null;
    end if;
  end loop;
  v_sql := 'truncate table dw.stage_bonus';
  execute immediate v_sql;
  commit;
exception
  when others then
    rollback;
    dw.log_pkg.log_error('p_calc_bonus', sqlerrm);
    raise;
end p_calc_bonus;
/

create or replace package dw.log_pkg is
  procedure log_error (p_source in varchar2, p_message in varchar2);
end log_pkg;
/

create or replace package body dw.log_pkg is
  procedure log_error (p_source in varchar2, p_message in varchar2) is
    pragma autonomous_transaction;
  begin
    insert into dw.etl_log (log_time, source, message) values (systimestamp, p_source, p_message);
    commit;
  end log_error;
end log_pkg;
/

begin
  dw.p_calc_bonus(to_char(&run_date, 'YYYYMM'));
end;
/

create or replace view dw.v_bonus_report as
select b.cust_id, c.display_name, b.bonus
  from dw.bonus b
  join crm.customers c on c.cust_id = b.cust_id;

create table dw.bonus_snapshot as
select * from dw.v_bonus_report where bonus > 0;

delete from dw.order_fact where order_id in (select order_id from tmp_order where status = 'ADJUSTED' and amount <= 0);

truncate table dw.stage_bonus;
drop table tmp_order purge;
commit;
prompt Done.
