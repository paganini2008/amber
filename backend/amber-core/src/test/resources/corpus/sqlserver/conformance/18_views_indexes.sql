CREATE VIEW sales.v_customer_orders
AS
SELECT c.customer_id, c.name, COUNT_BIG(*) AS order_count, SUM(ISNULL(o.amount, 0)) AS total
FROM sales.Customers c JOIN sales.Orders o ON o.customer_id = c.customer_id
GROUP BY c.customer_id, c.name;
GO
CREATE OR ALTER VIEW sales.v_open_orders WITH SCHEMABINDING AS
SELECT order_id, customer_id, amount FROM sales.Orders WHERE status = 'N';
GO
CREATE UNIQUE CLUSTERED INDEX ucx_v_open_orders ON sales.v_open_orders (order_id);
GO
CREATE VIEW dbo.v_checked AS SELECT * FROM dbo.Products WHERE active = 1 WITH CHECK OPTION;
GO
ALTER VIEW dbo.v_checked AS SELECT product_id, name FROM dbo.Products;
GO
DROP VIEW IF EXISTS dbo.v_checked;
GO
CREATE INDEX ix_orders_cust ON sales.Orders (customer_id);
CREATE UNIQUE NONCLUSTERED INDEX ux_orders_ref ON sales.Orders (order_id, customer_id) INCLUDE (amount) WHERE status <> 'X' WITH (FILLFACTOR = 90, ONLINE = ON, SORT_IN_TEMPDB = ON);
CREATE NONCLUSTERED COLUMNSTORE INDEX ncci_orders ON sales.Orders (order_date, amount);
CREATE CLUSTERED INDEX cx_log ON dbo.EventLog (logged_at) WITH (DROP_EXISTING = ON) ON [PRIMARY];
CREATE SPATIAL INDEX sx_shapes ON dbo.Shapes (geo) USING GEOGRAPHY_AUTO_GRID;
CREATE PRIMARY XML INDEX pxi_docs ON dbo.XmlDocs (doc);
ALTER INDEX ix_orders_cust ON sales.Orders REBUILD WITH (ONLINE = ON);
ALTER INDEX ALL ON sales.Orders REORGANIZE;
ALTER INDEX ix_orders_cust ON sales.Orders DISABLE;
DROP INDEX ix_orders_cust ON sales.Orders;
DROP INDEX IF EXISTS ux_orders_ref ON sales.Orders;
CREATE STATISTICS st_orders_amount ON sales.Orders (amount) WITH FULLSCAN;
UPDATE STATISTICS sales.Orders WITH SAMPLE 50 PERCENT;
CREATE FULLTEXT CATALOG ft_catalog AS DEFAULT;
CREATE FULLTEXT INDEX ON dbo.Products (description) KEY INDEX pk_products ON ft_catalog;
CREATE SYNONYM dbo.Cust FOR sales.Customers;
DROP SYNONYM IF EXISTS dbo.Cust;
