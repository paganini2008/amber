:setvar TargetDb Sales
use [$(TargetDb)]
go
IF object_id('dbo.usp_post_batch', 'P') IS NOT NULL
    DROP PROCEDURE dbo.usp_post_batch
go
-- posts one batch; legacy code, do not touch the GO lines
CREATE PROCEDURE dbo.usp_post_batch
    @batch_id INT,
    @user NVARCHAR(128) = NULL,
    @dry_run bit = 0,
    @rows INT = NULL OUTPUT
AS
BEGIN
    SET NOCOUNT ON
    SET xact_abort ON
    DECLARE @now datetime2 = sysutcdatetime(), @msg NVARCHAR(400)
    IF @batch_id IS NULL OR @batch_id <= 0
    BEGIN
        RAISERROR('bad batch %d', 16, 1, @batch_id)
        RETURN -1
    END
    BEGIN TRY
        BEGIN TRAN
        SELECT l.line_id, l.amount, a.ledger_ref
        INTO #lines
        FROM dbo.batch_lines l WITH (nolock)
        INNER JOIN dbo.accounts a ON a.account_id = l.account_id
            AND a.active = 1
        WHERE l.batch_id = @batch_id
            AND l.amount <> 0
        IF @@rowcount = 0
            SET @msg = N'empty batch -- nothing to do; skipped'
        ELSE
        BEGIN
            UPDATE a
            SET
                a.balance = a.balance + x.total,
                a.updated_by = isnull(@user, suser_sname())
            FROM dbo.accounts a
            JOIN (
                SELECT ledger_ref, sum(amount) total
                FROM #lines
                GROUP BY ledger_ref
            ) x ON x.ledger_ref = a.ledger_ref
            SET @rows = @@rowcount
        END
        WHILE EXISTS (SELECT 1 FROM #lines WHERE amount > 1000000)
            DELETE TOP (100)
            FROM #lines
            WHERE amount > 1000000
        IF @dry_run = 1
            ROLLBACK
        ELSE
            COMMIT
    END TRY
    BEGIN CATCH
        IF xact_state() <> 0
            ROLLBACK TRAN
        SELECT @msg = error_message();
        THROW
    END CATCH
END
go
:r .\grants.sql
EXEC dbo.usp_post_batch @batch_id = 42, @dry_run = 1
go 2
