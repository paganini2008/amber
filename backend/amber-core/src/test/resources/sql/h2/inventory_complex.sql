-- Inventory rebuild (synthetic test script for Amber).
CREATE LOCAL TEMPORARY TABLE tmp_stock (sku VARCHAR(20), qty INT);
INSERT INTO tmp_stock (sku, qty)
WITH moves AS (SELECT m.sku, SUM(m.delta) AS qty FROM inv.movement m GROUP BY m.sku)
SELECT mv.sku, mv.qty FROM moves mv JOIN inv.product p ON p.sku = mv.sku;
CREATE INDEX idx_tmp_stock ON tmp_stock (sku);
MERGE INTO inv.stock s USING tmp_stock t ON s.sku = t.sku
  WHEN MATCHED THEN UPDATE SET s.qty = t.qty
  WHEN NOT MATCHED THEN INSERT (sku, qty) VALUES (t.sku, t.qty);
UPDATE inv.product p SET p.in_stock = (p.sku IN (SELECT sku FROM tmp_stock WHERE qty > 0));
CREATE TABLE inv.stock_snapshot AS SELECT * FROM inv.stock WITH DATA;
CREATE SEQUENCE IF NOT EXISTS inv.snapshot_seq;
DROP TABLE tmp_stock;
