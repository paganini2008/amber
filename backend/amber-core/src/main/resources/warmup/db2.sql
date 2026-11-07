-- Warm-up script (synthetic).
CREATE TABLE app.ledger (id INTEGER NOT NULL PRIMARY KEY, account VARCHAR(20), amount DECIMAL(15,2));
INSERT INTO app.ledger (id, account, amount) SELECT id, account, amount FROM app.ledger_stage WHERE amount <> 0;
MERGE INTO app.balance b USING (SELECT account, SUM(amount) AS total FROM app.ledger GROUP BY account) s
   ON b.account = s.account
 WHEN MATCHED THEN UPDATE SET b.total = s.total
 WHEN NOT MATCHED THEN INSERT (account, total) VALUES (s.account, s.total);
DELETE FROM app.ledger_stage;
