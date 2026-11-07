create table dbo.sales (id int not null primary key, sold_at datetime2 not null, region varchar(10), amount decimal(10,2));
go
create procedure dbo.p_load
as
begin
    set nocount on;
    /* keep a copy of today's sales */
    insert into dbo.sales_hist select * from dbo.sales where cast(sold_at as date) = cast(getdate() as date);
    select distinct region, count(*) as n from dbo.sales group by region;
end
go
