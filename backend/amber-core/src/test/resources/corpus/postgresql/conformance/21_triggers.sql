-- Triggers and event triggers
CREATE OR REPLACE FUNCTION audit.touch_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;
CREATE TRIGGER customer_touch BEFORE UPDATE ON sales.customer FOR EACH ROW EXECUTE FUNCTION audit.touch_updated_at();
CREATE OR REPLACE TRIGGER customer_touch BEFORE INSERT OR UPDATE OF name, email ON sales.customer FOR EACH ROW WHEN (OLD.* IS DISTINCT FROM NEW.*) EXECUTE FUNCTION audit.touch_updated_at();
CREATE TRIGGER orders_audit AFTER INSERT OR UPDATE OR DELETE ON sales.orders REFERENCING NEW TABLE AS new_rows OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION audit.log_changes();
CREATE TRIGGER orders_trunc AFTER TRUNCATE ON sales.orders EXECUTE PROCEDURE audit.log_truncate('orders');
CREATE TRIGGER v_instead INSTEAD OF INSERT ON sales.v_open_orders FOR EACH ROW EXECUTE FUNCTION sales.v_insert();
CREATE CONSTRAINT TRIGGER orders_check AFTER INSERT ON sales.orders DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION sales.check_order();
CREATE CONSTRAINT TRIGGER t_from AFTER UPDATE ON sales.orders FROM sales.customer NOT DEFERRABLE FOR EACH ROW EXECUTE FUNCTION sales.check_order();
ALTER TABLE sales.customer DISABLE TRIGGER customer_touch;
ALTER TABLE sales.customer ENABLE ALWAYS TRIGGER customer_touch;
ALTER TABLE sales.customer DISABLE TRIGGER ALL;
ALTER TRIGGER customer_touch ON sales.customer RENAME TO customer_touch2;
DROP TRIGGER IF EXISTS customer_touch2 ON sales.customer CASCADE;
CREATE FUNCTION audit.on_ddl() RETURNS event_trigger LANGUAGE plpgsql AS $$ BEGIN RAISE NOTICE 'ddl: %', tg_tag; END $$;
CREATE EVENT TRIGGER ddl_logger ON ddl_command_end WHEN TAG IN ('CREATE TABLE', 'ALTER TABLE') EXECUTE FUNCTION audit.on_ddl();
ALTER EVENT TRIGGER ddl_logger DISABLE;
DROP EVENT TRIGGER ddl_logger;
