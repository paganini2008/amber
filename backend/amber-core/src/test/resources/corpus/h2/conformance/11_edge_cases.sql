-- Keywords as identifiers, quoting, comments, casts, misc syntax edge cases
/* block comment */ -- line comment
// H2 also accepts double-slash line comments
CREATE TABLE "Mixed Case Table" ("Id" INT PRIMARY KEY, "select" VARCHAR(10), "Order" INT);
SELECT "Id", "select", "Order" FROM "Mixed Case Table" AS m WHERE m."Id" = 1;
CREATE TABLE settings_kv (name VARCHAR(50), "VALUE" VARCHAR(100), "KEY" VARCHAR(50), data VARCHAR(10), type VARCHAR(10), status VARCHAR(10), code VARCHAR(10), action VARCHAR(10), position INT, comment VARCHAR(100), level INT, path VARCHAR(100), text VARCHAR(10), timestamp_col TIMESTAMP, password VARCHAR(10), role VARCHAR(10), source VARCHAR(10), version INT, owner VARCHAR(10), cache INT);
SELECT name, data, type, status, code, action, position, comment, level, path, text, password, role, source, version, owner, cache FROM settings_kv;
SELECT s.name AS "name", s."VALUE" FROM settings_kv s ORDER BY 1, 2 DESC;
SELECT CAST(position AS VARCHAR(10)), CAST('2024-01-01' AS DATE) FROM settings_kv;
SELECT * FROM settings_kv WHERE name LIKE 'a%' AND NOT (position < 0 OR position IS NULL);
SELECT name FROM settings_kv WHERE position IN (1, 2, 3) AND name NOT IN ('x') AND position NOT BETWEEN 5 AND 10;
SELECT COUNT(DISTINCT name), COUNT(*), EVERY(position > 0), BOOL_AND(position > 0), BIT_OR(position) FROM settings_kv;
SELECT name FROM settings_kv GROUP BY name HAVING SUM(position) > 1 ORDER BY name LIMIT 10;
SELECT * FROM settings_kv s1 INNER JOIN settings_kv s2 ON s1.name = s2.name LEFT JOIN settings_kv s3 ON s3.name = s2.name;
SELECT * FROM settings_kv s1 JOIN (settings_kv s2 JOIN settings_kv s3 ON s2.name = s3.name) ON s1.name = s2.name;
SELECT * FROM settings_kv USE INDEX (idx_none) WHERE name = 'x';
SELECT 1 + 2 * 3 - 4 / 2 % 3, -position, +position, 'a' || 'b' FROM settings_kv;
SELECT name FROM settings_kv WHERE name = ANY (ARRAY['a', 'b']);
SELECT ARRAY_AGG(DISTINCT name) FILTER (WHERE position > 0) FROM settings_kv;
SELECT * FROM settings_kv WHERE (name, position) IN (('a', 1), ('b', 2));
SELECT name FROM settings_kv WHERE EXISTS (TABLE settings_kv);
SELECT * FROM (SELECT name FROM settings_kv UNION ALL SELECT name FROM settings_kv) AS u(n) ORDER BY n FETCH FIRST ROW ONLY;
SELECT CURRENT_DATE, CURRENT_TIME, LOCALTIME, LOCALTIMESTAMP(3), CURRENT_TIMESTAMP(6), CURRENT_CATALOG, CURRENT_ROLE;
SELECT timestamp_col + INTERVAL '1' HOUR, timestamp_col - INTERVAL '30' MINUTE FROM settings_kv;
SELECT name FROM settings_kv WHERE timestamp_col > NOW() - INTERVAL '7' DAY;
INSERT INTO settings_kv (name, "VALUE") VALUES (?, ?);
UPDATE settings_kv SET "VALUE" = DEFAULT, position = position + 1 WHERE name = 'x';
SELECT name FROM settings_kv FOR UPDATE NOWAIT;
SELECT name FROM settings_kv FOR UPDATE SKIP LOCKED;
SELECT name, NULL AS empty_col FROM settings_kv UNION SELECT 'z', 'y' ORDER BY name;
SELECT (SELECT 1) + (SELECT 2);
EXECUTE IMMEDIATE 'DELETE FROM settings_kv WHERE 1 = 0';
