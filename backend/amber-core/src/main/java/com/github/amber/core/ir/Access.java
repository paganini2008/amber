package com.github.amber.core.ir;

/**
 * What a statement does to an object.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public enum Access {
    CREATE,
    READ,
    INSERT,
    UPDATE,
    DELETE,
    MERGE,
    TRUNCATE,
    ALTER,
    DROP,
    /** Calling a procedure or function. */
    EXECUTE;

    /** True when the access changes the object's rows. */
    public boolean writesData() {
        return this == INSERT || this == UPDATE || this == DELETE || this == MERGE || this == TRUNCATE;
    }
}
