-- Long realistic ETL: inventory snapshot with partition maintenance and upserts
SET statement_timeout = '30min';
SET client_min_messages TO warning;

CREATE TABLE IF NOT EXISTS inv.stock_snapshot (
    snapshot_date date NOT NULL,
    warehouse_id  int  NOT NULL,
    sku           text NOT NULL,
    on_hand       int  NOT NULL CHECK (on_hand >= 0),
    reserved      int  NOT NULL DEFAULT 0,
    available     int  GENERATED ALWAYS AS (on_hand - reserved) STORED,
    PRIMARY KEY (snapshot_date, warehouse_id, sku)
) PARTITION BY RANGE (snapshot_date);

CREATE TABLE IF NOT EXISTS inv.stock_snapshot_2024_06 PARTITION OF inv.stock_snapshot
    FOR VALUES FROM ('2024-06-01') TO ('2024-07-01');

CREATE UNLOGGED TABLE IF NOT EXISTS inv.stage_movement (
    movement_id bigint,
    warehouse_id int,
    sku text,
    delta int,
    reason text,
    moved_at timestamptz
);

TRUNCATE inv.stage_movement;

COPY inv.stage_movement FROM '/var/lib/etl/in/movements.csv' WITH (FORMAT csv, HEADER, NULL 'NULL');

BEGIN ISOLATION LEVEL REPEATABLE READ;

LOCK TABLE inv.stock_current IN SHARE ROW EXCLUSIVE MODE;

WITH agg AS (
    SELECT warehouse_id, sku, sum(delta) AS net_delta,
           sum(delta) FILTER (WHERE reason = 'RESERVE') AS reserve_delta
    FROM inv.stage_movement
    GROUP BY warehouse_id, sku
), applied AS (
    INSERT INTO inv.stock_current AS s (warehouse_id, sku, on_hand, reserved)
    SELECT warehouse_id, sku, greatest(net_delta, 0), coalesce(reserve_delta, 0) FROM agg
    ON CONFLICT (warehouse_id, sku) DO UPDATE
        SET on_hand  = greatest(s.on_hand + EXCLUDED.on_hand, 0),
            reserved = s.reserved + EXCLUDED.reserved
    RETURNING s.warehouse_id, s.sku, s.on_hand
)
INSERT INTO inv.movement_log (warehouse_id, sku, on_hand_after, logged_at)
SELECT warehouse_id, sku, on_hand, now() FROM applied;

SAVEPOINT before_snapshot;

INSERT INTO inv.stock_snapshot (snapshot_date, warehouse_id, sku, on_hand, reserved)
SELECT current_date, warehouse_id, sku, on_hand, reserved
FROM inv.stock_current
ON CONFLICT (snapshot_date, warehouse_id, sku) DO UPDATE
    SET on_hand = EXCLUDED.on_hand, reserved = EXCLUDED.reserved;

DELETE FROM inv.stock_current sc
USING inv.discontinued d
WHERE d.sku = sc.sku AND sc.on_hand = 0
RETURNING sc.warehouse_id, sc.sku;

RELEASE SAVEPOINT before_snapshot;

COMMIT;

-- Pivot-style report using FILTER (PostgreSQL has no PIVOT)
SELECT sku,
       sum(available) FILTER (WHERE warehouse_id = 1) AS wh_1,
       sum(available) FILTER (WHERE warehouse_id = 2) AS wh_2,
       sum(available) FILTER (WHERE warehouse_id = 3) AS wh_3,
       sum(available) AS total
FROM inv.stock_snapshot
WHERE snapshot_date = current_date
GROUP BY sku
HAVING sum(available) < 10
ORDER BY total, sku
FETCH FIRST 50 ROWS ONLY;

-- Unpivot-style using LATERAL VALUES
SELECT s.sku, v.metric, v.val
FROM inv.stock_snapshot s
CROSS JOIN LATERAL (VALUES ('on_hand', s.on_hand), ('reserved', s.reserved), ('available', s.available)) AS v(metric, val)
WHERE s.snapshot_date = current_date;

-- Gaps and islands on stock-outs
SELECT warehouse_id, sku, min(snapshot_date) AS out_from, max(snapshot_date) AS out_to
FROM (
    SELECT warehouse_id, sku, snapshot_date,
           snapshot_date - (row_number() OVER (PARTITION BY warehouse_id, sku ORDER BY snapshot_date))::int AS grp
    FROM inv.stock_snapshot
    WHERE available <= 0
) t
GROUP BY warehouse_id, sku, grp
HAVING count(*) >= 3;

CREATE OR REPLACE FUNCTION inv.drop_old_snapshots(p_keep_months int) RETURNS int
LANGUAGE plpgsql AS $$
DECLARE
    v_cnt int := 0;
    r record;
BEGIN
    FOR r IN
        SELECT c.relname
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_class p ON p.oid = i.inhparent
        WHERE p.relname = 'stock_snapshot'
          AND c.relname < 'stock_snapshot_' || to_char(current_date - make_interval(months => p_keep_months), 'YYYY_MM')
    LOOP
        EXECUTE format('ALTER TABLE inv.stock_snapshot DETACH PARTITION inv.%I', r.relname);
        EXECUTE format('DROP TABLE inv.%I', r.relname);
        v_cnt := v_cnt + 1;
    END LOOP;
    RETURN v_cnt;
END;
$$;

SELECT inv.drop_old_snapshots(24);

DROP TABLE IF EXISTS inv.stage_movement;
RESET statement_timeout;
RESET client_min_messages;
