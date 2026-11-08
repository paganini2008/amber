--------------------------------------------------------------------------------
-- Monthly partition maintenance and archive job with dynamic SQL
--------------------------------------------------------------------------------
WHENEVER SQLERROR EXIT FAILURE
SET SERVEROUTPUT ON

CREATE TABLE IF NOT EXISTS archive_job_log (
  job_id     NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  part_name  VARCHAR2(128),
  action     VARCHAR2(30),
  row_count  NUMBER,
  logged_at  TIMESTAMP DEFAULT SYSTIMESTAMP
);

CREATE OR REPLACE PROCEDURE archive_old_partitions (
  p_table_name   IN VARCHAR2,
  p_keep_months  IN PLS_INTEGER := 24
) AUTHID CURRENT_USER
IS
  v_cutoff     DATE := ADD_MONTHS(TRUNC(SYSDATE, 'MM'), -p_keep_months);
  v_high_value DATE;
  v_rows       NUMBER;
  v_sql        CLOB;
BEGIN
  FOR p IN (
    SELECT partition_name, high_value
    FROM   user_tab_partitions
    WHERE  table_name = UPPER(p_table_name)
    ORDER  BY partition_position
  ) LOOP
    EXECUTE IMMEDIATE 'SELECT ' || p.high_value || ' FROM dual' INTO v_high_value;
    CONTINUE WHEN v_high_value > v_cutoff;

    v_sql := 'INSERT /*+ APPEND */ INTO ' || p_table_name || '_arch SELECT * FROM '
             || p_table_name || ' PARTITION (' || p.partition_name || ')';
    EXECUTE IMMEDIATE v_sql;
    v_rows := SQL%ROWCOUNT;
    COMMIT;

    EXECUTE IMMEDIATE 'ALTER TABLE ' || p_table_name || ' DROP PARTITION ' || p.partition_name || ' UPDATE INDEXES';

    INSERT INTO archive_job_log (part_name, action, row_count)
    VALUES (p.partition_name, 'ARCHIVED', v_rows);
    COMMIT;
  END LOOP;
EXCEPTION
  WHEN OTHERS THEN
    DBMS_OUTPUT.PUT_LINE(DBMS_UTILITY.FORMAT_ERROR_STACK);
    ROLLBACK;
    RAISE;
END archive_old_partitions;
/

BEGIN
  archive_old_partitions('ORDERS_PART', 36);
END;
/

-- Rebuild unusable local index partitions
DECLARE
  CURSOR c_unusable IS
    SELECT index_name, partition_name
    FROM   user_ind_partitions
    WHERE  status = 'UNUSABLE';
BEGIN
  FOR r IN c_unusable LOOP
    EXECUTE IMMEDIATE 'ALTER INDEX ' || DBMS_ASSERT.ENQUOTE_NAME(r.index_name)
                   || ' REBUILD PARTITION ' || DBMS_ASSERT.ENQUOTE_NAME(r.partition_name) || ' ONLINE';
  END LOOP;
END;
/

-- Summary of what happened, pivoted by action
SELECT *
FROM  (SELECT TRUNC(logged_at, 'MM') AS month, action, row_count FROM archive_job_log)
PIVOT (SUM(row_count) FOR action IN ('ARCHIVED' AS archived, 'SKIPPED' AS skipped))
ORDER BY month;

-- Gather stats via scheduler job
BEGIN
  DBMS_SCHEDULER.CREATE_JOB(
    job_name        => 'GATHER_ORDERS_STATS',
    job_type        => 'PLSQL_BLOCK',
    job_action      => q'[BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'ORDERS_PART', granularity => 'PARTITION'); END;]',
    start_date      => SYSTIMESTAMP,
    repeat_interval => 'FREQ=MONTHLY;BYMONTHDAY=1;BYHOUR=2',
    enabled         => TRUE);
END;
/

ALTER TABLE orders_part ADD PARTITION p_next VALUES LESS THAN (TO_DATE('2099-01-01', 'YYYY-MM-DD'));
ALTER TABLE orders_part MODIFY PARTITION p_next COMPRESS FOR QUERY HIGH;
DROP PROCEDURE archive_old_partitions;
EXIT
