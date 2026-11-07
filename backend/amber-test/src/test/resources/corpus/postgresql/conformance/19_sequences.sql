-- Sequences
CREATE SEQUENCE sales.order_seq;
CREATE SEQUENCE IF NOT EXISTS sales.inv_seq AS bigint INCREMENT BY 10 MINVALUE 1 MAXVALUE 999999 START WITH 100 CACHE 50 CYCLE OWNED BY sales.invoice.invoice_id;
CREATE SEQUENCE sales.desc_seq INCREMENT -1 NO MINVALUE NO MAXVALUE START 0 NO CYCLE;
CREATE UNLOGGED SEQUENCE sales.fast_seq;
SELECT nextval('sales.order_seq'), currval('sales.order_seq'), lastval();
SELECT setval('sales.order_seq', 500, true);
SELECT nextval('sales.order_seq'::regclass);
INSERT INTO sales.invoice (invoice_id, total) VALUES (nextval('sales.inv_seq'), 10);
ALTER SEQUENCE sales.order_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS sales.order_seq INCREMENT BY 5 OWNED BY NONE;
ALTER SEQUENCE sales.order_seq RENAME TO ord_seq;
ALTER SEQUENCE sales.ord_seq SET SCHEMA archive;
CREATE TABLE sales.t_serial (id integer DEFAULT nextval('sales.inv_seq') PRIMARY KEY);
SELECT * FROM pg_sequences WHERE schemaname = 'sales';
DROP SEQUENCE IF EXISTS sales.desc_seq, sales.fast_seq CASCADE;
