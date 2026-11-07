-- Identifiers, comments, literals, operators (MariaDB)
# hash comment
SELECT 1; -- dash comment
/* block */ SELECT `col one` FROM `my db`.`my table`;
/*M!100100 SELECT 2 */;
SELECT 'single', "double", 'it''s', 'it\'s', N'nat', X'41', 0x41, b'101', _utf8mb4'x';
SELECT 1.5e3, .25, -7, TRUE, FALSE, NULL;
SELECT DATE '2024-01-01', TIME '12:00:00', TIMESTAMP '2024-01-01 12:00:00';
SELECT @v := 5, @@sql_mode, @@global.max_connections, @@session.autocommit;
SET @a = 1, @b := 2;
SET SESSION sql_mode = 'STRICT_ALL_TABLES';
SET STATEMENT max_statement_time = 10 FOR SELECT * FROM big_table;
SET NAMES utf8mb4 COLLATE utf8mb4_general_ci;
SELECT 7 DIV 2, 7 MOD 2, 1 <=> NULL, 'abc' REGEXP '^a', 'abc' RLIKE 'c$', NOT 0, 1 XOR 1;
SELECT CAST('5' AS INTEGER), CAST(1 AS DOUBLE), CAST('2024-01-01' AS DATE), CONVERT('a' USING utf8mb4), CAST(x AS CHAR(10)) FROM t;
SELECT INTERVAL 1 DAY + CURDATE(), DATE_ADD(NOW(), INTERVAL 3 HOUR);
SELECT CASE WHEN a > 1 THEN 'x' ELSE 'y' END, IF(a, 1, 0), IFNULL(a, 0), NVL(a, 0), NVL2(a, 1, 0), DECODE_ORACLE(a, 1, 'one', 'other') FROM t;
SELECT CHR(65), SUBSTR('abc', 2), LPAD('5', 3, '0'), RPAD('x', 3, '-'), INSTR('abc', 'b'), REPLACE('aa', 'a', 'b'), REGEXP_REPLACE('abc', 'b', 'x'), SFORMAT('{} {}', 'a', 'b');
SELECT UUID(), SYS_GUID(), NATURAL_SORT_KEY('a10');
SELECT 1 FROM DUAL WHERE 1 = 1;
SELECT a FROM t WHERE a LIKE 'x%' ESCAPE '\\';
SELECT a FROM t ORDER BY a LIMIT 10 OFFSET 5;
SELECT a FROM t ORDER BY a LIMIT 5, 10;
SELECT a FROM t ORDER BY a OFFSET 5 ROWS FETCH FIRST 10 ROWS ONLY;
SELECT a FROM t ORDER BY a FETCH FIRST 3 ROWS WITH TIES;
SELECT a FROM t LIMIT 10 ROWS EXAMINED 1000;
SELECT * FROM t1 JOIN t2 USING (id) LEFT JOIN t3 ON t3.id = t1.id STRAIGHT_JOIN t4 ON t4.id = t1.id;
SELECT * FROM t1 WHERE id IN (SELECT id FROM t2) AND EXISTS (SELECT 1 FROM t3 WHERE t3.id = t1.id);
SELECT * FROM t FOR UPDATE WAIT 5;
SELECT * FROM t FOR UPDATE SKIP LOCKED;
SELECT * FROM t LOCK IN SHARE MODE NOWAIT;
SELECT a INTO @x FROM t LIMIT 1;
SELECT * FROM t1 USE INDEX (ix_a) IGNORE INDEX FOR ORDER BY (ix_b);
