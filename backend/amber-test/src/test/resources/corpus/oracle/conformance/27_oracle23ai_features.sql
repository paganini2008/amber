-- Oracle conformance: Oracle Database 23ai additions
SELECT 1;
SELECT SYSDATE;
SELECT 1 + 1 AS two;
CREATE TABLE IF NOT EXISTS flags (id NUMBER PRIMARY KEY, active BOOLEAN DEFAULT TRUE);
DROP TABLE IF EXISTS old_flags;
DROP VIEW IF EXISTS v_old;
DROP SEQUENCE IF EXISTS old_seq;
CREATE VIEW IF NOT EXISTS v_flags AS SELECT * FROM flags;
INSERT INTO flags (id, active) VALUES (1, TRUE), (2, FALSE), (3, NULL);
SELECT * FROM flags WHERE active;
SELECT * FROM flags WHERE active IS NOT TRUE;
SELECT id, NOT active AS inactive FROM flags;
SELECT region, COUNT(*) FROM orders GROUP BY region HAVING COUNT(*) > 1;
SELECT EXTRACT(YEAR FROM order_date) AS yr, SUM(amount) FROM orders GROUP BY yr;
SELECT * FROM (VALUES (1, 'a'), (2, 'b')) v (id, label);
WITH v (id, label) AS (VALUES (1, 'a'), (2, 'b')) SELECT * FROM v;
UPDATE orders o SET o.amount = s.new_amount FROM staging_orders s WHERE s.order_id = o.order_id;
DELETE FROM orders o FROM customers c WHERE c.customer_id = o.customer_id AND c.status = 'GONE';
UPDATE orders SET amount = amount * 2 WHERE order_id = 1 RETURNING OLD amount, NEW amount INTO :o, :n;
CREATE DOMAIN email_d AS VARCHAR2(320) CONSTRAINT email_chk CHECK (REGEXP_LIKE(email_d, '^.+@.+$'));
CREATE TABLE contacts (id NUMBER, email VARCHAR2(320) DOMAIN email_d);
CREATE TABLE products_v (id NUMBER, embedding VECTOR(768, FLOAT32));
SELECT id FROM products_v ORDER BY VECTOR_DISTANCE(embedding, :q, COSINE) FETCH FIRST 5 ROWS ONLY;
CREATE JSON RELATIONAL DUALITY VIEW orders_dv AS
  SELECT JSON {'_id' : o.order_id, 'amount' : o.amount} FROM orders o WITH INSERT UPDATE DELETE;
SELECT JSON {'id' : order_id, 'amt' : amount} FROM orders;
CREATE TABLE ann_t (id NUMBER ANNOTATIONS (display 'Identifier')) ANNOTATIONS (owner 'sales');
SELECT * FROM GRAPH_TABLE (social_graph MATCH (a IS person) -[e IS knows]-> (b IS person) COLUMNS (a.name AS src, b.name AS dst));
CREATE TABLE sched (id NUMBER, slot INTERVAL DAY TO SECOND);
SELECT SUM(slot) FROM sched;
SELECT TIME_BUCKET(order_ts, INTERVAL '1' HOUR, TIMESTAMP '2024-01-01 00:00:00') FROM orders;
CREATE TABLE lock_free (id NUMBER PRIMARY KEY, qty NUMBER RESERVABLE CONSTRAINT qty_ck CHECK (qty >= 0));
SELECT CEIL(SYSDATE), FLOOR(SYSTIMESTAMP) FROM dual;
CREATE MLE MODULE util_mod LANGUAGE JAVASCRIPT AS
export function twice(x) { return x * 2; }
/
