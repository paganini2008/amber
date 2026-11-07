package com.github.amber.dialect.ir;

import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;

/**
 * Declares that the {@code occurrence}-th child of rule {@code childRule} under a {@code parentRule}
 * node is the object a statement acts on, e.g. {@code (insert_into_clause, general_table_ref, 0, INSERT)}.
 * Object names inside that child (but not inside its subqueries) get {@code access} and {@code kind}.
 * When the child itself is not a name rule (for example {@code table_name} or {@code procedure_name}),
 * the child's own text is the name.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record TargetSpec(String parentRule, String childRule, int occurrence, Access access, ObjectKind kind) {

    public static final int ALL = -1;

    /** Only the first object name inside the first matching child, as in MySQL {@code UPDATE a JOIN b …}. */
    public static final int FIRST_NAME = -2;

    public static TargetSpec firstName(String parentRule, String childRule, Access access) {
        return new TargetSpec(parentRule, childRule, FIRST_NAME, access, ObjectKind.TABLE);
    }

    public static TargetSpec all(String parentRule, String childRule, Access access, ObjectKind kind) {
        return new TargetSpec(parentRule, childRule, ALL, access, kind);
    }

    public static TargetSpec of(String parentRule, String childRule, Access access, ObjectKind kind) {
        return new TargetSpec(parentRule, childRule, 0, access, kind);
    }

    public static TargetSpec of(String parentRule, String childRule, Access access) {
        return new TargetSpec(parentRule, childRule, 0, access, ObjectKind.TABLE);
    }
}
