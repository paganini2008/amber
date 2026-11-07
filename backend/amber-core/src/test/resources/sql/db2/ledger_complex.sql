-- Ledger close (synthetic test script for Amber), CLP with the @ terminator.
--#SET TERMINATOR @
DECLARE GLOBAL TEMPORARY TABLE session.tmp_posting (acct_ref VARCHAR(20), amount DECIMAL(15,2))
  ON COMMIT PRESERVE ROWS NOT LOGGED@

INSERT INTO session.tmp_posting (acct_ref, amount)
WITH day_entries AS (
  SELECT e.acct_ref, e.amount FROM fin.entry e WHERE e.posted_on = CURRENT DATE - 1 DAY
)
SELECT d.acct_ref, SUM(d.amount) FROM day_entries d GROUP BY d.acct_ref@

MERGE INTO fin.balance b
USING (SELECT acct_ref, amount FROM session.tmp_posting) p ON b.acct_ref = p.acct_ref
WHEN MATCHED THEN UPDATE SET b.amount = b.amount + p.amount
WHEN NOT MATCHED THEN INSERT (acct_ref, amount) VALUES (p.acct_ref, p.amount)@

CREATE OR REPLACE PROCEDURE fin.close_day (IN p_day DATE)
LANGUAGE SQL
BEGIN
  DECLARE v_count INTEGER DEFAULT 0;
  DECLARE CONTINUE HANDLER FOR SQLEXCEPTION
    INSERT INTO fin.close_log (closed_on, status) VALUES (p_day, 'FAILED');
  FOR r AS c1 CURSOR FOR SELECT acct_ref FROM fin.balance WHERE amount < 0 DO
    INSERT INTO fin.overdraft (acct_ref, noted_on) VALUES (r.acct_ref, p_day);
    SET v_count = v_count + 1;
  END FOR;
  IF v_count > 0 THEN
    UPDATE fin.close_log SET status = 'WARN' WHERE closed_on = p_day;
  END IF;
END@

CALL fin.close_day(CURRENT DATE - 1 DAY)@
DROP TABLE session.tmp_posting@
