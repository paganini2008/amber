-- Identifiers, quoting and comments
# hash comment at line start
SELECT 1; # trailing hash comment
/* block comment */ SELECT 2;
/*!80000 SELECT 3 */;
SELECT `order`.`id`, `order`.`total amount` FROM `shop`.`order` AS `order`;
SELECT o.id AS "Order Id" FROM shop_orders o;
SELECT id AS 'label' FROM shop_orders;
SELECT t1.col_a FROM db_one.tbl_one t1;
SELECT `weird``name` FROM `tbl-with-dash`;
SELECT 1 -- dash comment needs a space
;
SELECT _utf8mb4'abc' COLLATE utf8mb4_bin;
SELECT N'national text', X'4D7953514C', 0x4D7953514C, b'1010', 0b1010;
SELECT 'it''s', 'it\'s', "double quoted string";
SELECT 1e3, -2.5E-3, .5, 10.0;
SELECT DATE '2024-01-31', TIME '10:00:00', TIMESTAMP '2024-01-31 10:00:00';
SELECT TRUE, FALSE, NULL, UNKNOWN IS NULL;
SELECT @user_var, @@session.sql_mode, @@GLOBAL.max_connections, @@sql_mode;
SET @counter := 10;
SET @a = 1, @b = 'x';
SELECT @counter := @counter + 1;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET CHARACTER SET utf8mb4;
SET SESSION sql_mode = 'STRICT_TRANS_TABLES,NO_ZERO_DATE';
SET GLOBAL max_connections = 500;
SET PERSIST max_connections = 500;
SET @@SESSION.autocommit = 0;
SELECT 5 DIV 2, 5 MOD 2, 5 % 2, 1 XOR 0, NOT 1, !0, 1 && 1, 0 || 1;
SELECT 'abc' REGEXP '^a', 'abc' RLIKE 'b', 'abc' NOT REGEXP 'z';
SELECT 1 <=> NULL, 3 BETWEEN 1 AND 5, 'a' SOUNDS LIKE 'a';
SELECT BINARY 'a' = 'A';
SELECT ~5, 5 << 2, 5 >> 1, 5 & 3, 5 | 3, 5 ^ 3;
SELECT CAST('2024-01-01' AS DATE), CAST(12 AS CHAR(10)), CAST('1' AS UNSIGNED), CAST(1.5 AS DECIMAL(10,2)), CONVERT('abc' USING utf8mb4), CONVERT(12, SIGNED);
SELECT INTERVAL 1 DAY + DATE '2024-01-01', DATE_ADD(NOW(), INTERVAL 2 HOUR), DATE_SUB(CURDATE(), INTERVAL '1-2' YEAR_MONTH);
SELECT EXTRACT(YEAR FROM NOW()), TIMESTAMPDIFF(MINUTE, '2024-01-01', NOW());
SELECT CURRENT_TIMESTAMP, CURRENT_DATE(), LOCALTIME, UTC_TIMESTAMP();
