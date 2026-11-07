package com.github.amber.core.ir;

import com.github.amber.core.source.TextRange;

/**
 * One mention of an object by a statement.
 *
 * @param via for a call of a procedure or function defined in the script: that routine, whose body reads
 *            or writes the object when the call runs ({@code range} is then the call); null for a direct
 *            mention
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record ObjectRef(ObjectName name, ObjectKind kind, Access access, TextRange range, Confidence confidence,
                        ObjectName via) {

    public ObjectRef(ObjectName name, ObjectKind kind, Access access, TextRange range, Confidence confidence) {
        this(name, kind, access, range, confidence, null);
    }

    public ObjectRef withKind(ObjectKind newKind) {
        return new ObjectRef(name, newKind, access, range, confidence, via);
    }

    /** True when the object is touched through a called routine rather than by the statement itself. */
    public boolean indirect() {
        return via != null;
    }
}
