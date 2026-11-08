-- Identifiers, quoting and comments
/* block comment
   /* nested block comment */ still comment */
SELECT 1 AS one;
SELECT "Mixed Case Col" FROM "Sales Data"."Order Lines";
SELECT u&"d\0061ta" FROM inventory.stock_item;
SELECT U&"\0441\043B\043E\043D" FROM catalog.product_item;
CREATE TABLE "select" ("from" integer, "where" text);
SELECT "from", "where" FROM "select";
SELECT s.item_id, s."Qty" FROM warehouse.stock AS s;
SELECT a.b.c FROM a.b;
SELECT mydb.sales.orders.order_id FROM mydb.sales.orders;
SELECT t.* FROM sales.orders t;
SELECT (t).order_id FROM sales.orders t;
SELECT order_id -- trailing comment
FROM sales.orders; -- end comment
SELECT 1 /* inline */ + /* another */ 2;
SELECT _private_col, col$1 FROM tbl_with_dollar;
SELECT "a""b" FROM quoted_quotes;
SELECT current_user, session_user, current_schema, current_catalog;
SELECT name FROM pg_catalog.pg_settings WHERE name = 'work_mem';
DROP TABLE "select";
