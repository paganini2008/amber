:setvar DatabaseName "warehouse"
:setvar SchemaName sales
:on error exit
USE [$(DatabaseName)];
GO
SELECT * FROM $(SchemaName).Customers;
GO
PRINT N'Deploying to $(DatabaseName)';
GO
:r .\scripts\create_tables.sql
GO
:out C:\logs\deploy.log
SELECT COUNT(*) FROM sales.Orders;
GO 5
:connect sqlprod01 -U deploy_user
!! dir C:\scripts
EXIT
