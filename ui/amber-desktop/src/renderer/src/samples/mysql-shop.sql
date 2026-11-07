-- Daily sales snapshot for a fictional online shop (sample script, MySQL)
/*!40101 SET NAMES utf8mb4 */;
SET @snapshot_day = CURDATE() - INTERVAL 1 DAY;

DROP TEMPORARY TABLE IF EXISTS tmp_day_orders;
CREATE TEMPORARY TABLE tmp_day_orders AS
SELECT o.id AS order_id, o.customer_id, o.coupon_code, SUM(i.qty * i.unit_price) AS gross
  FROM shop.orders o
  JOIN shop.order_items i ON i.order_id = o.id
 WHERE DATE(o.created_at) = @snapshot_day
   AND o.status <> 'CANCELLED'
 GROUP BY o.id, o.customer_id, o.coupon_code;

UPDATE tmp_day_orders t
  JOIN shop.coupons c ON c.code = t.coupon_code
   SET t.gross = t.gross * (1 - c.discount_pct / 100)
 WHERE c.active = 1;

INSERT INTO report.daily_sales (sale_day, orders, customers, revenue, top_category)
WITH by_category AS (
  SELECT p.category, SUM(i.qty * i.unit_price) AS amount
    FROM shop.order_items i
    JOIN shop.products p ON p.id = i.product_id
   WHERE i.order_id IN (SELECT order_id FROM tmp_day_orders)
   GROUP BY p.category
)
SELECT @snapshot_day, COUNT(*), COUNT(DISTINCT customer_id), SUM(gross),
       (SELECT category FROM by_category ORDER BY amount DESC LIMIT 1)
  FROM tmp_day_orders
ON DUPLICATE KEY UPDATE orders = VALUES(orders), revenue = VALUES(revenue);

DELIMITER $$
CREATE PROCEDURE report.refresh_vip()
BEGIN
  DECLARE done INT DEFAULT 0;
  DELETE FROM report.vip_customers;
  INSERT INTO report.vip_customers (customer_id, total_90d)
  SELECT customer_id, SUM(gross)
    FROM report.order_history
   WHERE sale_day >= CURDATE() - INTERVAL 90 DAY
   GROUP BY customer_id
  HAVING SUM(gross) > 1000;
END$$
DELIMITER ;

CALL report.refresh_vip();

SELECT c.email, v.total_90d
  FROM report.vip_customers v, shop.customers c
 WHERE c.email LIKE '%@example.com';
