-- Training script for Amber's AppCDS archive (release workflow).
-- Small on purpose: it only has to load the classes a typical analysis touches.
CREATE TEMP TABLE paid_orders AS
SELECT o.order_id, o.customer_id, o.amount, o.paid_at
FROM sales.orders o
WHERE o.status = 'PAID';

WITH ranked AS (
  SELECT p.customer_id,
         SUM(p.amount) AS revenue,
         ROW_NUMBER() OVER (ORDER BY SUM(p.amount) DESC) AS rnk
  FROM paid_orders p
  JOIN crm.customers c ON c.customer_id = p.customer_id
  GROUP BY p.customer_id
)
INSERT INTO dw.top_customers (customer_id, revenue, rnk)
SELECT customer_id, revenue, rnk
FROM ranked
WHERE rnk <= 100;

UPDATE dw.top_customers t
SET revenue = t.revenue * 1.0
WHERE t.rnk = 1;

DROP TABLE paid_orders;
