-- MERGE (PG 15+, RETURNING and NOT MATCHED BY SOURCE in 17)
MERGE INTO sales.customer c USING staging.customer s ON c.customer_id = s.customer_id
WHEN MATCHED THEN UPDATE SET name = s.name
WHEN NOT MATCHED THEN INSERT (customer_id, name) VALUES (s.customer_id, s.name);
MERGE INTO sales.customer AS c USING (SELECT * FROM staging.customer WHERE batch_id = 7) AS s ON c.customer_id = s.customer_id
WHEN MATCHED AND s.deleted THEN DELETE
WHEN MATCHED AND c.name <> s.name THEN UPDATE SET name = s.name, updated_at = now()
WHEN MATCHED THEN DO NOTHING
WHEN NOT MATCHED AND NOT s.deleted THEN INSERT VALUES (s.customer_id, s.name, DEFAULT, NULL)
WHEN NOT MATCHED THEN DO NOTHING;
MERGE INTO sales.customer c USING staging.customer s ON c.customer_id = s.customer_id
WHEN NOT MATCHED THEN INSERT DEFAULT VALUES;
MERGE INTO sales.stock t USING (VALUES (1, 5), (2, 10)) AS v(item_id, qty) ON t.item_id = v.item_id
WHEN MATCHED THEN UPDATE SET qty = t.qty + v.qty
WHEN NOT MATCHED THEN INSERT (item_id, qty) OVERRIDING SYSTEM VALUE VALUES (v.item_id, v.qty);
MERGE INTO sales.customer c USING staging.customer s ON c.customer_id = s.customer_id
WHEN MATCHED THEN UPDATE SET name = s.name
WHEN NOT MATCHED BY TARGET THEN INSERT (customer_id, name) VALUES (s.customer_id, s.name)
WHEN NOT MATCHED BY SOURCE THEN DELETE;
MERGE INTO sales.customer c USING staging.customer s ON c.customer_id = s.customer_id
WHEN MATCHED THEN UPDATE SET name = s.name
WHEN NOT MATCHED THEN INSERT (customer_id, name) VALUES (s.customer_id, s.name)
RETURNING merge_action(), c.*;
WITH src AS (SELECT * FROM staging.customer) MERGE INTO sales.customer c USING src ON c.customer_id = src.customer_id WHEN MATCHED THEN UPDATE SET name = src.name;
MERGE INTO ONLY sales.customer c USING staging.customer s ON c.customer_id = s.customer_id WHEN MATCHED THEN UPDATE SET (name, region) = (s.name, s.region);
