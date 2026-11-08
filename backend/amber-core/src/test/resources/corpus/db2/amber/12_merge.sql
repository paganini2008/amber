MERGE INTO archive ar
USING (SELECT activity, description FROM activities) ac
ON (ar.activity = ac.activity)
WHEN MATCHED THEN UPDATE SET description = ac.description
WHEN NOT MATCHED THEN INSERT (activity, description) VALUES (ac.activity, ac.description);
MERGE INTO inventory AS i
USING shipment AS s
ON i.partno = s.partno
WHEN MATCHED AND i.qty + s.qty > 1000 THEN SIGNAL SQLSTATE '70001' SET MESSAGE_TEXT = 'Too many parts'
WHEN MATCHED AND s.qty = 0 THEN DELETE
WHEN MATCHED THEN UPDATE SET i.qty = i.qty + s.qty, i.updated = CURRENT TIMESTAMP
WHEN NOT MATCHED AND s.qty > 0 THEN INSERT (partno, qty) VALUES (s.partno, s.qty)
WHEN NOT MATCHED THEN SIGNAL SQLSTATE '70002'
ELSE IGNORE;
MERGE INTO t USING (VALUES (1, 'a'), (2, 'b')) AS src (id, val) ON t.id = src.id
WHEN MATCHED THEN UPDATE SET (val, changed) = (src.val, CURRENT TIMESTAMP)
WHEN NOT MATCHED THEN INSERT VALUES (src.id, src.val, CURRENT TIMESTAMP);
MERGE INTO app.dim_customer d
USING (SELECT c.id, c.name FROM staging.customer c JOIN staging.batch b ON b.id = c.batch_id WHERE b.status = 'READY') s
ON d.customer_id = s.id
WHEN MATCHED AND d.name <> s.name THEN UPDATE SET d.name = s.name
WHEN NOT MATCHED THEN INSERT (customer_id, name) VALUES (s.id, s.name)
WITH RS;
MERGE INTO t USING u ON t.k = u.k WHEN MATCHED THEN DELETE;
