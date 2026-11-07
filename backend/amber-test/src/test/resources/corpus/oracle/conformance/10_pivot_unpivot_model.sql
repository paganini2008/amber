-- Oracle conformance: PIVOT, UNPIVOT, MODEL
SELECT * FROM (SELECT region, quarter, amount FROM sales)
PIVOT (SUM(amount) FOR quarter IN ('Q1' AS q1, 'Q2' AS q2, 'Q3' AS q3, 'Q4' AS q4));
SELECT * FROM (SELECT region, quarter, channel, amount FROM sales)
PIVOT (SUM(amount) AS total, COUNT(*) AS cnt FOR (quarter, channel) IN (('Q1', 'WEB') AS q1_web, ('Q1', 'STORE') AS q1_store));
SELECT * FROM (SELECT region, quarter, amount FROM sales)
PIVOT XML (SUM(amount) FOR quarter IN (ANY));
SELECT * FROM quarterly_sales
UNPIVOT (amount FOR quarter IN (q1 AS 'Q1', q2 AS 'Q2', q3 AS 'Q3', q4 AS 'Q4'));
SELECT * FROM quarterly_sales
UNPIVOT INCLUDE NULLS (amount FOR quarter IN (q1, q2, q3, q4));
SELECT * FROM quarterly_sales
UNPIVOT ((amt, qty) FOR quarter IN ((q1_amt, q1_qty) AS 'Q1', (q2_amt, q2_qty) AS 'Q2'));
SELECT p.* FROM (SELECT region, quarter, amount FROM sales) PIVOT (MAX(amount) FOR quarter IN ('Q1', 'Q2')) p WHERE p.region = 'EU';
SELECT country, year, s FROM sales_view
MODEL
  PARTITION BY (country)
  DIMENSION BY (year)
  MEASURES (sales s)
  RULES (
    s[2025] = s[2024] * 1.1,
    s[2026] = AVG(s)[year BETWEEN 2022 AND 2024]
  )
ORDER BY country, year;
SELECT country, year, s FROM sales_view
MODEL RETURN UPDATED ROWS
  DIMENSION BY (country, year)
  MEASURES (sales s)
  RULES UPSERT ALL ITERATE (3) (s[ANY, 2030] = s[CV(country), 2029] + 1);
