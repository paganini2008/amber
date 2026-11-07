-- Non-reserved Oracle keywords used as table, column, alias and CTE names.
create table app.sizing (big number, little number, fixed varchar2(10), latest date, these number, zoned number);
with big as (select big, little, fixed from app.sizing), latest as (select latest from app.sizing)
select b.big, b.fixed, l.latest from big b cross join latest l;
select s.these, s.zoned compatible, s.little characters from app.sizing s where s.big > 0;
update app.sizing set fixed = 'Y', latest = sysdate where these = 1;
