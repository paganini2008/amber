-- Monthly overdue report for a fictional city library (sample script, Oracle)
-- Written over the years by several people; Amber shows what it really does.
SET SERVEROUTPUT ON
DEFINE report_month = '2026-10'

CREATE GLOBAL TEMPORARY TABLE tmp_overdue (
  loan_id    NUMBER,
  member_id  NUMBER,
  branch_id  NUMBER,
  days_late  NUMBER
) ON COMMIT PRESERVE ROWS;

INSERT INTO tmp_overdue (loan_id, member_id, branch_id, days_late)
SELECT l.loan_id, l.member_id, b.branch_id, TRUNC(SYSDATE) - l.due_on
  FROM lib.loans l, lib.copies c, lib.branches b
 WHERE l.copy_id = c.copy_id
   AND c.branch_id = b.branch_id
   AND l.returned_on IS NULL
   AND TO_CHAR(l.due_on, 'YYYY-MM') <= '&report_month';

-- old fee rule: 0.25 per day, capped by member category
MERGE INTO lib.member_fees f
USING (
  SELECT o.member_id,
         SUM(LEAST(o.days_late * 0.25, NVL(m.fee_cap, 10))) AS fee
    FROM tmp_overdue o
    LEFT JOIN lib.members m ON m.member_id = o.member_id
   WHERE m.status = 'ACTIVE'
   GROUP BY o.member_id
) x
ON (f.member_id = x.member_id AND f.period = '&report_month')
WHEN MATCHED THEN UPDATE SET f.amount = x.fee, f.updated_on = SYSDATE
WHEN NOT MATCHED THEN INSERT (member_id, period, amount, updated_on)
  VALUES (x.member_id, '&report_month', x.fee, SYSDATE);

BEGIN
  FOR r IN (SELECT branch_id, COUNT(*) cnt FROM tmp_overdue GROUP BY branch_id) LOOP
    UPDATE lib.branches SET overdue_count = r.cnt WHERE branch_id = r.branch_id;
    COMMIT;
  END LOOP;
EXCEPTION
  WHEN OTHERS THEN
    ROLLBACK;
    DBMS_OUTPUT.PUT_LINE('branch update failed: ' || SQLERRM);
END;
/

-- branch hierarchy with overdue totals per region
SELECT LPAD(' ', 2 * (LEVEL - 1)) || b.name AS branch,
       (SELECT COUNT(*) FROM tmp_overdue o WHERE o.branch_id = b.branch_id) AS overdue
  FROM lib.branches b
 START WITH b.parent_id IS NULL
CONNECT BY PRIOR b.branch_id = b.parent_id;

SELECT *
  FROM (SELECT m.member_id, m.display_name, f.amount
          FROM lib.members m
          JOIN lib.member_fees f ON f.member_id = m.member_id
         WHERE f.period = '&report_month'
           AND m.member_id NOT IN (SELECT member_id FROM lib.fee_waivers)
         ORDER BY f.amount DESC)
 WHERE ROWNUM <= 20;

DROP TABLE tmp_overdue PURGE;
