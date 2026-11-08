MERGE dbo.Customers AS tgt
USING staging.Customers AS src ON tgt.customer_id = src.customer_id
WHEN MATCHED AND tgt.name <> src.name THEN UPDATE SET tgt.name = src.name, tgt.updated_at = GETDATE()
WHEN NOT MATCHED BY TARGET THEN INSERT (customer_id, name, created_at) VALUES (src.customer_id, src.name, GETDATE())
WHEN NOT MATCHED BY SOURCE THEN DELETE
OUTPUT $action, inserted.customer_id, deleted.customer_id;
MERGE INTO dbo.Inventory WITH (HOLDLOCK) AS t
USING (SELECT product_id, SUM(qty) AS qty FROM staging.Movements GROUP BY product_id) AS s (product_id, qty)
ON t.product_id = s.product_id
WHEN MATCHED AND t.qty + s.qty = 0 THEN DELETE
WHEN MATCHED THEN UPDATE SET qty = t.qty + s.qty
WHEN NOT MATCHED THEN INSERT VALUES (s.product_id, s.qty);
MERGE TOP (100) dbo.T AS t USING dbo.S AS s ON t.id = s.id WHEN MATCHED THEN UPDATE SET t.v = s.v;
MERGE dbo.T t USING (VALUES (1, 'a'), (2, 'b')) AS s (id, v) ON t.id = s.id
WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v)
OUTPUT $action AS act, inserted.id INTO @changes (act, id);
INSERT INTO dbo.ChangeLog (act, id)
SELECT act, id FROM (
    MERGE dbo.T t USING dbo.S s ON t.id = s.id
    WHEN MATCHED THEN UPDATE SET t.v = s.v
    WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v)
    OUTPUT $action, inserted.id
) AS changes (act, id) WHERE act = 'UPDATE';
MERGE dbo.T AS t USING dbo.S AS s ON (t.id = s.id) WHEN NOT MATCHED BY TARGET THEN INSERT DEFAULT VALUES;
