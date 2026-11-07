CREATE TABLE #stage (id INT NOT NULL PRIMARY KEY, val NVARCHAR(50) NULL);
CREATE TABLE ##global_stage (id INT, created DATETIME DEFAULT GETDATE());
INSERT INTO #stage (id, val) VALUES (1, N'a'), (2, N'b');
CREATE NONCLUSTERED INDEX ix_stage_val ON #stage (val);
IF OBJECT_ID('tempdb..#stage') IS NOT NULL DROP TABLE #stage;
IF OBJECT_ID(N'tempdb..##global_stage', N'U') IS NOT NULL
    DROP TABLE ##global_stage;
DECLARE @ids TABLE (id INT PRIMARY KEY, label VARCHAR(20) NOT NULL DEFAULT 'x', INDEX ix_label (label));
INSERT INTO @ids (id) SELECT TOP (5) object_id FROM sys.objects;
SELECT i.id, o.name FROM @ids AS i JOIN sys.objects o ON o.object_id = i.id;
CREATE TYPE dbo.IdList AS TABLE (id INT NOT NULL PRIMARY KEY);
GO
DECLARE @list dbo.IdList;
INSERT @list VALUES (1), (2);
EXEC dbo.usp_process_ids @ids = @list;
GO
SELECT name INTO #names FROM sys.tables;
ALTER TABLE #names ADD seq INT IDENTITY(1, 1);
TRUNCATE TABLE #names;
DROP TABLE IF EXISTS #names;
GO
