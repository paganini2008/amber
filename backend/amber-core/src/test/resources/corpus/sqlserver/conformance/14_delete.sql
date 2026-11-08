DELETE FROM dbo.Orders WHERE order_id = 1;
DELETE dbo.Orders WHERE status = 'CANCELLED';
DELETE o FROM dbo.Orders o INNER JOIN dbo.Customers c ON c.customer_id = o.customer_id WHERE c.is_deleted = 1;
DELETE FROM dbo.Orders FROM dbo.Orders o JOIN dbo.Blocked b ON b.customer_id = o.customer_id;
DELETE TOP (1000) FROM dbo.EventLog WHERE logged_at < DATEADD(DAY, -90, GETDATE());
DELETE FROM dbo.Orders OUTPUT deleted.* INTO dbo.DeletedOrders WHERE status = 'X';
DELETE FROM dbo.Orders OUTPUT deleted.order_id WHERE order_id < 5;
DELETE FROM dbo.Orders WHERE CURRENT OF c_orders;
DELETE FROM @tv;
DELETE #tmp WHERE id > 10;
TRUNCATE TABLE dbo.StagingOrders;
TRUNCATE TABLE dbo.PartitionedSales WITH (PARTITIONS (1, 3 TO 5));
WHILE 1 = 1
BEGIN
    DELETE TOP (5000) FROM dbo.EventLog WHERE logged_at < '2020-01-01';
    IF @@ROWCOUNT = 0 BREAK;
END;
