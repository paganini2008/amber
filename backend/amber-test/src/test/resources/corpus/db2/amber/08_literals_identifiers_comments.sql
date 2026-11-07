-- line comment
/* block comment */
/* nested /* block */ comment */
SELECT 'string', 'it''s', '', X'48656C6C6F', x'00ff', G'graphic', N'national', GX'0041', UX'0041', BX'0102', U&'\0041' FROM sysibm.sysdummy1;
SELECT 1, -1, 123456789012, 1.5, .5, 1., 1.5E10, 2E-3, 6.02e23 FROM sysibm.sysdummy1;
SELECT DATE '2024-01-31', TIME '13:45:00', TIMESTAMP '2024-01-31-13.45.00.000000', TIMESTAMP '2024-01-31 13:45:00' FROM sysibm.sysdummy1;
SELECT TRUE, FALSE, NULL FROM sysibm.sysdummy1;
SELECT "a""b", "lower case", "SELECT" FROM "S"."T";
SELECT a -- trailing comment
  , b /* inline */ , c
FROM t; -- after
SELECT name, type, value, status, date, year, month, day, user, data, comment, position, text, size, mode, source, label, role, level, key, index, time, timestamp, description, location, owner, version, sequence FROM app.wide;
SELECT t.year, t.month, t.day, t.hour, t.minute, t.second FROM calendar t;
SELECT a AS year, b AS "DATE", c AS value FROM t;
CREATE TABLE app.keywords_as_names (name VARCHAR(10), type INT, value DECIMAL(5,2), status CHAR(1), date DATE, year SMALLINT, user VARCHAR(8), data BLOB(1M), comment VARCHAR(200), position INT, text CLOB, size BIGINT, start DATE, end_ DATE, mode CHAR(1), active BOOLEAN);
INSERT INTO app.keywords_as_names (name, type, value, status, date, year, user, data, comment, position) VALUES ('n', 1, 2.5, 'A', CURRENT DATE, 2024, USER, NULL, 'c', 1);
UPDATE app.keywords_as_names SET status = 'B', comment = 'x', value = value + 1 WHERE name = 'n' AND type = 1;
SELECT ÄÖÜ_spalte, café FROM données;
