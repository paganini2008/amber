-- Long realistic ETL: SCD type 2 dimension maintenance with procedures
CREATE SCHEMA IF NOT EXISTS dw;

CREATE TABLE IF NOT EXISTS dw.dim_product (
    product_sk     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_code   text NOT NULL,
    product_name   text NOT NULL,
    category       text,
    list_price     numeric(12,2),
    attrs          jsonb NOT NULL DEFAULT '{}'::jsonb,
    valid_from     timestamptz NOT NULL DEFAULT now(),
    valid_to       timestamptz,
    is_current     boolean NOT NULL DEFAULT true,
    row_hash       text GENERATED ALWAYS AS (md5(product_name || '|' || coalesce(category, '') || '|' || coalesce(list_price::text, ''))) STORED
);

CREATE UNIQUE INDEX IF NOT EXISTS dim_product_current_uq
    ON dw.dim_product (product_code) WHERE is_current;

CREATE TABLE IF NOT EXISTS dw.etl_error_log (
    id          bigserial PRIMARY KEY,
    proc_name   text,
    sqlstate    char(5),
    message     text,
    detail      text,
    logged_at   timestamptz DEFAULT now()
);

CREATE OR REPLACE FUNCTION dw.log_error(p_proc text, p_state text, p_msg text, p_detail text)
RETURNS void
LANGUAGE sql
AS $$
    INSERT INTO dw.etl_error_log (proc_name, sqlstate, message, detail)
    VALUES (p_proc, p_state, p_msg, p_detail);
$$;

CREATE OR REPLACE PROCEDURE dw.load_dim_product(p_batch_id int, INOUT p_inserted int DEFAULT NULL, INOUT p_closed int DEFAULT NULL)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = dw, staging, pg_temp
AS $proc$
DECLARE
    v_now timestamptz := clock_timestamp();
    v_state text;
    v_msg text;
    v_detail text;
    cur_changes CURSOR FOR
        SELECT s.product_code, s.product_name, s.category, s.list_price
        FROM staging.product s
        WHERE s.batch_id = p_batch_id;
    rec record;
BEGIN
    CREATE TEMP TABLE IF NOT EXISTS tmp_changed (product_code text PRIMARY KEY) ON COMMIT DROP;
    TRUNCATE tmp_changed;

    FOR rec IN cur_changes LOOP
        IF EXISTS (
            SELECT 1 FROM dw.dim_product d
            WHERE d.product_code = rec.product_code AND d.is_current
              AND (d.product_name, d.category, d.list_price) IS DISTINCT FROM (rec.product_name, rec.category, rec.list_price)
        ) THEN
            INSERT INTO tmp_changed VALUES (rec.product_code) ON CONFLICT DO NOTHING;
        END IF;
    END LOOP;

    UPDATE dw.dim_product d
       SET valid_to = v_now, is_current = false
      FROM tmp_changed c
     WHERE d.product_code = c.product_code AND d.is_current;
    GET DIAGNOSTICS p_closed = ROW_COUNT;

    INSERT INTO dw.dim_product (product_code, product_name, category, list_price, valid_from)
    SELECT s.product_code, s.product_name, s.category, s.list_price, v_now
    FROM staging.product s
    LEFT JOIN dw.dim_product d ON d.product_code = s.product_code AND d.is_current
    WHERE s.batch_id = p_batch_id AND d.product_sk IS NULL;
    GET DIAGNOSTICS p_inserted = ROW_COUNT;

    COMMIT;
EXCEPTION
    WHEN unique_violation OR foreign_key_violation THEN
        GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT, v_detail = PG_EXCEPTION_DETAIL;
        PERFORM dw.log_error('load_dim_product', v_state, v_msg, v_detail);
        RAISE;
    WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
        PERFORM dw.log_error('load_dim_product', v_state, v_msg, NULL);
        RAISE EXCEPTION 'load failed: %', v_msg USING ERRCODE = 'P0001', HINT = 'check dw.etl_error_log';
END;
$proc$;

CREATE OR REPLACE FUNCTION dw.product_history(p_code text)
RETURNS TABLE (product_sk bigint, valid_from timestamptz, valid_to timestamptz, list_price numeric, price_change numeric)
LANGUAGE sql STABLE
AS $$
    SELECT product_sk, valid_from, valid_to, list_price,
           list_price - lag(list_price) OVER (ORDER BY valid_from)
    FROM dw.dim_product
    WHERE product_code = p_code
    ORDER BY valid_from;
$$;

CALL dw.load_dim_product(101);

SELECT * FROM dw.product_history('SKU-001');

WITH RECURSIVE cat_tree AS (
    SELECT category_id, parent_id, name, ARRAY[name] AS path
    FROM dw.dim_category WHERE parent_id IS NULL
    UNION ALL
    SELECT c.category_id, c.parent_id, c.name, t.path || c.name
    FROM dw.dim_category c JOIN cat_tree t ON c.parent_id = t.category_id
)
SELECT category_id, array_to_string(path, ' > ') AS full_path FROM cat_tree ORDER BY full_path;

SELECT category,
       count(*) FILTER (WHERE is_current) AS active_products,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY list_price) AS median_price,
       jsonb_agg(DISTINCT attrs -> 'brand') AS brands
FROM dw.dim_product
GROUP BY GROUPING SETS ((category), ())
ORDER BY category NULLS LAST;

DO $$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT indexrelid::regclass AS idx FROM pg_index i JOIN pg_class c ON c.oid = i.indrelid
             WHERE c.relname = 'dim_product' LOOP
        EXECUTE 'REINDEX INDEX ' || r.idx;
    END LOOP;
END;
$$;

GRANT SELECT ON dw.dim_product TO report_role;
GRANT EXECUTE ON PROCEDURE dw.load_dim_product(int, int, int) TO etl_user;
REVOKE ALL ON dw.etl_error_log FROM PUBLIC;

DELETE FROM dw.etl_error_log WHERE logged_at < now() - interval '90 days';
