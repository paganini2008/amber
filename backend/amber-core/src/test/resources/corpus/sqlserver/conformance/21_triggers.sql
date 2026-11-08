CREATE TRIGGER sales.trg_orders_audit ON sales.Orders
AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;
    IF UPDATE(amount)
    BEGIN
        INSERT INTO dbo.AuditLog (order_id, old_amount, new_amount, changed_at)
        SELECT i.order_id, d.amount, i.amount, SYSUTCDATETIME()
        FROM inserted i JOIN deleted d ON d.order_id = i.order_id;
    END;
    IF NOT EXISTS (SELECT 1 FROM inserted) AND EXISTS (SELECT 1 FROM deleted)
        INSERT INTO dbo.AuditLog (order_id, old_amount, changed_at) SELECT order_id, amount, SYSUTCDATETIME() FROM deleted;
END;
GO
CREATE OR ALTER TRIGGER dbo.trg_v_insert ON dbo.v_customer_orders INSTEAD OF INSERT AS
BEGIN
    INSERT INTO sales.Customers (email, name) SELECT name + '@example.com', name FROM inserted;
END;
GO
CREATE TRIGGER trg_ddl_guard ON DATABASE FOR DROP_TABLE, ALTER_TABLE AS
BEGIN
    DECLARE @evt XML = EVENTDATA();
    PRINT @evt.value('(/EVENT_INSTANCE/ObjectName)[1]', 'NVARCHAR(256)');
    ROLLBACK;
END;
GO
CREATE TRIGGER trg_logon_limit ON ALL SERVER WITH EXECUTE AS 'sa' FOR LOGON AS
BEGIN
    IF ORIGINAL_LOGIN() = 'blocked_user' ROLLBACK;
END;
GO
CREATE TRIGGER dbo.trg_products_upd ON dbo.Products WITH EXECUTE AS CALLER FOR UPDATE NOT FOR REPLICATION AS
IF COLUMNS_UPDATED() & 2 = 2 PRINT 'col 2 changed';
GO
DISABLE TRIGGER sales.trg_orders_audit ON sales.Orders;
ENABLE TRIGGER ALL ON sales.Orders;
DISABLE TRIGGER trg_ddl_guard ON DATABASE;
DROP TRIGGER IF EXISTS sales.trg_orders_audit;
DROP TRIGGER trg_ddl_guard ON DATABASE;
GO
