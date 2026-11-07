-- Loyalty points batch (synthetic test script for Amber): PL/pgSQL functions, a procedure,
-- a DO block and an SQL-language function.
CREATE OR REPLACE FUNCTION loyalty.award_points(p_since date)
RETURNS integer
LANGUAGE plpgsql
AS $body$
DECLARE
    v_count   integer := 0;
    v_member  record;
    c_recent CURSOR FOR
        SELECT m.member_id, sum(o.total) AS spent
          FROM shop.member m
          JOIN shop.purchase o ON o.member_id = m.member_id
         WHERE o.bought_on >= p_since
         GROUP BY m.member_id;
BEGIN
    CREATE TEMP TABLE IF NOT EXISTS tmp_award (member_id bigint, points integer) ON COMMIT DROP;

    FOR v_member IN c_recent LOOP
        IF v_member.spent > 1000 THEN
            INSERT INTO tmp_award VALUES (v_member.member_id, 100);
        ELSIF v_member.spent > 100 THEN
            INSERT INTO tmp_award VALUES (v_member.member_id, 10);
        ELSE
            CONTINUE;
        END IF;
        v_count := v_count + 1;
    END LOOP;

    SELECT count(*) INTO STRICT v_count FROM tmp_award;

    UPDATE loyalty.balance b
       SET points = b.points + a.points
      FROM tmp_award a
     WHERE a.member_id = b.member_id;

    PERFORM pg_notify('loyalty', v_count::text);
    EXECUTE format('ANALYZE %I.%I', 'loyalty', 'balance');
    RETURN v_count;
EXCEPTION
    WHEN unique_violation THEN
        INSERT INTO loyalty.error_log (raised_at, message) VALUES (now(), SQLERRM);
        RETURN -1;
END;
$body$;

CREATE OR REPLACE FUNCTION loyalty.top_members(p_limit int)
RETURNS TABLE (member_id bigint, points integer)
LANGUAGE plpgsql STABLE
AS $$
BEGIN
    RETURN QUERY
        SELECT b.member_id, b.points
          FROM loyalty.balance b
         ORDER BY b.points DESC
         LIMIT p_limit;
END;
$$;

CREATE OR REPLACE PROCEDURE loyalty.expire_points()
LANGUAGE plpgsql
AS $proc$
DECLARE
    v_rows bigint;
BEGIN
    WHILE true LOOP
        DELETE FROM loyalty.ledger
         WHERE ctid IN (SELECT ctid FROM loyalty.ledger WHERE expires_on < current_date LIMIT 1000);
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        EXIT WHEN v_rows = 0;
        COMMIT;
    END LOOP;
END;
$proc$;

CREATE OR REPLACE FUNCTION loyalty.member_count() RETURNS bigint
LANGUAGE sql
AS $$ SELECT count(*) FROM shop.member $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'loyalty') THEN
        RAISE NOTICE 'schema missing';
    END IF;
    PERFORM loyalty.award_points(current_date - 7);
    INSERT INTO loyalty.run_log (ran_at) VALUES (now()) RETURNING ran_at INTO STRICT last_run;
END;
$$;
