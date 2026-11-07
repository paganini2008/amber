-- Warm-up script (synthetic).
CREATE TABLE IF NOT EXISTS stock (sku VARCHAR(20) PRIMARY KEY, qty INT DEFAULT 0);
MERGE INTO stock KEY (sku) VALUES ('W-1', 4);
WITH low AS (SELECT sku FROM stock WHERE qty < 5) UPDATE stock SET qty = qty + 10 WHERE sku IN (SELECT sku FROM low);
SELECT sku, qty, RANK() OVER (ORDER BY qty DESC) AS r FROM stock ORDER BY sku FETCH FIRST 10 ROWS ONLY;
