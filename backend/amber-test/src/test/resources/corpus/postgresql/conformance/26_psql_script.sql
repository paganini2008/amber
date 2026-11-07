-- psql client script with meta-commands and variable interpolation
\set ON_ERROR_STOP on
\set QUIET 1
\pset format aligned
\pset null '(null)'
\timing on
\echo 'Starting load for' :target_date
\set target_date '2024-06-30'
\set schema_name staging
\connect analytics etl_user
\c analytics
SET search_path TO :schema_name, public;
SELECT count(*) FROM :schema_name.orders WHERE order_date = :'target_date';
SELECT * FROM sales.orders WHERE customer_id = :customer_id;
SELECT :"schema_name" AS quoted_ident_var;
\i common/setup.sql
\ir ./helpers.sql
\copy sales.orders FROM 'orders.csv' WITH (FORMAT csv, HEADER true)
\copy (SELECT * FROM sales.orders WHERE amount > 0) TO 'positive.csv' CSV HEADER
SELECT format('ANALYZE %I.%I', schemaname, tablename) FROM pg_tables WHERE schemaname = 'sales' \gexec
SELECT max(order_id) AS max_id FROM sales.orders \gset
\echo max id is :max_id
\if :ERROR
  \echo 'previous command failed'
\elif :max_id
  \echo 'have orders'
\else
  \echo 'no orders'
\endif
SELECT now()
\g
SELECT 1 \g /tmp/one.txt
\o /tmp/report.txt
SELECT region, sum(amount) FROM sales.orders GROUP BY region;
\o
\dt sales.*
\d+ sales.orders
\x auto
\unset target_date
BEGIN;
INSERT INTO audit.log (msg) VALUES ('psql run ' || :'schema_name');
COMMIT;
\q
