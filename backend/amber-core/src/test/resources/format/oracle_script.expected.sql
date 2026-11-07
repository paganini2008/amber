-- nightly refresh of the sales mart
set define on
set serveroutput on size unlimited
whenever sqlerror exit failure rollback
prompt === step 1: stage ===
define run_date = '2024-01-31'
column cnt new_value v_cnt

SELECT /*+ parallel(s 4) */
    s.region_code,
    s.product_id,
    sum(s.qty) qty,
    sum(s.amount) amt,
    count(*) cnt
FROM stg_sales s
WHERE s.sale_date = to_date('&run_date', 'YYYY-MM-DD')
    AND s.note <> 'skip -- not a comment; really' -- trailing remark
    AND s.batch_id = &&batch_id.
GROUP BY s.region_code, s.product_id
ORDER BY 1, 2;

INSERT INTO mart_sales (region_code, product_id, qty, amount, load_ts)
SELECT
    region_code,
    product_id,
    qty,
    amt,
    systimestamp
FROM (
    SELECT
        region_code,
        product_id,
        sum(qty) qty,
        sum(amount) amt
    FROM (
        SELECT *
        FROM (
            SELECT s.*, row_number() OVER (PARTITION BY s.id ORDER BY s.version DESC) rn
            FROM stg_sales s
            WHERE s.status IN ('A', 'B', 'C')
        )
        WHERE rn = 1
    )
    GROUP BY region_code, product_id
);

UPDATE mart_sales m
SET
    m.amount = (
        SELECT sum(x.amount)
        FROM adj x
        WHERE x.region_code = m.region_code /* same region */
            AND x.product_id = m.product_id
    ),
    m.load_ts = systimestamp
WHERE EXISTS (SELECT 1 FROM adj x WHERE x.region_code = m.region_code);

DELETE FROM mart_sales
WHERE load_ts < add_months(sysdate, -24)
    AND q'[it's old; drop it]' IS NOT NULL;

MERGE INTO dim_product d
USING (
    SELECT id, name, price
    FROM stg_product
    WHERE name LIKE '%;%'
) s
ON (d.id = s.id)
WHEN MATCHED THEN
    UPDATE SET
        d.name = s.name,
        d.price = s.price
    WHERE d.price <> s.price
WHEN NOT MATCHED THEN
    INSERT (id, name, price)
    VALUES (s.id, s.name, s.price);

WITH base AS (
    SELECT region_code, sum(amount) amt
    FROM mart_sales
    GROUP BY region_code
),
ranked AS (
    SELECT b.*, rank() OVER (ORDER BY amt DESC) rk
    FROM base b
),
top_regions AS (
    SELECT *
    FROM ranked
    WHERE rk <= 5
)
SELECT
    r.region_code,
    r.amt,
    CASE
        WHEN r.rk = 1 THEN 'leader'
        WHEN r.rk BETWEEN 2 AND 3 THEN 'challenger'
        ELSE 'follower'
    END AS tier
FROM top_regions r
ORDER BY r.rk;

CREATE TABLE mart_audit (
    audit_id NUMBER(18) NOT NULL,
    run_date date DEFAULT sysdate NOT NULL,
    message VARCHAR2(4000),
    CONSTRAINT mart_audit_pk PRIMARY KEY (audit_id)
);
/
@@post_load.sql
exit
