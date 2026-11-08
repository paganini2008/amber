SELECT region, [2022], [2023], [2024]
FROM (SELECT region, YEAR(sale_date) AS yr, amount FROM dbo.Sales) AS src
PIVOT (SUM(amount) FOR yr IN ([2022], [2023], [2024])) AS pvt;
SELECT * FROM (SELECT product, quarter, qty FROM dbo.QuarterlySales) s PIVOT (MAX(qty) FOR quarter IN ([Q1], [Q2], [Q3], [Q4])) p ORDER BY product;
SELECT product, quarter, qty FROM dbo.WideSales UNPIVOT (qty FOR quarter IN (Q1, Q2, Q3, Q4)) AS unp;
SELECT u.product, u.quarter, u.qty FROM (SELECT product, Q1, Q2 FROM dbo.WideSales) w UNPIVOT (qty FOR quarter IN ([Q1], [Q2])) AS u;
SELECT p.* FROM dbo.Sales s PIVOT (COUNT(order_id) FOR status IN ([open], [closed])) p;
SELECT * FROM (SELECT region, yr, amount FROM dbo.SalesByYear) s PIVOT (SUM(amount) FOR yr IN ([2023], [2024])) p WHERE p.region <> 'X';
DECLARE @cols NVARCHAR(MAX) = N'[2022],[2023]';
DECLARE @sql NVARCHAR(MAX) = N'SELECT * FROM (SELECT region, yr, amount FROM dbo.SalesByYear) s PIVOT (SUM(amount) FOR yr IN (' + @cols + N')) p;';
EXEC sp_executesql @sql;
SELECT p.product, v.attr, v.val FROM dbo.Products p CROSS APPLY (VALUES ('color', p.color), ('size', p.size)) AS v (attr, val);
