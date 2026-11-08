if schema_id('wh') is null exec('create schema wh');
create table wh.stock (sku varchar(32) not null primary key, on_hand int not null);
create table wh.stock_movements (id bigint identity primary key, sku varchar(32) not null, qty int not null, moved_on date not null);
create index ix_movements_moved on wh.stock_movements (moved_on);
create table wh.alerts (id bigint identity primary key, sku varchar(32) not null, message varchar(200), resolved bit not null default 0);
create table wh.audit_log (run_on datetime2 not null, rows_moved int not null);
insert into wh.stock values ('SKU-1', 40), ('SKU-2', 5);
insert into wh.stock_movements (sku, qty, moved_on) values ('SKU-1', -3, '2026-01-06'), ('SKU-2', 7, '2026-01-07');
