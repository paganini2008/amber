-- Oracle conformance: INSERT variants
INSERT INTO customers (customer_id, name, region) VALUES (1, 'Acme', 'EU');
INSERT INTO customers VALUES (2, 'Globex', 'NA', SYSDATE, NULL);
INSERT INTO customers (customer_id, name) VALUES (customers_seq.NEXTVAL, 'Initech');
INSERT INTO customers c (c.customer_id, c.name) VALUES (3, 'Umbrella');
INSERT INTO archive_orders SELECT * FROM orders WHERE order_date < DATE '2020-01-01';
INSERT /*+ APPEND */ INTO archive_orders (order_id, amount) SELECT order_id, amount FROM orders;
INSERT /*+ APPEND_VALUES */ INTO audit_log (msg) VALUES ('x');
INSERT INTO orders (order_id, amount) VALUES (100, 20) RETURNING order_id INTO :new_id;
INSERT INTO orders (order_id, amount) VALUES (101, 30) RETURNING order_id, amount INTO :id, :amt;
INSERT ALL
  INTO orders_small (order_id, amount) VALUES (order_id, amount)
  INTO orders_audit (order_id, logged_at) VALUES (order_id, SYSDATE)
SELECT order_id, amount FROM staging_orders;
INSERT FIRST
  WHEN amount < 100 THEN INTO orders_small VALUES (order_id, amount)
  WHEN amount < 1000 THEN INTO orders_medium VALUES (order_id, amount)
  ELSE INTO orders_large VALUES (order_id, amount)
SELECT order_id, amount FROM staging_orders;
INSERT ALL
  WHEN region = 'EU' THEN INTO eu_customers (id, name) VALUES (customer_id, name)
  WHEN region = 'NA' THEN INTO na_customers (id, name) VALUES (customer_id, name)
SELECT customer_id, name, region FROM customers;
INSERT ALL
  INTO t_multi (a, b) VALUES (1, 'x')
  INTO t_multi (a, b) VALUES (2, 'y')
  INTO t_multi (a, b) VALUES (3, 'z')
SELECT * FROM dual;
INSERT INTO t_multi (a, b) VALUES (1, 'x'), (2, 'y'), (3, 'z');
INSERT INTO (SELECT order_id, amount FROM orders WHERE region = 'EU' WITH CHECK OPTION) VALUES (200, 10);
INSERT INTO orders PARTITION (p_2024) (order_id, amount) VALUES (300, 50);
INSERT INTO remote_orders@dblink_hq (order_id) VALUES (1);
INSERT INTO err_target (id, val) SELECT id, val FROM src LOG ERRORS INTO err$_err_target ('batch1') REJECT LIMIT UNLIMITED;
INSERT INTO customers (customer_id, name, created_at) VALUES (4, q'[O'Reilly]', DEFAULT);
