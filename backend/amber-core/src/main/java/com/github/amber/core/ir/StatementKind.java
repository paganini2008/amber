package com.github.amber.core.ir;

/**
 * What a statement is. Container kinds hold child statements (procedure bodies, blocks, branches).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public enum StatementKind {
    // queries and DML
    SELECT(Category.QUERY),
    INSERT(Category.DML),
    UPDATE(Category.DML),
    DELETE(Category.DML),
    MERGE(Category.DML),
    /** {@code SELECT … INTO new_table} (SQL Server, PostgreSQL). */
    SELECT_INTO(Category.DML),
    LOCK(Category.DML),

    // DDL
    CREATE_TABLE(Category.DDL),
    CREATE_TABLE_AS(Category.DDL),
    CREATE_VIEW(Category.DDL),
    CREATE_MATERIALIZED_VIEW(Category.DDL),
    CREATE_INDEX(Category.DDL),
    CREATE_SEQUENCE(Category.DDL),
    CREATE_SCHEMA(Category.DDL),
    CREATE_SYNONYM(Category.DDL),
    CREATE_TYPE(Category.DDL),
    CREATE_OTHER(Category.DDL),
    ALTER(Category.DDL),
    DROP(Category.DDL),
    TRUNCATE(Category.DDL),
    RENAME(Category.DDL),
    COMMENT(Category.DDL),

    // routines and programs (containers)
    CREATE_PROCEDURE(Category.PROGRAM),
    CREATE_FUNCTION(Category.PROGRAM),
    CREATE_PACKAGE(Category.PROGRAM),
    CREATE_PACKAGE_BODY(Category.PROGRAM),
    CREATE_TRIGGER(Category.PROGRAM),
    CREATE_TYPE_BODY(Category.PROGRAM),
    /** A procedure or function defined inside a package body or declare section. */
    ROUTINE(Category.PROGRAM),
    BLOCK(Category.PROGRAM),

    // control flow (containers)
    IF(Category.CONTROL),
    LOOP(Category.CONTROL),
    CASE(Category.CONTROL),
    TRY(Category.CONTROL),
    EXCEPTION_HANDLER(Category.CONTROL),

    // procedural statements
    CURSOR(Category.PROCEDURAL),
    CALL(Category.PROCEDURAL),
    DYNAMIC_SQL(Category.PROCEDURAL),
    DECLARE(Category.PROCEDURAL),
    ASSIGNMENT(Category.PROCEDURAL),
    RETURN(Category.PROCEDURAL),

    // other
    GRANT(Category.DCL),
    REVOKE(Category.DCL),
    TRANSACTION(Category.TCL),
    SESSION(Category.OTHER),
    CLIENT_COMMAND(Category.CLIENT),
    DATA(Category.CLIENT),
    /** A unit that failed to parse; only scanned in fallback mode. */
    UNPARSED(Category.OTHER),
    OTHER(Category.OTHER);

    public enum Category {
        QUERY, DML, DDL, PROGRAM, CONTROL, PROCEDURAL, DCL, TCL, CLIENT, OTHER
    }

    private final Category category;

    StatementKind(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }

    /** True when the statement holds child statements. */
    public boolean isContainer() {
        return category == Category.PROGRAM || category == Category.CONTROL;
    }
}
