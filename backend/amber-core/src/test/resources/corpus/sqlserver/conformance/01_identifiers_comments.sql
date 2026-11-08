-- Identifiers, quoting and comments in T-SQL
/* block comment
   spanning lines */
SELECT [order id], [Customer Name] FROM [sales].[order header];
GO
SET QUOTED_IDENTIFIER ON;
GO
SELECT "first name", "last name" FROM "hr"."staff member";
GO
SELECT o.order_id /* inline comment */ , o.total -- trailing comment
FROM sales.orders AS o;
GO
SELECT warehouse_db.inventory.stock_level.qty FROM warehouse_db.inventory.stock_level;
GO
SELECT x.qty FROM linked_srv.warehouse_db.inventory.stock_level AS x;
GO
SELECT x.qty FROM warehouse_db..stock_level AS x;
GO
SELECT [select], [from], [where] FROM dbo.[keyword table];
GO
SELECT [col]]with]]brackets] FROM dbo.odd_names;
GO
SELECT _private_col, col$2, [#notatemp] = 1 FROM dbo.underscore_table;
GO
SELECT @@ROWCOUNT AS affected, @@SPID AS session_id, @@VERSION AS ver;
GO
/* nested /* comment */ still comment */
SELECT 1 AS one;
GO
SELECT N'Unicode ''quoted'' text' AS label;
GO
SELECT p.[Name] AS [Product Name], p.Price AS 'Unit Price', p.Qty Quantity FROM dbo.Products p;
GO
SELECT [Product Name] = p.[Name] FROM dbo.Products AS p;
GO
SELECT customer_id FROM dbo.Customers WITH (NOLOCK) WHERE region_code = 'EU';
GO
SELECT customer_id FROM dbo.Customers c WITH (NOLOCK, INDEX(ix_customers_region)) WHERE c.region_code = 'EU';
GO
SELECT * FROM dbo.Customers (NOLOCK);
GO
