CREATE PROCEDURE dbo.usp_get_customer
    @customer_id INT,
    @include_orders BIT = 0,
    @row_count INT = NULL OUTPUT
AS
BEGIN
    SET NOCOUNT ON;
    SELECT * FROM sales.Customers WHERE customer_id = @customer_id;
    IF @include_orders = 1
        SELECT * FROM sales.Orders WHERE customer_id = @customer_id;
    SET @row_count = @@ROWCOUNT;
    RETURN 0;
END;
GO
CREATE OR ALTER PROC dbo.usp_simple @p NVARCHAR(50) AS SELECT @p AS echo;
GO
ALTER PROCEDURE dbo.usp_simple @p NVARCHAR(50) WITH RECOMPILE, EXECUTE AS OWNER AS
BEGIN
    SELECT UPPER(@p);
END
GO
CREATE PROCEDURE dbo.usp_tvp @ids dbo.IdList READONLY AS SELECT id FROM @ids;
GO
DECLARE @rc INT, @cnt INT;
EXEC @rc = dbo.usp_get_customer @customer_id = 1, @include_orders = 1, @row_count = @cnt OUTPUT;
EXECUTE dbo.usp_get_customer 1;
GO
dbo.usp_simple 'first statement in batch';
GO
EXEC dbo.usp_get_customer @customer_id = 2 WITH RESULT SETS NONE;
GO
CREATE FUNCTION dbo.fn_display_name (@first NVARCHAR(50), @last NVARCHAR(50))
RETURNS NVARCHAR(101)
WITH SCHEMABINDING
AS
BEGIN
    RETURN LTRIM(RTRIM(CONCAT(@first, N' ', @last)));
END;
GO
CREATE FUNCTION dbo.fn_customer_totals (@customer_id INT)
RETURNS TABLE
AS
RETURN (SELECT SUM(amount) AS total, COUNT(*) AS cnt FROM sales.Orders WHERE customer_id = @customer_id);
GO
CREATE FUNCTION dbo.fn_split_ints (@csv VARCHAR(MAX))
RETURNS @result TABLE (val INT NOT NULL)
AS
BEGIN
    INSERT INTO @result (val) SELECT CAST(value AS INT) FROM STRING_SPLIT(@csv, ',');
    RETURN;
END;
GO
CREATE OR ALTER FUNCTION dbo.fn_inline_or_null (@x INT) RETURNS INT WITH RETURNS NULL ON NULL INPUT, INLINE = ON AS BEGIN RETURN @x * 2; END;
GO
SELECT dbo.fn_display_name(N'Ada', N'Lovelace'), t.* FROM dbo.fn_customer_totals(1) AS t;
GO
DROP FUNCTION IF EXISTS dbo.fn_inline_or_null;
DROP PROCEDURE IF EXISTS dbo.usp_simple;
GO
CREATE PROCEDURE dbo.usp_natively @id INT
WITH NATIVE_COMPILATION, SCHEMABINDING
AS BEGIN ATOMIC WITH (TRANSACTION ISOLATION LEVEL = SNAPSHOT, LANGUAGE = N'us_english')
    SELECT val FROM dbo.InMem WHERE id = @id;
END;
GO
CREATE PROCEDURE dbo.usp_loop @n INT AS
BEGIN
    DECLARE @i INT = 0;
    WHILE @i < @n
    BEGIN
        SET @i += 1;
        IF @i % 2 = 0 CONTINUE;
        PRINT CONCAT('odd: ', @i);
    END;
    lbl_done:
    IF @n < 0 GOTO lbl_done;
    WAITFOR DELAY '00:00:01';
    RETURN @i;
END;
GO
