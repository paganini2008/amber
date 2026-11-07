-- =====================================================================
-- Audit infrastructure: Java aliases, triggers (class and inline source),
-- constants, roles and grants
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS audit;

CREATE CONSTANT IF NOT EXISTS audit.VERSION VALUE 3;

CREATE TABLE IF NOT EXISTS audit.change_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    table_name  VARCHAR(128) NOT NULL,
    operation   CHAR(1) NOT NULL CHECK (operation IN ('I', 'U', 'D')),
    changed_by  VARCHAR(128) DEFAULT CURRENT_USER,
    changed_at  TIMESTAMP DEFAULT LOCALTIMESTAMP,
    old_row     JSON,
    new_row     JSON
);

CREATE ALIAS IF NOT EXISTS audit.TO_JSON_ROW DETERMINISTIC AS $$
import java.util.*;
@CODE
String toJsonRow(Object[] row) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < row.length; i++) {
        if (i > 0) sb.append(',');
        sb.append(row[i] == null ? "null" : "\"" + row[i] + "\"");
    }
    return sb.append(']').toString();
}
$$;

CREATE ALIAS IF NOT EXISTS audit.SLEEP FOR "java.lang.Thread.sleep(long)";
CREATE ALIAS IF NOT EXISTS audit.ENV FOR "java.lang.System.getenv(java.lang.String)";

CREATE TRIGGER IF NOT EXISTS audit.trg_customer_audit
    AFTER INSERT, UPDATE, DELETE ON shop.customer
    FOR EACH ROW
    CALL "com.example.audit.JsonAuditTrigger";

CREATE TRIGGER IF NOT EXISTS audit.trg_order_touch
    BEFORE UPDATE ON shop.orders
    FOR EACH ROW
AS $$
org.h2.api.Trigger create() {
    return new org.h2.tools.TriggerAdapter() {
        @Override
        public void fire(java.sql.Connection conn, java.sql.ResultSet oldRow, java.sql.ResultSet newRow)
                throws java.sql.SQLException {
            newRow.updateTimestamp("UPDATED_AT", new java.sql.Timestamp(System.currentTimeMillis()));
        }
    };
}
$$;

CREATE TRIGGER audit.trg_order_stmt AFTER INSERT ON shop.orders FOR EACH STATEMENT QUEUE 64 NOWAIT
    CALL "com.example.audit.StatementCounter";

CREATE ROLE IF NOT EXISTS auditor;
GRANT SELECT ON SCHEMA audit TO auditor;
CREATE USER IF NOT EXISTS audit_reader PASSWORD 'change-me';
GRANT auditor TO audit_reader;

SELECT audit.TO_JSON_ROW(ARRAY[id, display_name]) FROM shop.customer WHERE id = ?;
CALL audit.SLEEP(10);

-- purge old audit rows in batches
DELETE FROM audit.change_log
WHERE changed_at < DATEADD('DAY', -90, CURRENT_TIMESTAMP)
LIMIT 10000;

COMMENT ON TRIGGER audit.trg_customer_audit IS 'Writes JSON rows into audit.change_log';
COMMENT ON ALIAS audit.TO_JSON_ROW IS 'Serializes a row array as JSON';
