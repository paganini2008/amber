-- Oracle conformance: identifiers, quoting, comments
-- single-line comment
/* block comment */
/* multi-line
   block comment */
SELECT order_id FROM orders;
SELECT "order_id" FROM "orders";
SELECT "Order Id", "select" FROM "Mixed Case Table";
SELECT o.order_id FROM sales.orders o;
SELECT o.order_id FROM "SALES"."ORDERS" o;
SELECT order_id FROM sales.orders@remote_link;
SELECT order_id FROM orders@remote_link.example.com;
SELECT e.emp$id, e.dept#no, e.cost_ctr_1 FROM hr.emp_data e;
SELECT order_id -- trailing comment
  FROM orders /* inline */ WHERE status = 'OPEN';
SELECT /*+ FULL(o) PARALLEL(o, 4) */ order_id FROM orders o;
SELECT --+ INDEX(o orders_pk)
  order_id FROM orders o;
SELECT ROWID, ROWNUM, order_id FROM orders WHERE ROWNUM <= 10;
SELECT o.ROWID FROM orders o;
SELECT USER, SYSDATE, SYSTIMESTAMP, CURRENT_DATE, LOCALTIMESTAMP FROM dual;
SELECT level_code, comment_text, date_value, type FROM misc_attributes;
SELECT name, value, status, data, result FROM config_items;
SELECT key FROM config_items;
SELECT t.timestamp, t.language, t.year FROM kv_store t;
SELECT a.* FROM accounts a;
SELECT accounts.* , 1 AS one FROM accounts;
REM this is a SQL*Plus remark
SELECT 1 FROM dual;
