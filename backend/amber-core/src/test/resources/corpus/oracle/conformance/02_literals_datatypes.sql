-- Oracle conformance: literals and data types
SELECT 'It''s quoted' FROM dual;
SELECT q'[It's alternative quoting]' FROM dual;
SELECT q'{braces}', q'(parens)', q'<angles>', q'!bang!' FROM dual;
SELECT N'national text', n'lower n' FROM dual;
SELECT nq'[national q-quote]' FROM dual;
SELECT 42, -7, 3.14, .5, 1e10, 2.5E-3, 10f, 3.5d FROM dual;
SELECT DATE '2024-01-31', TIMESTAMP '2024-01-31 10:20:30.123' FROM dual;
SELECT TIMESTAMP '2024-01-31 10:20:30 +02:00' FROM dual;
SELECT INTERVAL '5' DAY, INTERVAL '2-6' YEAR TO MONTH FROM dual;
SELECT INTERVAL '4 5:12:10.222' DAY TO SECOND(3) FROM dual;
SELECT INTERVAL '30' MINUTE, INTERVAL '90' SECOND FROM dual;
SELECT SYSDATE + INTERVAL '1' HOUR FROM dual;
SELECT CAST('123' AS NUMBER(10,2)) FROM dual;
SELECT CAST(order_ts AS DATE), CAST(amount AS VARCHAR2(20 CHAR)) FROM orders;
SELECT TO_DATE('2024-02-29', 'YYYY-MM-DD'), TO_CHAR(SYSDATE, 'DD-MON-YYYY HH24:MI:SS') FROM dual;
SELECT TO_NUMBER('1,234.50', '9G999D99') FROM dual;
SELECT TO_TIMESTAMP_TZ('2024-03-01 10:00:00 Europe/Paris', 'YYYY-MM-DD HH24:MI:SS TZR') FROM dual;
SELECT order_ts AT TIME ZONE 'UTC' FROM orders;
SELECT FROM_TZ(CAST(order_ts AS TIMESTAMP), 'America/New_York') AT LOCAL FROM orders;
SELECT EXTRACT(YEAR FROM order_date), EXTRACT(HOUR FROM order_ts) FROM orders;
SELECT HEXTORAW('DEADBEEF'), UTL_RAW.CAST_TO_VARCHAR2(HEXTORAW('41')) FROM dual;
SELECT BINARY_DOUBLE_INFINITY, BINARY_FLOAT_NAN FROM dual;
SELECT CASE WHEN amount IS NULL THEN 0 ELSE amount END FROM orders;
SELECT NVL(a, 0), NVL2(a, 1, 0), COALESCE(a, b, c), NULLIF(a, b), DECODE(status, 'A', 1, 'B', 2, 0) FROM t_codes;
CREATE TABLE type_showcase (
  c_num        NUMBER,
  c_num_ps     NUMBER(12,4),
  c_num_star   NUMBER(*,0),
  c_int        INTEGER,
  c_float      FLOAT(63),
  c_bf         BINARY_FLOAT,
  c_bd         BINARY_DOUBLE,
  c_vc         VARCHAR2(100 BYTE),
  c_vc_char    VARCHAR2(100 CHAR),
  c_nvc        NVARCHAR2(50),
  c_char       CHAR(3),
  c_nchar      NCHAR(3),
  c_clob       CLOB,
  c_nclob      NCLOB,
  c_blob       BLOB,
  c_bfile      BFILE,
  c_raw        RAW(16),
  c_long_raw   LONG RAW,
  c_date       DATE,
  c_ts         TIMESTAMP(6),
  c_tstz       TIMESTAMP(6) WITH TIME ZONE,
  c_tsltz      TIMESTAMP WITH LOCAL TIME ZONE,
  c_iym        INTERVAL YEAR(2) TO MONTH,
  c_ids        INTERVAL DAY(3) TO SECOND(2),
  c_rowid      ROWID,
  c_urowid     UROWID(4000),
  c_xml        XMLTYPE,
  c_json       JSON
);
CREATE TABLE legacy_long (id NUMBER, notes LONG);
SELECT 'line1' || CHR(10) || 'line2' FROM dual;
