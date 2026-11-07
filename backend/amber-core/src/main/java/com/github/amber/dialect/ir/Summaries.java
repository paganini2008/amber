package com.github.amber.dialect.ir;

import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Statement;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One-line, plain-English descriptions of statements, built from the IR only (deterministic, no AI).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class Summaries {

    private static final int MAX_SOURCES = 4;

    private Summaries() {
    }

    public static String describe(Statement st) {
        String targets = names(st, r -> r.access() != Access.READ && r.access() != Access.EXECUTE);
        String sources = names(st, r -> r.access() == Access.READ && r.kind() != ObjectKind.CTE);
        String via = st.ctes().isEmpty() ? "" : " via " + st.ctes().size() + (st.ctes().size() == 1 ? " CTE" : " CTEs");
        String from = sources.isEmpty() ? "" : " from " + sources;
        String called = names(st, r -> r.access() == Access.EXECUTE);
        String named = st.name() == null ? "" : " " + st.name();
        String result = switch (st.kind()) {
            case SELECT -> sources.isEmpty() ? "Query" : "Query " + sources + via;
            case INSERT -> "Insert into " + targets + from + via;
            case UPDATE -> "Update " + targets + (sources.isEmpty() ? "" : " using " + sources) + via;
            case DELETE -> "Delete from " + targets + (sources.isEmpty() ? "" : " using " + sources) + via;
            case MERGE -> "Merge into " + targets + from + via;
            case SELECT_INTO -> "Select into new table " + targets + from + via;
            case LOCK -> "Lock " + sources;
            case CREATE_TABLE -> "Create " + objectWord(st) + " " + targets;
            case CREATE_TABLE_AS -> "Create " + objectWord(st) + " " + targets + from + via;
            case CREATE_VIEW -> "Create view " + targets + (sources.isEmpty() ? "" : " over " + sources) + via;
            case CREATE_MATERIALIZED_VIEW -> "Create materialized view " + targets + from + via;
            case CREATE_INDEX -> "Create index" + named + (targets.contains(",") || !sources.isEmpty() ? " on " + both(st) : onTable(st));
            case CREATE_SEQUENCE -> "Create sequence" + named;
            case CREATE_SYNONYM -> "Create synonym" + named;
            case CREATE_SCHEMA -> "Create schema" + named;
            case CREATE_TYPE -> "Create type" + named;
            case CREATE_OTHER -> "Create" + named;
            case ALTER -> "Alter " + (targets.isEmpty() ? "object" : targets);
            case DROP -> "Drop " + (targets.isEmpty() ? "object" : objectWord(st) + " " + targets);
            case TRUNCATE -> "Truncate " + targets;
            case RENAME -> "Rename object";
            case COMMENT -> "Comment on " + (sources.isEmpty() ? "object" : sources);
            case CREATE_PROCEDURE -> "Create procedure" + named;
            case CREATE_FUNCTION -> "Create function" + named;
            case CREATE_PACKAGE -> "Create package" + named;
            case CREATE_PACKAGE_BODY -> "Create package body" + named;
            case CREATE_TRIGGER -> "Create trigger" + named + (sources.isEmpty() ? "" : " on " + sources);
            case CREATE_TYPE_BODY -> "Create type body" + named;
            case ROUTINE -> "Routine" + named;
            case BLOCK -> "Block";
            case IF -> "If";
            case LOOP -> sources.isEmpty() ? "Loop" : "Loop over " + sources;
            case CASE -> "Case";
            case TRY -> "Try";
            case EXCEPTION_HANDLER -> "Exception handler";
            case CURSOR -> "Cursor" + (sources.isEmpty() ? "" : " over " + sources) + via;
            case CALL -> called.isEmpty() ? "Call" : "Call " + called;
            case DYNAMIC_SQL -> "Dynamic SQL";
            case DECLARE -> "Declare";
            case ASSIGNMENT -> "Assignment";
            case RETURN -> "Return";
            case GRANT -> "Grant" + (sources.isEmpty() ? "" : " on " + sources);
            case REVOKE -> "Revoke" + (sources.isEmpty() ? "" : " on " + sources);
            case TRANSACTION -> "Transaction control";
            case SESSION -> "Session setting";
            case CLIENT_COMMAND -> "Client command";
            case DATA -> "Inline data";
            case UNPARSED -> "Unparsed statement" + (targets.isEmpty() && sources.isEmpty() ? "" : " touching " + both(st));
            case OTHER -> "Statement";
        };
        return result.strip();
    }

    private static String objectWord(Statement st) {
        for (ObjectRef r : st.refs()) {
            if (r.access() != Access.READ) {
                return switch (r.kind()) {
                    case TEMP_TABLE -> "temp table";
                    case TABLE_VARIABLE -> "table variable";
                    case VIEW -> "view";
                    case MATERIALIZED_VIEW -> "materialized view";
                    case SEQUENCE -> "sequence";
                    case INDEX -> "index";
                    case PROCEDURE -> "procedure";
                    case FUNCTION -> "function";
                    case PACKAGE -> "package";
                    case TRIGGER -> "trigger";
                    default -> r.kind().name().toLowerCase(Locale.ROOT).replace('_', ' ');
                };
            }
        }
        return "table";
    }

    private static String onTable(Statement st) {
        String t = names(st, r -> r.access() == Access.ALTER);
        return t.isEmpty() ? "" : " on " + t;
    }

    private static String both(Statement st) {
        return names(st, r -> r.kind() != ObjectKind.CTE && r.kind() != ObjectKind.INDEX);
    }

    private static String names(Statement st, java.util.function.Predicate<ObjectRef> filter) {
        Set<String> names = st.refs().stream().filter(filter).map(r -> r.name().toString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (names.size() <= MAX_SOURCES) {
            return String.join(", ", names);
        }
        List<String> shown = names.stream().limit(MAX_SOURCES).toList();
        return String.join(", ", shown) + " and " + (names.size() - MAX_SOURCES) + " more";
    }
}
