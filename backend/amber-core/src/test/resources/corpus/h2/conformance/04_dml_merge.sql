-- INSERT, UPDATE, DELETE, MERGE (standard and H2 MERGE INTO ... KEY), data change delta tables
INSERT INTO sales.customer (display_name, email) VALUES ('Ann Lee', 'ann@example.com');
INSERT INTO sales.customer (display_name, email) VALUES ('Bo Chen', 'bo@example.com'), ('Cy Diaz', 'cy@example.com');
INSERT INTO sales.customer SET display_name = 'Di Evans', email = 'di@example.com';
INSERT INTO sales.customer (display_name, email) SELECT display_name, email FROM sales.customer WHERE customer_id < 0;
INSERT INTO audit.lookup_cache DEFAULT VALUES;
INSERT INTO sales.product (sku, price, tags, attrs) VALUES ('SKU-1', 9.99, ARRAY['red', 'small'], JSON '{"color":"red"}');
INSERT INTO sales.product (sku, price, attrs) VALUES ('SKU-2', 19.5, '{"size":1}' FORMAT JSON);
INSERT INTO sales.product (sku, price) OVERRIDING SYSTEM VALUE VALUES ('SKU-3', 1);
UPDATE sales.customer SET balance = balance + 10, is_active = TRUE WHERE customer_id = 1;
UPDATE sales.customer c SET balance = (SELECT COALESCE(SUM(total), 0) FROM sales.order_header o WHERE o.customer_id = c.customer_id);
UPDATE sales.customer SET (display_name, email) = ('Ann L.', 'ann.l@example.com') WHERE customer_id = 1;
UPDATE sales.order_header SET status = 'SHIPPED' WHERE status = 'PAID' LIMIT 100;
DELETE FROM sales.order_line WHERE qty = 0;
DELETE FROM sales.order_header WHERE status = 'NEW' AND ordered_at < CURRENT_DATE - INTERVAL '30' DAY;
DELETE FROM audit.event_log LIMIT 5;
MERGE INTO audit.lookup_cache KEY (k) VALUES ('currency', 'EUR');
MERGE INTO audit.lookup_cache (k, v) KEY (k) VALUES ('lang', 'en'), ('tz', 'UTC');
MERGE INTO audit.lookup_cache (k, v) KEY (k) SELECT 'cnt', CAST(COUNT(*) AS VARCHAR) FROM sales.customer;
MERGE INTO audit.lookup_cache t USING (SELECT 'currency' AS k, 'USD' AS v) s ON t.k = s.k
  WHEN MATCHED THEN UPDATE SET t.v = s.v
  WHEN NOT MATCHED THEN INSERT (k, v) VALUES (s.k, s.v);
MERGE INTO sales.customer AS t
USING (SELECT email, display_name FROM tmp_import) AS s
ON t.email = s.email
WHEN MATCHED AND s.display_name IS NULL THEN DELETE
WHEN MATCHED THEN UPDATE SET display_name = s.display_name
WHEN NOT MATCHED AND s.email IS NOT NULL THEN INSERT (display_name, email) VALUES (s.display_name, s.email);
SELECT * FROM FINAL TABLE (INSERT INTO sales.customer (display_name) VALUES ('Eve'));
SELECT customer_id FROM NEW TABLE (UPDATE sales.customer SET balance = 0 WHERE customer_id = 2);
SELECT * FROM OLD TABLE (DELETE FROM audit.event_log WHERE id < 10);
