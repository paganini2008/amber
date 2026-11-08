-- nightly_orders.sql: nightly revenue report. Legacy batch, edited by many people over the years.

-- step 1: today's orders, with the customer's region, into a work table
DROP TEMPORARY TABLE IF EXISTS tmp_day_orders;
CREATE TEMPORARY TABLE tmp_day_orders AS
SELECT o.*, c.region
  FROM demo.orders o, demo.customers c
 WHERE o.customer_id = c.id
   AND DATE(o.created_at) = CURDATE();

-- step 2: revenue per region for the report
INSERT INTO report_region_revenue (region, revenue, order_count)
SELECT region, SUM(amount), COUNT(*)
  FROM tmp_day_orders
 GROUP BY region;

-- step 3: customers without an order this year (for the marketing mail)
SELECT id, email
  FROM demo.customers
 WHERE id NOT IN (SELECT customer_id
                    FROM demo.orders
                   WHERE YEAR(created_at) = YEAR(CURDATE()));

-- step 4: top customers in Europe
SELECT c.email,
       (SELECT SUM(o.amount)
          FROM demo.orders o
         WHERE o.customer_id = c.id) AS total
  FROM demo.customers c
 WHERE c.region = 'EU'
 ORDER BY total DESC;

-- step 5: tidy up
UPDATE demo.orders SET amount = 0 WHERE amount < 0;
DELETE FROM report_region_revenue WHERE revenue IS NULL;
DROP TEMPORARY TABLE tmp_day_orders;
