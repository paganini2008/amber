-- UPDATE variants
UPDATE sales.orders SET status = 'SHIPPED' WHERE order_id = 1;
UPDATE sales.orders SET status = 'SHIPPED', shipped_at = now() WHERE order_id = 1 RETURNING *;
UPDATE sales.orders o SET amount = o.amount * 1.1 WHERE o.region = 'EU';
UPDATE sales.orders AS o SET status = c.fallback_state FROM sales.customer c WHERE c.customer_id = o.customer_id;
UPDATE sales.orders o SET amount = s.amount FROM staging.orders s JOIN staging.batch b ON b.batch_id = s.batch_id WHERE s.order_id = o.order_id AND b.loaded;
UPDATE sales.orders SET (status, amount) = ('X', 0) WHERE order_id = 2;
UPDATE sales.orders SET (status, amount) = (SELECT status, amount FROM staging.orders s WHERE s.order_id = sales.orders.order_id);
UPDATE sales.orders SET status = DEFAULT WHERE order_id = 3;
UPDATE ONLY sales.orders SET status = 'Y' WHERE false;
UPDATE sales.orders SET tags[1] = 'first', meta = meta || '{"k":1}' WHERE order_id = 4;
UPDATE sales.orders SET amount = amount + 1 WHERE CURRENT OF order_cursor;
UPDATE sales.orders SET note = NULL WHERE customer_id IN (SELECT customer_id FROM sales.blacklist) RETURNING order_id, note AS cleared_note;
UPDATE sales.orders o SET amount = v.amount FROM (VALUES (1, 10.0), (2, 20.0)) AS v(order_id, amount) WHERE v.order_id = o.order_id;
UPDATE sales.orders SET status = CASE WHEN amount > 0 THEN 'OK' ELSE 'BAD' END;
