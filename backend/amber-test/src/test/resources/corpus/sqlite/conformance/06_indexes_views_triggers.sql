-- Indexes, views, triggers
CREATE INDEX idx_customer_email ON customer (email);
CREATE UNIQUE INDEX IF NOT EXISTS ux_customer_name ON customer (display_name COLLATE NOCASE DESC);
CREATE INDEX idx_event_active ON account_event (customer_id, amount) WHERE amount > 0;
CREATE INDEX idx_customer_lower ON customer (lower(email));
CREATE INDEX idx_json_kind ON account_event (json_extract(payload, '$.kind'));
DROP INDEX IF EXISTS idx_customer_lower;
REINDEX customer;
REINDEX;
CREATE VIEW v_customer_total AS SELECT c.customer_id, c.display_name, sum(e.amount) AS total FROM customer c LEFT JOIN account_event e ON e.customer_id = c.customer_id GROUP BY c.customer_id;
CREATE VIEW IF NOT EXISTS v_region_names (region, name) AS SELECT code, label FROM region;
CREATE TEMP VIEW v_tmp AS SELECT * FROM customer WHERE is_active;
DROP VIEW IF EXISTS v_tmp;
CREATE TRIGGER trg_customer_ins AFTER INSERT ON customer
BEGIN
  INSERT INTO account_event (customer_id, amount) VALUES (NEW.customer_id, 0.01);
END;
CREATE TRIGGER IF NOT EXISTS trg_balance_check BEFORE UPDATE OF balance ON customer
FOR EACH ROW WHEN NEW.balance < 0
BEGIN
  SELECT RAISE(ABORT, 'negative balance');
END;
CREATE TRIGGER trg_view_ins INSTEAD OF INSERT ON v_region_names
BEGIN
  INSERT INTO region (code, label) VALUES (NEW.region, NEW.name);
END;
CREATE TEMP TRIGGER trg_audit_del AFTER DELETE ON account_event
WHEN OLD.amount > 1000
BEGIN
  INSERT INTO widget_log (id, note) VALUES (OLD.event_id, 'big delete');
  UPDATE customer SET balance = balance - OLD.amount WHERE customer_id = OLD.customer_id;
  DELETE FROM scratch_names WHERE display_name = 'x';
END;
CREATE TRIGGER trg_upsert_in_trigger AFTER UPDATE ON region
BEGIN
  INSERT INTO strict_kv (k, v) VALUES (NEW.code, 1) ON CONFLICT(k) DO UPDATE SET v = v + 1;
  SELECT CASE WHEN NEW.label IS NULL THEN RAISE(IGNORE) END;
  SELECT RAISE(FAIL, 'x') WHERE NEW.code = 'ZZ';
  SELECT RAISE(ROLLBACK, 'y') WHERE NEW.code = 'YY';
END;
DROP TRIGGER IF EXISTS trg_audit_del;
