-- Functions, procedures, DO blocks
CREATE FUNCTION sales.add_tax(amount numeric, rate numeric DEFAULT 0.2) RETURNS numeric
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT amount * (1 + rate) $$;
CREATE OR REPLACE FUNCTION sales.customer_total(p_customer_id bigint)
RETURNS numeric
LANGUAGE plpgsql STABLE
AS $function$
DECLARE
  v_total numeric;
BEGIN
  SELECT sum(amount) INTO v_total FROM sales.orders WHERE customer_id = p_customer_id;
  RETURN coalesce(v_total, 0);
END;
$function$;
CREATE OR REPLACE FUNCTION sales.top_customers(p_limit int)
RETURNS TABLE (customer_id bigint, total numeric)
AS $$
BEGIN
  RETURN QUERY SELECT o.customer_id, sum(o.amount) FROM sales.orders o GROUP BY 1 ORDER BY 2 DESC LIMIT p_limit;
END
$$ LANGUAGE plpgsql;
CREATE FUNCTION sales.split_name(IN display_name text, OUT given_name text, OUT family_name text)
AS 'SELECT split_part(display_name, '' '', 1), split_part(display_name, '' '', 2)' LANGUAGE sql;
CREATE FUNCTION sales.ids() RETURNS SETOF bigint LANGUAGE sql AS $$ SELECT customer_id FROM sales.customer $$;
CREATE FUNCTION sales.sum_all(VARIADIC nums numeric[]) RETURNS numeric LANGUAGE sql AS $$ SELECT sum(n) FROM unnest(nums) n $$;
CREATE FUNCTION sales.secure_fn() RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = sales, pg_temp COST 100 AS $$ BEGIN NULL; END $$;
CREATE FUNCTION sales.atomic_add(a int, b int) RETURNS int LANGUAGE sql IMMUTABLE RETURN a + b;
CREATE FUNCTION sales.atomic_body(a int) RETURNS int LANGUAGE sql
BEGIN ATOMIC
  SELECT a * 2;
END;
CREATE FUNCTION sales.poly(anyelement) RETURNS anyelement LANGUAGE sql AS $$ SELECT $1 $$;
CREATE FUNCTION sales.c_fn(int) RETURNS int AS 'module_name', 'c_fn' LANGUAGE c STRICT;
CREATE OR REPLACE PROCEDURE sales.archive_orders(p_before date, INOUT p_moved int DEFAULT 0)
LANGUAGE plpgsql
AS $$
BEGIN
  WITH moved AS (DELETE FROM sales.orders WHERE order_date < p_before RETURNING *)
  INSERT INTO sales.orders_archive SELECT * FROM moved;
  GET DIAGNOSTICS p_moved = ROW_COUNT;
  COMMIT;
END;
$$;
CREATE PROCEDURE sales.insert_pair(a int, b int) LANGUAGE sql BEGIN ATOMIC INSERT INTO pairs VALUES (a); INSERT INTO pairs VALUES (b); END;
CALL sales.archive_orders('2020-01-01');
CALL sales.archive_orders(p_before => DATE '2021-01-01', p_moved => NULL);
SELECT sales.add_tax(100), sales.add_tax(amount => 100, rate => 0.1), sales.add_tax(100, rate := 0.05);
SELECT * FROM sales.top_customers(10);
DO $$
BEGIN
  RAISE NOTICE 'hello %', now();
END
$$;
DO LANGUAGE plpgsql $body$
DECLARE r record;
BEGIN
  FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'staging' LOOP
    EXECUTE format('TRUNCATE %I.%I', 'staging', r.tablename);
  END LOOP;
END
$body$;
DO 'BEGIN PERFORM 1; END';
ALTER FUNCTION sales.add_tax(numeric, numeric) RENAME TO add_vat;
ALTER FUNCTION sales.add_vat(numeric, numeric) OWNER TO app_owner;
ALTER FUNCTION sales.customer_total(bigint) SET work_mem = '64MB';
ALTER PROCEDURE sales.archive_orders(date, int) SET SCHEMA archive;
ALTER ROUTINE sales.ids() STABLE;
DROP FUNCTION IF EXISTS sales.add_vat(numeric, numeric), sales.ids();
DROP PROCEDURE IF EXISTS archive.archive_orders;
DROP ROUTINE sales.secure_fn() CASCADE;
CREATE AGGREGATE sales.my_sum (numeric) (SFUNC = numeric_add, STYPE = numeric, INITCOND = '0');
CREATE OPERATOR sales.=== (LEFTARG = int, RIGHTARG = int, FUNCTION = int4eq);
CREATE CAST (text AS sales.mood) WITH INOUT AS IMPLICIT;
