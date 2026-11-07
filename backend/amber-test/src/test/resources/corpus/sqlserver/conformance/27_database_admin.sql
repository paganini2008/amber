CREATE DATABASE analytics_db
ON PRIMARY (NAME = analytics_data, FILENAME = 'D:\data\analytics.mdf', SIZE = 512MB, FILEGROWTH = 64MB)
LOG ON (NAME = analytics_log, FILENAME = 'E:\log\analytics.ldf', SIZE = 128MB)
COLLATE Latin1_General_100_CI_AS_SC_UTF8;
GO
ALTER DATABASE analytics_db SET RECOVERY SIMPLE;
ALTER DATABASE analytics_db SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE;
ALTER DATABASE analytics_db SET COMPATIBILITY_LEVEL = 160;
ALTER DATABASE analytics_db ADD FILEGROUP fg_archive;
ALTER DATABASE analytics_db ADD FILE (NAME = arch1, FILENAME = 'D:\data\arch1.ndf') TO FILEGROUP fg_archive;
ALTER DATABASE SCOPED CONFIGURATION SET MAXDOP = 4;
ALTER DATABASE CURRENT SET QUERY_STORE = ON;
GO
USE analytics_db;
GO
BACKUP DATABASE analytics_db TO DISK = N'F:\backup\analytics.bak' WITH COMPRESSION, INIT, STATS = 10;
BACKUP LOG analytics_db TO DISK = N'F:\backup\analytics.trn';
RESTORE DATABASE analytics_copy FROM DISK = N'F:\backup\analytics.bak' WITH MOVE 'analytics_data' TO 'D:\data\copy.mdf', MOVE 'analytics_log' TO 'E:\log\copy.ldf', REPLACE, RECOVERY;
CHECKPOINT;
EXEC sp_configure 'show advanced options', 1;
RECONFIGURE WITH OVERRIDE;
KILL 57;
CREATE TYPE dbo.PhoneNumber FROM VARCHAR(20) NOT NULL;
DROP TYPE IF EXISTS dbo.PhoneNumber;
CREATE ASSEMBLY clr_utils FROM 'C:\clr\utils.dll' WITH PERMISSION_SET = SAFE;
CREATE XML SCHEMA COLLECTION dbo.OrderSchema AS N'<xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"><xsd:element name="order" type="xsd:string"/></xsd:schema>';
CREATE EXTERNAL DATA SOURCE lake_src WITH (LOCATION = 'abs://container@account.blob.core.windows.net/');
DECLARE @dbname SYSNAME = DB_NAME();
SELECT name, state_desc FROM sys.databases WHERE database_id > 4;
EXEC sp_who2;
EXEC msdb.dbo.sp_start_job @job_name = N'Nightly ETL';
SHUTDOWN WITH NOWAIT;
GO
