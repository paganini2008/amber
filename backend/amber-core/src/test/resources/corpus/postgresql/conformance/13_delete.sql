-- DELETE and TRUNCATE
DELETE FROM sales.orders WHERE order_id = 1;
DELETE FROM sales.orders;
DELETE FROM sales.orders o USING sales.customer c WHERE c.customer_id = o.customer_id AND c.deleted;
DELETE FROM sales.orders AS o USING sales.customer c, sales.region r WHERE c.customer_id = o.customer_id AND r.code = c.region_code AND r.closed;
DELETE FROM sales.orders WHERE order_id IN (SELECT order_id FROM sales.orders WHERE amount = 0 LIMIT 100) RETURNING order_id;
DELETE FROM ONLY sales.orders WHERE false;
DELETE FROM sales.orders WHERE CURRENT OF c1;
DELETE FROM sales.orders a USING sales.orders b WHERE a.ctid < b.ctid AND a.order_ref = b.order_ref;
TRUNCATE sales.orders;
TRUNCATE TABLE sales.orders, sales.order_line RESTART IDENTITY CASCADE;
TRUNCATE ONLY sales.orders CONTINUE IDENTITY RESTRICT;
