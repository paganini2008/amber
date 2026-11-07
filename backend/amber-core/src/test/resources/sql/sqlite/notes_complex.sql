-- Notes app maintenance (synthetic test script for Amber).
.mode column
BEGIN TRANSACTION;
CREATE TEMP TABLE tmp_active AS
WITH recent AS (SELECT n.note_id, n.owner_id FROM main.note n WHERE n.updated_at > date('now', '-30 day'))
SELECT r.note_id, r.owner_id, u.email FROM recent r JOIN main.app_user u ON u.user_id = r.owner_id;
CREATE INDEX IF NOT EXISTS idx_tmp_active_owner ON tmp_active (owner_id);
UPDATE main.app_user SET last_seen = datetime('now') WHERE user_id IN (SELECT owner_id FROM tmp_active);
INSERT INTO main.digest (owner_id, notes) SELECT owner_id, count(*) FROM tmp_active GROUP BY owner_id;
DELETE FROM main.note_draft WHERE note_id NOT IN (SELECT t.note_id FROM tmp_active t);
CREATE TRIGGER IF NOT EXISTS note_audit AFTER UPDATE ON note
BEGIN
  INSERT INTO main.note_history (note_id, changed_at) VALUES (new.note_id, datetime('now'));
END;
CREATE VIEW IF NOT EXISTS main.v_digest AS SELECT d.owner_id, d.notes FROM main.digest d;
COMMIT;
DROP TABLE tmp_active;
