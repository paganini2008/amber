:setvar TargetDb Sales
use [$(TargetDb)]
go
if object_id('dbo.usp_post_batch', 'P') is not null drop procedure dbo.usp_post_batch
go
-- posts one batch; legacy code, do not touch the GO lines
create procedure dbo.usp_post_batch @batch_id int, @user nvarchar(128) = null, @dry_run bit = 0, @rows int = null output as
begin
set nocount on
set xact_abort on
declare @now datetime2 = sysutcdatetime(), @msg nvarchar(400)
if @batch_id is null or @batch_id <= 0
begin
raiserror('bad batch %d', 16, 1, @batch_id)
return -1
end
begin try
begin tran
select l.line_id, l.amount, a.ledger_ref into #lines from dbo.batch_lines l with (nolock) inner join dbo.accounts a on a.account_id = l.account_id and a.active = 1 where l.batch_id = @batch_id and l.amount <> 0
if @@rowcount = 0 set @msg = N'empty batch -- nothing to do; skipped'
else
  begin
    update a set a.balance = a.balance + x.total, a.updated_by = isnull(@user, suser_sname()) from dbo.accounts a join (select ledger_ref, sum(amount) total from #lines group by ledger_ref) x on x.ledger_ref = a.ledger_ref
    set @rows = @@rowcount
  end
while exists (select 1 from #lines where amount > 1000000) delete top (100) from #lines where amount > 1000000
if @dry_run = 1 rollback else commit
end try
begin catch
if xact_state() <> 0 rollback tran
select @msg = error_message()
;throw
end catch
end
go
:r .\grants.sql
exec dbo.usp_post_batch @batch_id = 42, @dry_run = 1
go 2
