-- Data types and CREATE TABLE
CREATE DATABASE IF NOT EXISTS sales_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE SCHEMA IF NOT EXISTS archive_db DEFAULT CHARSET = latin1;
USE sales_db;
CREATE TABLE customers (
  customer_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
  email VARCHAR(255) NOT NULL,
  display_name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  status ENUM('active','inactive','banned') NOT NULL DEFAULT 'active',
  flags SET('vip','newsletter') DEFAULT '',
  balance DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  score DOUBLE,
  ratio FLOAT(7,4),
  tiny_flag TINYINT(1) DEFAULT 0,
  small_num SMALLINT, med_num MEDIUMINT ZEROFILL, big_num BIGINT SIGNED,
  is_ok BOOLEAN, bitmask BIT(8),
  birth DATE, last_seen DATETIME(6), wake TIME, yr YEAR,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  notes TEXT, bio MEDIUMTEXT, log_data LONGTEXT, tiny TINYTEXT,
  avatar BLOB, raw_bytes VARBINARY(64), fixed_bin BINARY(16),
  prefs JSON, location POINT, area POLYGON SRID 4326,
  PRIMARY KEY (customer_id),
  UNIQUE KEY uq_customers_email (email),
  KEY idx_status_created (status, created_at DESC),
  FULLTEXT KEY ft_notes (notes),
  CONSTRAINT chk_balance CHECK (balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='customer master' AUTO_INCREMENT=1000 ROW_FORMAT=DYNAMIC;
CREATE TABLE orders (
  order_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  customer_id INT UNSIGNED NOT NULL,
  order_date DATE NOT NULL,
  qty INT NOT NULL CHECK (qty > 0),
  unit_price DECIMAL(10,2) NOT NULL,
  line_total DECIMAL(12,2) GENERATED ALWAYS AS (qty * unit_price) STORED,
  order_year INT AS (YEAR(order_date)) VIRTUAL,
  secret_col VARCHAR(20) INVISIBLE,
  doc JSON DEFAULT (JSON_OBJECT()),
  uuid_bin BINARY(16) DEFAULT (UUID_TO_BIN(UUID())),
  INDEX idx_cust (customer_id),
  INDEX idx_expr ((CAST(doc->>'$.ref' AS CHAR(30)) COLLATE utf8mb4_bin)),
  CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (customer_id) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT chk_price CHECK (unit_price >= 0) NOT ENFORCED
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS orders_copy LIKE orders;
CREATE TABLE order_summary AS SELECT customer_id, SUM(line_total) AS total FROM orders GROUP BY customer_id;
CREATE TABLE order_summary2 (PRIMARY KEY (customer_id)) SELECT customer_id, COUNT(*) cnt FROM orders GROUP BY customer_id;
CREATE TEMPORARY TABLE tmp_ids (id INT PRIMARY KEY) ENGINE=MEMORY;
CREATE TEMPORARY TABLE IF NOT EXISTS tmp_orders SELECT * FROM orders WHERE order_date > '2024-01-01';
DROP TEMPORARY TABLE IF EXISTS tmp_ids;
ALTER TABLE customers ADD COLUMN phone VARCHAR(30) NULL AFTER email;
ALTER TABLE customers ADD COLUMN region CHAR(2) FIRST, ADD INDEX idx_region (region);
ALTER TABLE customers MODIFY COLUMN phone VARCHAR(40) NOT NULL DEFAULT '';
ALTER TABLE customers CHANGE COLUMN phone phone_number VARCHAR(40);
ALTER TABLE customers RENAME COLUMN phone_number TO phone;
ALTER TABLE customers ALTER COLUMN status SET DEFAULT 'inactive';
ALTER TABLE customers ALTER COLUMN status DROP DEFAULT;
ALTER TABLE customers DROP COLUMN phone, DROP INDEX idx_region;
ALTER TABLE customers ADD CONSTRAINT chk_score CHECK (score BETWEEN 0 AND 100);
ALTER TABLE customers DROP CHECK chk_score;
ALTER TABLE orders DROP FOREIGN KEY fk_orders_customer;
ALTER TABLE orders ADD CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (customer_id);
ALTER TABLE orders ALTER COLUMN secret_col SET VISIBLE;
ALTER TABLE orders ALTER INDEX idx_cust INVISIBLE;
ALTER TABLE orders RENAME INDEX idx_cust TO idx_customer;
ALTER TABLE orders ENGINE = InnoDB, ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE orders_copy RENAME TO orders_backup;
RENAME TABLE orders_backup TO orders_old, order_summary TO order_summary_old;
TRUNCATE TABLE orders_old;
DROP TABLE IF EXISTS orders_old, order_summary_old, order_summary2 CASCADE;
DROP DATABASE IF EXISTS archive_db;
