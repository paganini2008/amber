package com.github.amber.core.ir;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public enum ObjectKind {
    TABLE,
    TEMP_TABLE,
    TABLE_VARIABLE,
    VIEW,
    MATERIALIZED_VIEW,
    CTE,
    INDEX,
    SEQUENCE,
    PROCEDURE,
    FUNCTION,
    PACKAGE,
    TRIGGER,
    TYPE,
    SYNONYM,
    SCHEMA,
    /** A routine call whose target kind is unknown (procedure, function or package member). */
    ROUTINE,
    OTHER
}
