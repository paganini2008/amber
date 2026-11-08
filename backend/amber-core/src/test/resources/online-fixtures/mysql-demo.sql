-- for a MySQL account limited to the demo database
create table demo.customers (id bigint primary key, email varchar(200) not null unique, region varchar(20));
create table demo.orders (id bigint primary key, customer_id bigint not null, amount decimal(10,2) not null, created_at datetime not null, key ix_orders_customer (customer_id));
insert into demo.customers values (1, 'a@example.com', 'EU'), (2, 'b@example.com', 'US');
insert into demo.orders values (1, 1, 20.00, now()), (2, 2, 35.00, now());
