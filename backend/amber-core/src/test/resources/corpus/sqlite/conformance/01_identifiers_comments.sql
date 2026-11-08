-- Identifier quoting variants and comments
/* block comment
   spanning lines */
CREATE TABLE "order items" ("item id" INTEGER PRIMARY KEY, [unit price] REAL, `qty` INTEGER);
SELECT "item id", [unit price], `qty` FROM "order items"; -- trailing comment
SELECT o."item id" AS "Item", o.[unit price] * o.`qty` AS total FROM "order items" AS o;
CREATE TABLE main.widget_log (id INTEGER, note TEXT);
SELECT id, note FROM main.widget_log;
SELECT 'it''s quoted' AS txt, X'0A0B' AS blob_val, 1.5e3 AS sci, 0x1F AS hex_int;
SELECT NULL, TRUE, FALSE, CURRENT_DATE, CURRENT_TIME, CURRENT_TIMESTAMP;
SELECT 1_000_000 AS big_number;
SELECT * FROM widget_log WHERE note = ?;
SELECT * FROM widget_log WHERE id = ?1 OR note = :note OR note = @note OR note = $note;
SELECT "select" FROM (SELECT 1 AS "select");
SELECT abs(-5), typeof(3.0), length('abc'), substr('hello', 2, 3), instr('abc', 'b');
SELECT 'a' || 'b' || 'c', 5 % 3, 7 / 2, ~5, 1 << 3, 16 >> 2, 6 & 3, 6 | 3;
SELECT CAST('12' AS INTEGER), CAST(3.7 AS TEXT), CAST(x'41' AS TEXT);
SELECT note COLLATE NOCASE FROM widget_log ORDER BY note COLLATE NOCASE DESC;
SELECT * FROM widget_log WHERE note GLOB 'a*' OR note LIKE 'b%' ESCAPE '\' OR note REGEXP '^c';
SELECT * FROM widget_log WHERE id IS NOT 3 AND note IS DISTINCT FROM 'x' AND id NOTNULL;
SELECT * FROM widget_log WHERE id ISNULL OR id NOT BETWEEN 1 AND 5;
SELECT CASE WHEN id > 1 THEN 'big' ELSE 'small' END, iif(id > 1, 'y', 'n') FROM widget_log;
SELECT coalesce(note, 'none'), ifnull(note, '-'), nullif(id, 0) FROM widget_log;
