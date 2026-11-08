-- CREATE ALIAS (Java functions), triggers, CALL
CREATE ALIAS IF NOT EXISTS REVERSE_TEXT AS $$
String reverseText(String s) {
    return s == null ? null : new StringBuilder(s).reverse().toString();
}
$$;
CREATE ALIAS MY_ABS FOR 'java.lang.Math.abs(int)';
CREATE ALIAS DETERMINISTIC_SQRT DETERMINISTIC FOR 'java.lang.Math.sqrt';
CREATE ALIAS sales.GET_TABLE_LIST AS '
ResultSet tables(Connection conn) throws SQLException {
    return conn.getMetaData().getTables(null, null, null, null);
}';
SELECT REVERSE_TEXT(display_name), MY_ABS(-5) FROM sales.customer;
CALL MY_ABS(-42);
CALL DETERMINISTIC_SQRT(16.0);
DROP ALIAS IF EXISTS MY_ABS;
CREATE TRIGGER trg_order_ins AFTER INSERT ON sales.order_header FOR EACH ROW CALL 'com.example.trigger.OrderAudit';
CREATE TRIGGER IF NOT EXISTS trg_cust_upd BEFORE UPDATE ON sales.customer FOR EACH ROW QUEUE 1024 NOWAIT CALL 'com.example.trigger.CustomerCheck';
CREATE TRIGGER trg_view_ins INSTEAD OF INSERT ON sales.v_active FOR EACH ROW CALL 'com.example.trigger.ViewInsert';
CREATE TRIGGER trg_stmt AFTER INSERT, UPDATE, DELETE ON audit.event_log CALL 'com.example.trigger.LogStatement';
CREATE TRIGGER trg_select BEFORE SELECT ON audit.lookup_cache CALL 'com.example.trigger.CacheWarm';
CREATE TRIGGER trg_src AFTER INSERT ON audit.event_log FOR EACH ROW AS $$
org.h2.api.Trigger create() {
    return new org.h2.tools.TriggerAdapter() {
        public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) { }
    };
}
$$;
DROP TRIGGER IF EXISTS trg_stmt;
CREATE AGGREGATE IF NOT EXISTS sales.MEDIAN_DEV FOR 'com.example.agg.MedianDeviation';
DROP AGGREGATE IF EXISTS sales.MEDIAN_DEV;
CALL CURRENT_TIMESTAMP;
CALL CSVWRITE('/data/out/customers.csv', 'SELECT * FROM sales.customer');
SELECT * FROM CSVREAD('/data/in/customers.csv');
INSERT INTO sales.customer (display_name, email) SELECT name, mail FROM CSVREAD('/data/in/new.csv', 'name,mail', 'charset=UTF-8 fieldSeparator=;');
SELECT FILE_READ('/data/in/blob.bin');
