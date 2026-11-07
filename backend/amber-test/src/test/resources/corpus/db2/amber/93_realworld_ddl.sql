-- =====================================================================
-- Database build for the SALES application (Db2 12.1 LUW)
-- Storage groups, buffer pools, tablespaces, schema objects
-- =====================================================================
CONNECT TO salesdb;

-- Storage ---------------------------------------------------------------
CREATE STOGROUP sg_fast ON '/db2/fs_ssd1', '/db2/fs_ssd2' OVERHEAD 0.8 DEVICE READ RATE 1024 DATA TAG 1;
CREATE STOGROUP sg_slow ON '/db2/fs_hdd1', '/db2/fs_hdd2' DATA TAG 9;

CREATE BUFFERPOOL bp_data_32k IMMEDIATE SIZE AUTOMATIC PAGESIZE 32K;
CREATE BUFFERPOOL bp_idx_16k IMMEDIATE SIZE 25000 AUTOMATIC PAGESIZE 16K;
CREATE BUFFERPOOL bp_temp_32k SIZE 5000 PAGESIZE 32K;

CREATE LARGE TABLESPACE ts_sales_data
    PAGESIZE 32K
    MANAGED BY AUTOMATIC STORAGE
    USING STOGROUP sg_fast
    AUTORESIZE YES INITIALSIZE 1 G INCREASESIZE 10 PERCENT MAXSIZE NONE
    EXTENTSIZE 32 PREFETCHSIZE AUTOMATIC
    BUFFERPOOL bp_data_32k
    NO FILE SYSTEM CACHING;
CREATE LARGE TABLESPACE ts_sales_idx PAGESIZE 16K MANAGED BY AUTOMATIC STORAGE USING STOGROUP sg_fast BUFFERPOOL bp_idx_16k;
CREATE LARGE TABLESPACE ts_sales_hist PAGESIZE 32K MANAGED BY AUTOMATIC STORAGE USING STOGROUP sg_slow BUFFERPOOL bp_data_32k;
CREATE LARGE TABLESPACE ts_2024 PAGESIZE 32K MANAGED BY AUTOMATIC STORAGE USING STOGROUP sg_slow BUFFERPOOL bp_data_32k;
CREATE LARGE TABLESPACE ts_2025 PAGESIZE 32K MANAGED BY AUTOMATIC STORAGE USING STOGROUP sg_fast BUFFERPOOL bp_data_32k;
CREATE SYSTEM TEMPORARY TABLESPACE ts_temp_32k PAGESIZE 32K MANAGED BY AUTOMATIC STORAGE BUFFERPOOL bp_temp_32k;
CREATE USER TEMPORARY TABLESPACE ts_utemp_32k PAGESIZE 32K MANAGED BY AUTOMATIC STORAGE BUFFERPOOL bp_temp_32k;

-- Schema and security ---------------------------------------------------------
CREATE SCHEMA sales AUTHORIZATION salesadm;
CREATE ROLE sales_rw;
CREATE ROLE sales_ro;
GRANT USE OF TABLESPACE ts_sales_data TO ROLE sales_rw;
GRANT CREATEIN ON SCHEMA sales TO ROLE sales_rw;

-- Types and sequences ---------------------------------------------------------
CREATE TYPE sales.amount_t AS DECIMAL(15, 2) WITH COMPARISONS;
CREATE TYPE sales.code_t AS VARCHAR(20);
CREATE SEQUENCE sales.seq_customer AS INTEGER START WITH 100000 INCREMENT BY 1 NO MAXVALUE NO CYCLE CACHE 100;
CREATE SEQUENCE sales.seq_invoice AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 500 ORDER;

-- Tables -------------------------------------------------------------------------
CREATE TABLE sales.region (
    region_id   SMALLINT     NOT NULL,
    region_code CHAR(3)      NOT NULL,
    name        VARCHAR(100) NOT NULL,
    CONSTRAINT pk_region PRIMARY KEY (region_id),
    CONSTRAINT uq_region_code UNIQUE (region_code)
) IN ts_sales_data INDEX IN ts_sales_idx ORGANIZE BY ROW;

CREATE TABLE sales.customer (
    customer_id  INTEGER      NOT NULL DEFAULT NEXT VALUE FOR sales.seq_customer,
    region_id    SMALLINT     NOT NULL,
    name         VARCHAR(200) NOT NULL,
    vat_no       VARCHAR(30),
    email        VARCHAR(254),
    credit_limit sales.amount_t,
    status       CHAR(1)      NOT NULL WITH DEFAULT 'A',
    created_ts   TIMESTAMP    NOT NULL WITH DEFAULT CURRENT TIMESTAMP,
    row_changed  TIMESTAMP    NOT NULL GENERATED ALWAYS FOR EACH ROW ON UPDATE AS ROW CHANGE TIMESTAMP,
    sys_start    TIMESTAMP(12) NOT NULL GENERATED ALWAYS AS ROW BEGIN,
    sys_end      TIMESTAMP(12) NOT NULL GENERATED ALWAYS AS ROW END,
    trans_id     TIMESTAMP(12) GENERATED ALWAYS AS TRANSACTION START ID,
    PERIOD SYSTEM_TIME (sys_start, sys_end),
    CONSTRAINT pk_customer PRIMARY KEY (customer_id),
    CONSTRAINT fk_customer_region FOREIGN KEY (region_id) REFERENCES sales.region (region_id) ON DELETE RESTRICT,
    CONSTRAINT ck_customer_status CHECK (status IN ('A', 'I', 'B'))
) IN ts_sales_data INDEX IN ts_sales_idx;

CREATE TABLE sales.customer_hist LIKE sales.customer IN ts_sales_hist;
ALTER TABLE sales.customer ADD VERSIONING USE HISTORY TABLE sales.customer_hist;

CREATE TABLE sales.invoice (
    invoice_id   BIGINT        NOT NULL,
    customer_id  INTEGER       NOT NULL,
    invoice_date DATE          NOT NULL,
    due_date     DATE          NOT NULL GENERATED ALWAYS AS (invoice_date + 30 DAYS),
    total        sales.amount_t NOT NULL,
    currency     CHAR(3)       NOT NULL DEFAULT 'EUR',
    payload      CLOB(10M)     INLINE LENGTH 1000,
    pdf          BLOB(20M)     NOT LOGGED,
    CONSTRAINT pk_invoice PRIMARY KEY (invoice_id, invoice_date),
    CONSTRAINT fk_invoice_customer FOREIGN KEY (customer_id) REFERENCES sales.customer (customer_id) NOT ENFORCED ENABLE QUERY OPTIMIZATION
)
DISTRIBUTE BY HASH (invoice_id)
PARTITION BY RANGE (invoice_date)
(
    PARTITION p_old  STARTING (MINVALUE)     ENDING ('2023-12-31') INCLUSIVE IN ts_sales_hist,
    PARTITION p_2024 STARTING ('2024-01-01') ENDING ('2024-12-31') INCLUSIVE IN ts_2024 INDEX IN ts_sales_idx,
    PARTITION p_2025 STARTING ('2025-01-01') ENDING ('2025-12-31') INCLUSIVE IN ts_2025 INDEX IN ts_sales_idx LONG IN ts_2025,
    PARTITION p_max  STARTING ('2026-01-01') ENDING (MAXVALUE)    IN ts_sales_data
)
COMPRESS YES ADAPTIVE;

CREATE TABLE sales.invoice_line (
    invoice_id   BIGINT       NOT NULL,
    invoice_date DATE         NOT NULL,
    line_no      SMALLINT     NOT NULL,
    product_code sales.code_t NOT NULL,
    qty          DECIMAL(11, 3) NOT NULL CHECK (qty > 0),
    unit_price   DECIMAL(15, 4) NOT NULL,
    line_total   DECIMAL(17, 2) GENERATED ALWAYS AS (qty * unit_price),
    PRIMARY KEY (invoice_id, invoice_date, line_no),
    FOREIGN KEY (invoice_id, invoice_date) REFERENCES sales.invoice ON DELETE CASCADE
)
DISTRIBUTE BY HASH (invoice_id)
PARTITION BY RANGE (invoice_date) (STARTING '2024-01-01' ENDING '2026-12-31' EVERY 3 MONTHS)
IN ts_sales_data INDEX IN ts_sales_idx;

CREATE TABLE sales.fact_daily (
    sale_date   DATE NOT NULL,
    region_id   SMALLINT NOT NULL,
    product_code VARCHAR(20) NOT NULL,
    qty         DECIMAL(15, 3),
    revenue     DECIMAL(17, 2)
) ORGANIZE BY COLUMN IN ts_sales_data DISTRIBUTE BY HASH (product_code);

-- Indexes ---------------------------------------------------------------------------
CREATE INDEX sales.ix_customer_region ON sales.customer (region_id, name) COLLECT SAMPLED DETAILED STATISTICS;
CREATE UNIQUE INDEX sales.ux_customer_vat ON sales.customer (vat_no) EXCLUDE NULL KEYS;
CREATE INDEX sales.ix_customer_email ON sales.customer (LOWER(email));
CREATE INDEX sales.ix_invoice_cust ON sales.invoice (customer_id, invoice_date DESC) PARTITIONED INCLUDE (total) ALLOW REVERSE SCANS;
CREATE INDEX sales.ix_line_product ON sales.invoice_line (product_code) NOT PARTITIONED IN ts_sales_idx PCTFREE 10 PAGE SPLIT SYMMETRIC COMPRESS YES;

-- Views and MQTs ---------------------------------------------------------------------
CREATE OR REPLACE VIEW sales.v_open_invoices AS
    SELECT i.invoice_id, i.invoice_date, i.due_date, c.name AS customer, i.total,
           DAYS(CURRENT DATE) - DAYS(i.due_date) AS days_overdue
    FROM sales.invoice i
    JOIN sales.customer c ON c.customer_id = i.customer_id
    WHERE NOT EXISTS (SELECT 1 FROM sales.payment p WHERE p.invoice_id = i.invoice_id)
WITH CHECK OPTION;

CREATE OR REPLACE VIEW sales.v_customer_asof (customer_id, name, status) AS
    SELECT customer_id, name, status FROM sales.customer FOR SYSTEM_TIME AS OF CURRENT TIMESTAMP - 1 MONTH;

CREATE TABLE sales.mqt_revenue_month AS (
    SELECT YEAR(i.invoice_date) AS yr, MONTH(i.invoice_date) AS mon, c.region_id,
           SUM(l.line_total) AS revenue, COUNT(*) AS line_cnt, COUNT_BIG(*) AS cnt
    FROM sales.invoice i
    JOIN sales.invoice_line l ON l.invoice_id = i.invoice_id AND l.invoice_date = i.invoice_date
    JOIN sales.customer c ON c.customer_id = i.customer_id
    GROUP BY YEAR(i.invoice_date), MONTH(i.invoice_date), c.region_id
)
DATA INITIALLY DEFERRED REFRESH DEFERRED
ENABLE QUERY OPTIMIZATION
MAINTAINED BY SYSTEM
IN ts_sales_data;

CREATE TABLE sales.mqt_customer_cnt (region_id, cnt) AS (
    SELECT region_id, COUNT(*) FROM sales.customer GROUP BY region_id
) DATA INITIALLY DEFERRED REFRESH IMMEDIATE;

SET INTEGRITY FOR sales.mqt_customer_cnt IMMEDIATE CHECKED;
REFRESH TABLE sales.mqt_revenue_month;

-- Aliases, comments, grants ------------------------------------------------------------
CREATE OR REPLACE ALIAS sales.cust FOR TABLE sales.customer;
CREATE OR REPLACE PUBLIC ALIAS invoice FOR TABLE sales.invoice;

COMMENT ON TABLE sales.customer IS 'Customers (system-period temporal)';
COMMENT ON sales.invoice (
    invoice_id IS 'Invoice number',
    due_date IS 'Generated: invoice date + 30 days',
    payload IS 'Original XML/JSON payload'
);
COMMENT ON INDEX sales.ix_customer_email IS 'Case-insensitive e-mail lookup';

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE sales.customer TO ROLE sales_rw;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE sales.invoice TO ROLE sales_rw;
GRANT SELECT ON TABLE sales.v_open_invoices TO ROLE sales_ro, ROLE sales_rw;
GRANT USAGE ON SEQUENCE sales.seq_customer TO ROLE sales_rw;
GRANT ROLE sales_ro TO GROUP reporting;
REVOKE SELECT ON TABLE sales.customer FROM PUBLIC;

-- Maintenance ----------------------------------------------------------------------------
ALTER TABLE sales.invoice ADD PARTITION p_2026 STARTING ('2026-01-01') ENDING ('2026-12-31') INCLUSIVE IN ts_2025;
ALTER TABLE sales.invoice DETACH PARTITION p_old INTO sales.invoice_archive_2023;
ALTER TABLE sales.customer ALTER COLUMN email SET DATA TYPE VARCHAR(320) ALTER COLUMN vat_no SET NOT NULL;
ALTER TABLE sales.customer ADD COLUMN segment VARCHAR(20) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE sales.region DATA CAPTURE CHANGES;
RENAME TABLE sales.invoice_archive_2023 TO invoice_2023;
RUNSTATS ON TABLE sales.customer WITH DISTRIBUTION AND DETAILED INDEXES ALL;
REORG TABLE sales.invoice ON DATA PARTITION p_2024;
COMMIT;
CONNECT RESET;
