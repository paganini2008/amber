-- MERGE INTO ... KEY and MERGE INTO ... USING
MERGE INTO test KEY (id) VALUES (1, 'Hello');
MERGE INTO test (id, name) KEY (id) VALUES (1, 'Hello'), (2, 'World');
MERGE INTO test (id, name) VALUES (1, 'no key');
MERGE INTO test KEY (id) SELECT id, name FROM staging;
MERGE INTO test (id, name) KEY (id) SELECT id, name FROM staging WHERE valid;
MERGE INTO target t USING source s ON t.id = s.id
    WHEN MATCHED THEN UPDATE SET t.name = s.name
    WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name);
MERGE INTO target AS t USING (SELECT id, name, deleted FROM source) AS s ON (t.id = s.id)
    WHEN MATCHED AND s.deleted THEN DELETE
    WHEN MATCHED AND t.name <> s.name THEN UPDATE SET name = s.name, version = t.version + 1
    WHEN NOT MATCHED AND s.deleted = FALSE THEN INSERT VALUES (s.id, s.name, 1);
MERGE INTO target t USING source s ON t.id = s.id
    WHEN MATCHED THEN UPDATE SET t.value = s.value WHERE t.value <> s.value DELETE WHERE s.value IS NULL;
MERGE INTO target USING DUAL ON target.id = 1
    WHEN NOT MATCHED THEN INSERT (id) VALUES (1);
MERGE INTO target t USING (VALUES (1, 'a'), (2, 'b')) s(id, name) ON t.id = s.id
    WHEN NOT MATCHED BY TARGET THEN INSERT (id, name) VALUES (s.id, s.name);
MERGE INTO target t USING source s ON t.id = s.id
    WHEN NOT MATCHED THEN INSERT (id, name) OVERRIDING SYSTEM VALUE VALUES (s.id, s.name);
