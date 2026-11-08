-- Oracle conformance: SQL*Plus / SQLcl script commands
SET ECHO ON
SET FEEDBACK OFF
SET HEADING OFF
SET PAGESIZE 0
SET LINESIZE 200
SET TRIMSPOOL ON
SET VERIFY OFF
SET TERMOUT OFF
SET DEFINE ON
SET DEFINE '&'
SET SQLBLANKLINES ON
SET TIMING ON
SET LONG 100000
SET COLSEP ','
SET NULL '(null)'
SET SERVEROUTPUT ON
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR CONTINUE
DEFINE run_date = '2024-06-30'
DEFINE schema_name = SALES
UNDEFINE unused_var
VARIABLE g_count NUMBER
VARIABLE g_name VARCHAR2(100)
VAR g_cur REFCURSOR
EXEC :g_count := 0
EXECUTE :g_name := 'abc'
PROMPT Loading orders for &run_date
ACCEPT p_region CHAR PROMPT 'Region: ' DEFAULT 'EU'
COLUMN amount FORMAT 999,999.99 HEADING 'Amount'
COLUMN order_date NEW_VALUE last_date NOPRINT
COL name FORMAT A30
TTITLE LEFT 'Order Report' SKIP 1
BTITLE OFF
BREAK ON region SKIP 1
COMPUTE SUM OF amount ON region
SPOOL /tmp/orders_report.log
SPOOL orders.csv APPEND
SELECT region, order_id, amount FROM &schema_name..orders WHERE order_date <= TO_DATE('&run_date', 'YYYY-MM-DD');
SELECT * FROM orders WHERE region = '&&p_region';
SPOOL OFF
PRINT g_count
PRINT
CONNECT app_owner/"Str0ng#Pass"@//dbhost:1521/ORCLPDB1
CONN / AS SYSDBA
DISCONNECT
@@sub_script.sql
@other_script.sql arg1 arg2
START another_script.sql
HOST echo done
!ls -l
DESCRIBE orders
DESC sales.orders
SHOW ERRORS
SHOW ERRORS PROCEDURE raise_salary
SHO USER
SHOW PARAMETER open_cursors
CLEAR COLUMNS
CLEAR BREAKS
TIMING START load_timer
TIMING STOP
PAUSE Press enter to continue
ALTER SESSION SET CURRENT_SCHEMA = &schema_name;
BEGIN
  DBMS_OUTPUT.PUT_LINE('count=' || :g_count);
END;
/
SELECT COUNT(*) FROM orders
/
SELECT 1 FROM dual;
/
REMARK end of script
EXIT SUCCESS
