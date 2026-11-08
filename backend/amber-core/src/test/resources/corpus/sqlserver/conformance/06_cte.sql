WITH recent AS (SELECT * FROM dbo.Orders WHERE order_date > '2024-01-01')
SELECT customer_id, COUNT(*) FROM recent GROUP BY customer_id;
GO
WITH a AS (SELECT 1 AS n), b (n2) AS (SELECT n + 1 FROM a)
SELECT * FROM a CROSS JOIN b;
GO
WITH org (employee_id, manager_id, lvl, path) AS (
    SELECT employee_id, manager_id, 0, CAST(name AS NVARCHAR(4000)) FROM hr.Employees WHERE manager_id IS NULL
    UNION ALL
    SELECT e.employee_id, e.manager_id, o.lvl + 1, CAST(o.path + N' > ' + e.name AS NVARCHAR(4000))
    FROM hr.Employees e INNER JOIN org o ON e.manager_id = o.employee_id
)
SELECT * FROM org OPTION (MAXRECURSION 200);
GO
DECLARE @x INT = 1;
WITH nums AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM nums WHERE n < 100)
SELECT n FROM nums OPTION (MAXRECURSION 0);
GO
WITH dupes AS (SELECT *, ROW_NUMBER() OVER (PARTITION BY email ORDER BY customer_id) AS rn FROM dbo.Customers)
DELETE FROM dupes WHERE rn > 1;
GO
WITH upd AS (SELECT price, list_price FROM dbo.Products WHERE category = 'A')
UPDATE upd SET price = list_price * 0.9;
GO
WITH src AS (SELECT * FROM staging.Customers)
INSERT INTO dbo.Customers (customer_id, name) SELECT customer_id, name FROM src;
GO
WITH src AS (SELECT customer_id, name FROM staging.Customers)
MERGE dbo.Customers AS t USING src AS s ON t.customer_id = s.customer_id
WHEN MATCHED THEN UPDATE SET t.name = s.name
WHEN NOT MATCHED THEN INSERT (customer_id, name) VALUES (s.customer_id, s.name);
GO
SELECT 1 AS x;
WITH totals AS (SELECT 1 AS t) SELECT * FROM totals;
GO
;WITH semi AS (SELECT 2 AS y) SELECT y FROM semi;
GO
CREATE VIEW dbo.v_recent AS
WITH r AS (SELECT * FROM dbo.Orders WHERE order_date > DATEADD(DAY, -30, GETDATE()))
SELECT * FROM r;
GO
