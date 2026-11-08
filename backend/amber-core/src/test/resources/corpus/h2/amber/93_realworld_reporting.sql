-- =====================================================================
-- Reporting queries over the shop schema (analytics workload)
-- =====================================================================
-- monthly revenue with year-over-year comparison
WITH monthly AS (
    SELECT EXTRACT(YEAR FROM o.order_date) AS yr,
           EXTRACT(MONTH FROM o.order_date) AS mon,
           SUM(ol.qty * ol.unit_price) AS revenue
    FROM shop.orders o
    JOIN shop.order_line ol ON ol.order_id = o.id
    WHERE o.status <> 'CANCELLED'
    GROUP BY EXTRACT(YEAR FROM o.order_date), EXTRACT(MONTH FROM o.order_date)
)
SELECT cur.yr, cur.mon, cur.revenue,
       prev.revenue AS revenue_last_year,
       CASE WHEN prev.revenue IS NULL OR prev.revenue = 0 THEN NULL
            ELSE ROUND((cur.revenue - prev.revenue) * 100.0 / prev.revenue, 1)
       END AS growth_pct
FROM monthly cur
LEFT JOIN monthly prev ON prev.yr = cur.yr - 1 AND prev.mon = cur.mon
ORDER BY cur.yr DESC, cur.mon DESC;

-- customer segmentation
SELECT c.id, c.display_name,
       COUNT(DISTINCT o.id) AS orders,
       COALESCE(SUM(ol.qty * ol.unit_price), 0) AS lifetime_value,
       CASE
           WHEN COALESCE(SUM(ol.qty * ol.unit_price), 0) >= 10000 THEN 'GOLD'
           WHEN COALESCE(SUM(ol.qty * ol.unit_price), 0) >= 1000 THEN 'SILVER'
           ELSE 'BRONZE'
       END AS segment,
       MAX(o.order_date) AS last_order,
       DATEDIFF(DAY, MAX(o.order_date), CURRENT_DATE) AS days_since_last_order
FROM shop.customer c
LEFT JOIN shop.orders o ON o.customer_id = c.id
LEFT JOIN shop.order_line ol ON ol.order_id = o.id
WHERE c.status = 'ACTIVE'
GROUP BY c.id, c.display_name
HAVING COUNT(DISTINCT o.id) > 0
ORDER BY lifetime_value DESC NULLS LAST
LIMIT 50;

-- revenue cube by country and product
SELECT COALESCE(a.country, '(all)') AS country,
       COALESCE(p.name, '(all)') AS product,
       GROUPING(a.country) AS g_country,
       SUM(ol.qty) AS units,
       SUM(ol.qty * ol.unit_price) AS revenue
FROM shop.order_line ol
JOIN shop.orders o ON o.id = ol.order_id
JOIN shop.product p ON p.id = ol.product_id
JOIN shop.address a ON a.customer_id = o.customer_id AND a.kind = 'BILLING'
GROUP BY CUBE (a.country, p.name)
ORDER BY 1, 2;

-- basket analysis: product pairs bought together
SELECT p1.name AS product_a, p2.name AS product_b, COUNT(*) AS together
FROM shop.order_line l1
JOIN shop.order_line l2 ON l2.order_id = l1.order_id AND l2.product_id > l1.product_id
JOIN shop.product p1 ON p1.id = l1.product_id
JOIN shop.product p2 ON p2.id = l2.product_id
GROUP BY p1.name, p2.name
HAVING COUNT(*) >= 5
ORDER BY together DESC
FETCH FIRST 20 ROWS WITH TIES;

-- percentiles and distribution per month
SELECT FORMATDATETIME(o.order_date, 'yyyy-MM') AS month,
       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY o.total) AS median_total,
       PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY o.total) AS p95_total,
       LISTAGG(DISTINCT o.status, ',') WITHIN GROUP (ORDER BY o.status) AS statuses,
       COUNT(*) FILTER (WHERE o.status = 'CANCELLED') AS cancelled
FROM shop.orders o
GROUP BY FORMATDATETIME(o.order_date, 'yyyy-MM')
ORDER BY month;

-- gaps-and-islands: consecutive order days per customer
SELECT customer_id, MIN(order_date) AS streak_start, MAX(order_date) AS streak_end, COUNT(*) AS days
FROM (
    SELECT DISTINCT customer_id, order_date,
           DATEADD(DAY, -DENSE_RANK() OVER (PARTITION BY customer_id ORDER BY order_date), order_date) AS grp
    FROM shop.orders
) t
GROUP BY customer_id, grp
HAVING COUNT(*) >= 3;

-- settings lookup using non-reserved keywords as names
SELECT s.key, s.value, s.user, s.date
FROM shop.setting s
WHERE s.key IN ('currency', 'vat.default')
  AND s.date BETWEEN DATE '2024-01-01' AND CURRENT_DATE;

EXPLAIN ANALYZE
SELECT * FROM shop.v_order_summary WHERE order_date > CURRENT_DATE - 30;
