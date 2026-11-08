-- Schemas, aliases (user-defined functions), aggregates, triggers, constants
CREATE SCHEMA app;
CREATE SCHEMA IF NOT EXISTS reporting AUTHORIZATION sa;
CREATE SCHEMA AUTHORIZATION app_owner;
CREATE SCHEMA ext WITH "engineParam";
ALTER SCHEMA app RENAME TO application;
ALTER SCHEMA IF EXISTS reporting RENAME TO rpt;
DROP SCHEMA application;
DROP SCHEMA IF EXISTS rpt CASCADE;
DROP SCHEMA ext RESTRICT;
CREATE ALIAS MY_SQRT FOR "java.lang.Math.sqrt";
CREATE ALIAS IF NOT EXISTS PUBLIC.GET_PROP DETERMINISTIC FOR "java.lang.System.getProperty";
CREATE ALIAS MY_HASH FOR 'com.acme.Functions.hash(java.lang.String)';
CREATE ALIAS NOBUF NOBUFFER FOR "com.acme.Functions.stream";
CREATE ALIAS REVERSE_STR AS $$
String reverse(String s) {
    return new StringBuilder(s).reverse().toString();
}
$$;
CREATE ALIAS IP_ADDRESS DETERMINISTIC AS $$
import java.net.*;
@CODE
String ipAddress(String host) throws Exception {
    return InetAddress.getByName(host).getHostAddress();
}
$$;
CREATE ALIAS ADD_ONE AS 'int addOne(int x) { return x + 1; }';
DROP ALIAS MY_SQRT;
DROP ALIAS IF EXISTS PUBLIC.GET_PROP;
CREATE AGGREGATE MEDIAN_AGG FOR "com.acme.MedianAggregate";
CREATE AGGREGATE IF NOT EXISTS app.SIMPLE_AGG FOR 'com.acme.SimpleAgg';
DROP AGGREGATE MEDIAN_AGG;
DROP AGGREGATE IF EXISTS app.SIMPLE_AGG;
CREATE TRIGGER trg_audit AFTER INSERT, UPDATE, DELETE ON test FOR EACH ROW CALL "com.acme.AuditTrigger";
CREATE TRIGGER IF NOT EXISTS trg_before BEFORE INSERT ON test FOR EACH ROW QUEUE 1024 NOWAIT CALL "com.acme.BeforeTrigger";
CREATE TRIGGER trg_stmt AFTER UPDATE ON test FOR EACH STATEMENT CALL 'com.acme.StatementTrigger';
CREATE TRIGGER trg_select BEFORE SELECT ON test CALL "com.acme.SelectTrigger";
CREATE TRIGGER trg_rollback AFTER ROLLBACK ON test FOR EACH ROW CALL "com.acme.RollbackTrigger";
CREATE TRIGGER trg_view INSTEAD OF INSERT ON v_test FOR EACH ROW CALL "com.acme.ViewInsertTrigger";
CREATE TRIGGER trg_src BEFORE UPDATE ON test FOR EACH ROW AS $$
org.h2.api.Trigger create() {
    return new org.h2.api.Trigger() {
        public void fire(java.sql.Connection conn, Object[] oldRow, Object[] newRow) {
            newRow[2] = new java.sql.Timestamp(System.currentTimeMillis());
        }
    };
}
$$;
CREATE TRIGGER trg_js AFTER INSERT ON test AS 'javascript:print("inserted");';
DROP TRIGGER trg_audit;
DROP TRIGGER IF EXISTS PUBLIC.trg_before;
CREATE CONSTANT ONE VALUE 1;
CREATE CONSTANT IF NOT EXISTS app.PI_APPROX VALUE 3.14159;
CREATE CONSTANT GREETING VALUE 'Hello' || ' World';
DROP CONSTANT ONE;
DROP CONSTANT IF EXISTS app.PI_APPROX;
