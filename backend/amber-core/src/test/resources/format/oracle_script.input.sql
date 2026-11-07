-- nightly refresh of the sales mart
set define on
set serveroutput on size unlimited
whenever sqlerror exit failure rollback
prompt === step 1: stage ===
define run_date = '2024-01-31'
column cnt new_value v_cnt

select /*+ parallel(s 4) */ s.region_code, s.product_id, sum(s.qty) qty, sum(s.amount) amt, count(*) cnt from stg_sales s where s.sale_date = to_date('&run_date', 'YYYY-MM-DD') and s.note <> 'skip -- not a comment; really' -- trailing remark
and s.batch_id = &&batch_id. group by s.region_code, s.product_id order by 1, 2;



insert into mart_sales (region_code, product_id, qty, amount, load_ts)
select region_code, product_id, qty, amt, systimestamp from (select region_code, product_id, sum(qty) qty, sum(amount) amt from (select * from (select s.*, row_number() over (partition by s.id order by s.version desc) rn from stg_sales s where s.status in ('A','B','C')) where rn = 1) group by region_code, product_id);

update mart_sales m set m.amount = (select sum(x.amount) from adj x where x.region_code = m.region_code /* same region */ and x.product_id = m.product_id), m.load_ts = systimestamp where exists (select 1 from adj x where x.region_code = m.region_code);

delete from mart_sales where load_ts < add_months(sysdate, -24) and q'[it's old; drop it]' is not null;

merge into dim_product d using (select id, name, price from stg_product where name like '%;%') s on (d.id = s.id) when matched then update set d.name = s.name, d.price = s.price where d.price <> s.price when not matched then insert (id, name, price) values (s.id, s.name, s.price);

with base as (select region_code, sum(amount) amt from mart_sales group by region_code), ranked as (select b.*, rank() over (order by amt desc) rk from base b), top_regions as (select * from ranked where rk <= 5) select r.region_code, r.amt, case when r.rk = 1 then 'leader' when r.rk between 2 and 3 then 'challenger' else 'follower' end as tier from top_regions r order by r.rk;

create table mart_audit (audit_id number(18) not null, run_date date default sysdate not null, message varchar2(4000), constraint mart_audit_pk primary key (audit_id));
/
@@post_load.sql
exit
