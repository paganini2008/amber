-- Views and materialized views
CREATE VIEW sales.v_open_orders AS SELECT * FROM sales.orders WHERE status = 'OPEN';
CREATE OR REPLACE VIEW sales.v_open_orders (id, amt) AS SELECT order_id, amount FROM sales.orders WHERE status = 'OPEN' WITH CHECK OPTION;
CREATE VIEW sales.v_local WITH (security_barrier = true, security_invoker = true) AS SELECT 1 AS x WITH LOCAL CHECK OPTION;
CREATE RECURSIVE VIEW sales.v_nums (n) AS VALUES (1) UNION ALL SELECT n + 1 FROM sales.v_nums WHERE n < 5;
CREATE TEMP VIEW v_tmp AS SELECT 1;
ALTER VIEW sales.v_open_orders RENAME TO v_open;
ALTER VIEW sales.v_open ALTER COLUMN amt SET DEFAULT 0;
ALTER VIEW sales.v_open OWNER TO report_role;
DROP VIEW IF EXISTS sales.v_open CASCADE;
CREATE MATERIALIZED VIEW sales.mv_daily AS SELECT order_date, sum(amount) total FROM sales.orders GROUP BY order_date WITH DATA;
CREATE MATERIALIZED VIEW IF NOT EXISTS sales.mv_empty TABLESPACE fast_ssd AS SELECT 1 AS x WITH NO DATA;
CREATE MATERIALIZED VIEW sales.mv_cols (d, t) WITH (fillfactor = 90) AS SELECT order_date, amount FROM sales.orders;
REFRESH MATERIALIZED VIEW sales.mv_daily;
REFRESH MATERIALIZED VIEW CONCURRENTLY sales.mv_daily WITH DATA;
REFRESH MATERIALIZED VIEW sales.mv_daily WITH NO DATA;
ALTER MATERIALIZED VIEW sales.mv_daily RENAME TO mv_day;
DROP MATERIALIZED VIEW IF EXISTS sales.mv_day, sales.mv_empty;
