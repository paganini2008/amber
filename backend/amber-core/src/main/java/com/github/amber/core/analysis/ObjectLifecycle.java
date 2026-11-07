package com.github.amber.core.analysis;

import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.source.TextRange;

import java.util.List;

/**
 * Everything one script does to one object, in source order: created, written, read, dropped.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record ObjectLifecycle(ObjectName name, ObjectKind kind, List<Event> events) {

    /**
     * @param step position of the statement among the script's statements in source order
     *             (see {@link Lifecycles#steps})
     */
    public record Event(int step, Statement statement, Access access, TextRange range, ObjectName via) {

        public Event(int step, Statement statement, Access access, TextRange range) {
            this(step, statement, access, range, null);
        }
    }

    /** How the object participates in the script's data flow. */
    public enum Role {
        /** Only read: data comes into the script from here. */
        SOURCE,
        /** Written and later read by the script (temp tables, staging tables). */
        INTERMEDIATE,
        /** Written and not read afterwards: where the script's results land. */
        TARGET,
        /** Only structural changes (create, alter, drop) without data flowing through it. */
        STRUCTURE
    }

    public ObjectLifecycle {
        events = List.copyOf(events);
    }

    public boolean createdInScript() {
        return events.stream().anyMatch(e -> e.access() == Access.CREATE);
    }

    public boolean droppedInScript() {
        return events.stream().anyMatch(e -> e.access() == Access.DROP);
    }

    public Role role() {
        boolean written = false;
        boolean read = false;
        for (Event e : events) {
            if (e.access().writesData() || isCreateAsSelect(e)) {
                written = true;
            } else if (e.access() == Access.READ) {
                if (written) {
                    return Role.INTERMEDIATE;
                }
                read = true;
            }
        }
        if (written) {
            return Role.TARGET;
        }
        return read ? Role.SOURCE : Role.STRUCTURE;
    }

    /** CREATE TABLE … AS SELECT and SELECT … INTO create the object and fill it in one step. */
    private static boolean isCreateAsSelect(Event e) {
        var kind = e.statement().kind();
        return e.access() == Access.CREATE
                && (kind == com.github.amber.core.ir.StatementKind.CREATE_TABLE_AS
                || kind == com.github.amber.core.ir.StatementKind.SELECT_INTO);
    }
}
