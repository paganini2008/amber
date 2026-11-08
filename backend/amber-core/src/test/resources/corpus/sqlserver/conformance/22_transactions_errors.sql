BEGIN TRANSACTION;
UPDATE dbo.Accounts SET balance -= 100 WHERE account_id = 1;
UPDATE dbo.Accounts SET balance += 100 WHERE account_id = 2;
COMMIT TRANSACTION;
BEGIN TRAN transfer_tx WITH MARK 'nightly transfer';
SAVE TRANSACTION before_fees;
UPDATE dbo.Accounts SET balance -= 1 WHERE account_id = 1;
ROLLBACK TRANSACTION before_fees;
COMMIT TRAN transfer_tx;
BEGIN DISTRIBUTED TRANSACTION;
COMMIT WORK;
SET TRANSACTION ISOLATION LEVEL SNAPSHOT;
SET XACT_ABORT ON;
SET NOCOUNT ON;
SET ANSI_NULLS ON;
SET DEADLOCK_PRIORITY LOW;
SET LOCK_TIMEOUT 5000;
SET DATEFORMAT ymd;
SET LANGUAGE us_english;
SET STATISTICS IO ON;
SET STATISTICS TIME ON;
SET ROWCOUNT 0;
BEGIN TRY
    BEGIN TRAN;
    INSERT INTO dbo.Accounts (account_id, balance) VALUES (3, 0);
    IF @@TRANCOUNT > 0 COMMIT;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK;
    DECLARE @msg NVARCHAR(4000) = ERROR_MESSAGE(), @sev INT = ERROR_SEVERITY(), @st INT = ERROR_STATE();
    SELECT ERROR_NUMBER() AS num, ERROR_LINE() AS line, ERROR_PROCEDURE() AS proc_name;
    RAISERROR (@msg, @sev, @st);
END CATCH;
BEGIN TRY
    SELECT 1 / 0;
END TRY
BEGIN CATCH
    THROW;
END CATCH;
THROW 50001, N'Custom failure', 1;
RAISERROR (N'Value %d exceeded %s', 16, 1, 42, N'limit') WITH NOWAIT, LOG;
RAISERROR 50005 'legacy form';
IF XACT_STATE() = -1 ROLLBACK TRANSACTION;
PRINT 'trancount: ' + CAST(@@TRANCOUNT AS VARCHAR(10));
EXEC sp_addmessage @msgnum = 60000, @severity = 16, @msgtext = N'Order %d not found', @lang = 'us_english';
DECLARE @err INT; SET @err = @@ERROR; IF @err <> 0 GOTO error_handler;
error_handler:
    PRINT 'error';
