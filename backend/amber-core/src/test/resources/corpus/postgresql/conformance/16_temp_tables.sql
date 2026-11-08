-- Temporary tables
CREATE TEMP TABLE tmp_orders (order_id bigint, amount numeric);
CREATE TEMPORARY TABLE IF NOT EXISTS tmp_customers (id int) ON COMMIT DELETE ROWS;
CREATE TEMP TABLE tmp_batch ON COMMIT DROP AS SELECT * FROM sales.orders WHERE false;
CREATE LOCAL TEMPORARY TABLE tmp_local (id int) ON COMMIT PRESERVE ROWS;
CREATE GLOBAL TEMPORARY TABLE tmp_global (id int);
CREATE TEMP TABLE tmp_like (LIKE sales.orders INCLUDING DEFAULTS);
SELECT * INTO TEMPORARY tmp_into FROM sales.orders;
SELECT * INTO UNLOGGED TABLE log_into FROM sales.orders;
INSERT INTO tmp_orders SELECT order_id, amount FROM sales.orders;
ANALYZE tmp_orders;
CREATE TEMP VIEW tmp_v AS SELECT * FROM tmp_orders;
CREATE TEMP SEQUENCE tmp_seq;
DROP TABLE IF EXISTS tmp_orders, tmp_customers;
DISCARD TEMP;
DISCARD ALL;
