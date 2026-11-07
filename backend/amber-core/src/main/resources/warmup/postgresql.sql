-- Subscription revenue refresh (synthetic test script for Amber): temp tables, a
-- data-modifying CTE, SELECT INTO, upsert, a materialized view, a function and cleanup.
\set ON_ERROR_STOP on
SET search_path = billing, public;

BEGIN;

CREATE TEMP TABLE tmp_invoice_line ON COMMIT DROP AS
SELECT il.invoice_id, il.plan_code, il.amount_cents, i.account_id, i.issued_at
  FROM billing.invoice_line il
  JOIN billing.invoice i ON i.invoice_id = il.invoice_id
 WHERE i.issued_at >= date_trunc('month', now()) - interval '1 month';

CREATE INDEX ON tmp_invoice_line (account_id);

WITH voided AS (
    DELETE FROM tmp_invoice_line t
     USING billing.invoice_void v
     WHERE v.invoice_id = t.invoice_id
    RETURNING t.invoice_id
)
INSERT INTO audit.voided_invoice (invoice_id, noted_at)
SELECT invoice_id, now() FROM voided;

UPDATE tmp_invoice_line t
   SET amount_cents = t.amount_cents - d.discount_cents
  FROM billing.discount d
 WHERE d.invoice_id = t.invoice_id;

SELECT account_id, plan_code, sum(amount_cents) AS revenue_cents
  INTO TEMP tmp_account_revenue
  FROM tmp_invoice_line
 GROUP BY account_id, plan_code;

INSERT INTO reporting.account_revenue AS r (account_id, plan_code, revenue_cents, refreshed_at)
SELECT a.account_id, a.plan_code, a.revenue_cents, now()
  FROM tmp_account_revenue a
ON CONFLICT (account_id, plan_code)
DO UPDATE SET revenue_cents = EXCLUDED.revenue_cents, refreshed_at = EXCLUDED.refreshed_at;

COMMIT;

CREATE MATERIALIZED VIEW IF NOT EXISTS reporting.mv_plan_revenue AS
SELECT plan_code, sum(revenue_cents) AS revenue_cents
  FROM reporting.account_revenue
 GROUP BY plan_code;

CREATE OR REPLACE FUNCTION reporting.refresh_plan_revenue() RETURNS void
LANGUAGE plpgsql AS $fn$
BEGIN
  REFRESH MATERIALIZED VIEW reporting.mv_plan_revenue;
END;
$fn$;

CALL reporting.archive_old_revenue(12);
TRUNCATE TABLE staging.raw_invoice_line;
DROP TABLE IF EXISTS tmp_account_revenue, staging.old_snapshot;
DROP VIEW IF EXISTS reporting.v_legacy_revenue;
