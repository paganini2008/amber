-- Oracle conformance: MERGE
MERGE INTO customers t
USING staging_customers s
ON (t.customer_id = s.customer_id)
WHEN MATCHED THEN UPDATE SET t.name = s.name, t.region = s.region
WHEN NOT MATCHED THEN INSERT (customer_id, name, region) VALUES (s.customer_id, s.name, s.region);
MERGE INTO customers t
USING (SELECT customer_id, name FROM staging_customers WHERE load_id = 7) s
ON (t.customer_id = s.customer_id)
WHEN MATCHED THEN UPDATE SET t.name = s.name WHERE t.name <> s.name
DELETE WHERE s.name IS NULL;
MERGE INTO customers t
USING staging_customers s ON (t.customer_id = s.customer_id)
WHEN NOT MATCHED THEN INSERT (customer_id, name) VALUES (s.customer_id, s.name) WHERE s.region IS NOT NULL;
MERGE /*+ APPEND */ INTO sales_fact f
USING (SELECT product_id, SUM(qty) qty FROM sales_stage GROUP BY product_id) s
ON (f.product_id = s.product_id AND f.sale_day = TRUNC(SYSDATE))
WHEN MATCHED THEN UPDATE SET f.qty = f.qty + s.qty
WHEN NOT MATCHED THEN INSERT VALUES (s.product_id, TRUNC(SYSDATE), s.qty);
MERGE INTO customers t
USING dual ON (t.customer_id = 42)
WHEN MATCHED THEN UPDATE SET t.last_seen = SYSDATE
WHEN NOT MATCHED THEN INSERT (customer_id, last_seen) VALUES (42, SYSDATE);
MERGE INTO inventory i
USING (SELECT :sku AS sku, :qty AS qty FROM dual) s
ON (i.sku = s.sku)
WHEN MATCHED THEN UPDATE SET i.qty = i.qty + s.qty
WHEN NOT MATCHED THEN INSERT (sku, qty) VALUES (s.sku, s.qty)
LOG ERRORS INTO err$_inventory REJECT LIMIT UNLIMITED;
MERGE INTO customers t
USING (WITH x AS (SELECT * FROM staging_customers) SELECT * FROM x) s
ON (t.customer_id = s.customer_id)
WHEN MATCHED THEN UPDATE SET t.name = s.name;
