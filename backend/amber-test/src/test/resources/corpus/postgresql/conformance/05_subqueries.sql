-- Subqueries
SELECT * FROM sales.orders WHERE customer_id IN (SELECT customer_id FROM sales.customer WHERE vip);
SELECT * FROM sales.orders WHERE customer_id NOT IN (SELECT customer_id FROM sales.blacklist);
SELECT order_id, (SELECT name FROM sales.customer c WHERE c.customer_id = o.customer_id) AS cname FROM sales.orders o;
SELECT * FROM (SELECT customer_id, sum(amount) AS total FROM sales.orders GROUP BY customer_id) t WHERE total > 1000;
SELECT * FROM (SELECT 1) AS sub;
SELECT * FROM (SELECT 1);
SELECT * FROM sales.orders o WHERE amount > (SELECT avg(amount) FROM sales.orders i WHERE i.customer_id = o.customer_id);
SELECT * FROM sales.orders WHERE (customer_id, region_code) IN (SELECT customer_id, region_code FROM sales.preferred);
SELECT * FROM sales.orders WHERE (a, b) = (1, 2);
SELECT * FROM sales.orders WHERE amount >= ALL (SELECT amount FROM sales.orders);
SELECT * FROM sales.orders WHERE amount = SOME (SELECT amount FROM sales.top_orders);
SELECT ARRAY(SELECT order_id FROM sales.orders ORDER BY 1) AS ids;
SELECT EXISTS (SELECT 1 FROM sales.orders) AS has_orders;
SELECT * FROM sales.customer c WHERE EXISTS (SELECT FROM sales.orders o WHERE o.customer_id = c.customer_id);
SELECT (SELECT max(amount) FROM sales.orders) - (SELECT min(amount) FROM sales.orders);
SELECT * FROM sales.orders WHERE customer_id = ANY (SELECT customer_id FROM sales.vip);
