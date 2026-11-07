-- sqlite3 command-line shell script with dot-commands
.headers on
.mode csv
.bail on
.timer on
CREATE TABLE IF NOT EXISTS import_stage (sku TEXT, qty INTEGER, price REAL);
.import --csv --skip 1 stock.csv import_stage
INSERT INTO line_item (order_id, line_no, sku) SELECT 1, rowid, sku FROM import_stage;
.output report.csv
SELECT sku, sum(qty) FROM import_stage GROUP BY sku;
.output stdout
.schema customer
.tables
.read other_script.sql
.quit
