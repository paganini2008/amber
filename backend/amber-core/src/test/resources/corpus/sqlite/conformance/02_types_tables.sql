-- Type affinity, constraints, STRICT, WITHOUT ROWID, generated columns
CREATE TABLE customer (
  customer_id INTEGER PRIMARY KEY AUTOINCREMENT,
  display_name VARCHAR(100) NOT NULL,
  email TEXT UNIQUE COLLATE NOCASE,
  balance NUMERIC(12,2) DEFAULT 0.00 CHECK (balance >= 0),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  photo BLOB,
  rating DOUBLE PRECISION,
  is_active BOOLEAN NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS region (code TEXT PRIMARY KEY, label TEXT) WITHOUT ROWID;
CREATE TABLE account_event (
  event_id INTEGER PRIMARY KEY,
  customer_id INTEGER NOT NULL REFERENCES customer(customer_id) ON DELETE CASCADE ON UPDATE NO ACTION,
  region_code TEXT,
  amount REAL,
  payload TEXT,
  CONSTRAINT fk_region FOREIGN KEY (region_code) REFERENCES region(code) DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT chk_amt CHECK (amount <> 0)
);
CREATE TABLE strict_metric (id INTEGER PRIMARY KEY, label TEXT NOT NULL, val REAL, raw ANY) STRICT;
CREATE TABLE strict_kv (k TEXT PRIMARY KEY, v INT) STRICT, WITHOUT ROWID;
CREATE TABLE shape (
  width REAL, height REAL,
  area REAL GENERATED ALWAYS AS (width * height) STORED,
  perimeter REAL AS (2 * (width + height)) VIRTUAL
);
CREATE TABLE line_item (
  order_id INTEGER, line_no INTEGER, sku TEXT,
  PRIMARY KEY (order_id, line_no) ON CONFLICT REPLACE,
  UNIQUE (order_id, sku) ON CONFLICT IGNORE
);
CREATE TEMP TABLE scratch_ids (id INTEGER);
CREATE TEMPORARY TABLE IF NOT EXISTS scratch_names AS SELECT display_name FROM customer WHERE 0;
CREATE TABLE customer_copy AS SELECT * FROM customer;
ALTER TABLE customer ADD COLUMN phone TEXT DEFAULT '';
ALTER TABLE customer RENAME COLUMN phone TO phone_number;
ALTER TABLE customer_copy RENAME TO customer_archive;
ALTER TABLE customer DROP COLUMN phone_number;
DROP TABLE IF EXISTS customer_archive;
DROP TABLE scratch_ids;
CREATE TABLE notypes (a, b, c);
CREATE TABLE weird_types (a UNSIGNED BIG INT, b NVARCHAR(50), c VARYING CHARACTER(20), d NATIVE CHARACTER(10), e CLOB, f DECIMAL(10,5), g INT8);
CREATE TABLE col_default_expr (id INTEGER PRIMARY KEY, created TEXT DEFAULT (datetime('now')), neg INTEGER DEFAULT -1, s TEXT DEFAULT 'x');
