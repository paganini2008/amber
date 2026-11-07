-- Warm-up script (synthetic).
CREATE TABLE IF NOT EXISTS note (id INTEGER PRIMARY KEY, body TEXT, updated_at TEXT);
INSERT INTO note (body, updated_at) SELECT body, datetime('now') FROM note_import WHERE body IS NOT NULL;
CREATE TRIGGER IF NOT EXISTS note_touch AFTER UPDATE ON note
BEGIN
  UPDATE note SET updated_at = datetime('now') WHERE id = new.id;
END;
DELETE FROM note WHERE body = '';
