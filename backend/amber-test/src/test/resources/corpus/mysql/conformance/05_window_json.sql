-- Window functions and JSON
SELECT order_id, customer_id, line_total, ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY order_date DESC) AS rn FROM orders;
SELECT order_id, RANK() OVER w, DENSE_RANK() OVER w, PERCENT_RANK() OVER w, CUME_DIST() OVER w, NTILE(4) OVER w FROM orders WINDOW w AS (ORDER BY line_total DESC);
SELECT order_id, SUM(line_total) OVER (PARTITION BY customer_id ORDER BY order_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) running FROM orders;
SELECT order_id, AVG(line_total) OVER (ORDER BY order_date RANGE BETWEEN INTERVAL 7 DAY PRECEDING AND CURRENT ROW) FROM orders;
SELECT order_id, LAG(line_total, 1, 0) OVER (ORDER BY order_date), LEAD(line_total) OVER (ORDER BY order_date) FROM orders;
SELECT order_id, FIRST_VALUE(line_total) OVER w, LAST_VALUE(line_total) OVER (w ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING), NTH_VALUE(line_total, 2) FROM FIRST OVER w FROM orders WINDOW w AS (PARTITION BY customer_id ORDER BY order_date);
SELECT order_id, COUNT(*) OVER () total_rows FROM orders;
SELECT order_id, SUM(qty) OVER (ORDER BY order_id ROWS 2 PRECEDING) FROM orders;
SELECT * FROM (SELECT o.*, ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY line_total DESC) rn FROM orders o) ranked WHERE rn <= 3;
SELECT order_id, LAG(qty) RESPECT NULLS OVER (ORDER BY order_id) FROM orders;
SELECT JSON_EXTRACT(prefs, '$.theme'), prefs->'$.lang', prefs->>'$.lang' FROM customers;
SELECT JSON_OBJECT('id', customer_id, 'email', email), JSON_ARRAY(1, 'two', NULL) FROM customers;
SELECT JSON_SET(prefs, '$.theme', 'dark'), JSON_INSERT(prefs, '$.new', 1), JSON_REPLACE(prefs, '$.lang', 'fr'), JSON_REMOVE(prefs, '$.old') FROM customers;
SELECT JSON_CONTAINS(prefs, '"dark"', '$.theme'), JSON_CONTAINS_PATH(prefs, 'one', '$.a', '$.b'), JSON_KEYS(prefs), JSON_LENGTH(prefs), JSON_TYPE(prefs), JSON_VALID(prefs) FROM customers;
SELECT JSON_VALUE(prefs, '$.age' RETURNING UNSIGNED DEFAULT 0 ON EMPTY) FROM customers;
SELECT * FROM customers WHERE 'vip' MEMBER OF (prefs->'$.tags');
SELECT JSON_OVERLAPS(prefs->'$.tags', '["a","b"]') FROM customers;
SELECT c.customer_id, jt.* FROM customers c, JSON_TABLE(c.prefs, '$.addresses[*]' COLUMNS (
  rowid FOR ORDINALITY,
  city VARCHAR(50) PATH '$.city' DEFAULT '"unknown"' ON EMPTY NULL ON ERROR,
  zip VARCHAR(10) PATH '$.zip',
  has_geo INT EXISTS PATH '$.geo',
  NESTED PATH '$.phones[*]' COLUMNS (phone VARCHAR(20) PATH '$')
)) AS jt;
SELECT JSON_PRETTY(prefs), JSON_STORAGE_SIZE(prefs), JSON_QUOTE('x'), JSON_UNQUOTE('"x"'), JSON_MERGE_PATCH('{}', prefs), JSON_MERGE_PRESERVE('[1]', '[2]') FROM customers;
SELECT JSON_SCHEMA_VALID('{"type":"object"}', prefs) FROM customers;
UPDATE customers SET prefs = JSON_SET(prefs, '$.visits', COALESCE(prefs->>'$.visits', 0) + 1) WHERE customer_id = 1;
SELECT REGEXP_REPLACE(email, '@.*$', ''), REGEXP_SUBSTR(email, '[a-z]+'), REGEXP_LIKE(email, '^a', 'i') FROM customers;
