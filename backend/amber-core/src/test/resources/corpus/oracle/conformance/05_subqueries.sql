-- Oracle conformance: subqueries
SELECT * FROM (SELECT order_id, amount FROM orders ORDER BY amount DESC) WHERE ROWNUM <= 5;
SELECT * FROM (SELECT o.*, ROWNUM rn FROM (SELECT * FROM orders ORDER BY order_date) o WHERE ROWNUM <= 20) WHERE rn > 10;
SELECT c.name, (SELECT COUNT(*) FROM orders o WHERE o.customer_id = c.customer_id) AS order_cnt FROM customers c;
SELECT * FROM customers c WHERE EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.customer_id);
SELECT * FROM customers c WHERE NOT EXISTS (SELECT NULL FROM orders o WHERE o.customer_id = c.customer_id);
SELECT * FROM customers WHERE customer_id IN (SELECT customer_id FROM orders WHERE amount > 1000);
SELECT * FROM customers WHERE customer_id NOT IN (SELECT customer_id FROM blacklist WHERE customer_id IS NOT NULL);
SELECT * FROM orders WHERE amount > (SELECT AVG(amount) FROM orders);
SELECT * FROM orders o WHERE amount = (SELECT MAX(amount) FROM orders i WHERE i.customer_id = o.customer_id);
SELECT * FROM (SELECT customer_id, SUM(amount) total FROM orders GROUP BY customer_id) t WHERE t.total > 5000;
SELECT * FROM (SELECT * FROM orders) sub;
SELECT (SELECT MAX(order_date) FROM orders) - (SELECT MIN(order_date) FROM orders) AS span_days FROM dual;
UPDATE orders o SET amount = (SELECT price FROM price_list p WHERE p.product_id = o.product_id) WHERE EXISTS (SELECT 1 FROM price_list p WHERE p.product_id = o.product_id);
DELETE FROM orders WHERE customer_id IN (SELECT customer_id FROM customers WHERE status = 'CLOSED');
SELECT * FROM orders WHERE (customer_id, amount) IN (SELECT customer_id, MAX(amount) FROM orders GROUP BY customer_id);
SELECT * FROM TABLE(SELECT tags FROM documents WHERE doc_id = 1);
SELECT * FROM (SELECT * FROM orders WHERE status = 'OPEN') WHERE amount > 10 ORDER BY order_id;
