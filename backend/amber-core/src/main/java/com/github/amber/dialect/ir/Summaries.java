package com.github.amber.dialect.ir;

import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.i18n.Messages;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * One-line descriptions of statements in the request's language (texts: {@code amber/i18n/summary*.properties}),
 * built from the IR only (deterministic, no AI).
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
        int ctes = st.ctes().size();
        String via = ctes == 0 ? "" : t(ctes == 1 ? "summary.part.via.one" : "summary.part.via.other", ctes);
        String from = part("summary.part.from", sources);
        String called = names(st, r -> r.access() == Access.EXECUTE);
        String named = st.name() == null ? "" : " " + st.name();
        String result = switch (st.kind()) {
            case SELECT -> sources.isEmpty() ? t("summary.SELECT.empty") : t("summary.SELECT", sources, via);
            case INSERT, MERGE, SELECT_INTO, CREATE_MATERIALIZED_VIEW -> kind(st, targets, from, via);
            case UPDATE, DELETE -> kind(st, targets, part("summary.part.using", sources), via);
            case CREATE_VIEW -> kind(st, targets, part("summary.part.over", sources), via);
            case LOCK -> kind(st, sources);
            case TRUNCATE -> kind(st, targets);
            case CREATE_TABLE -> kind(st, objectWord(st), targets);
            case CREATE_TABLE_AS -> kind(st, objectWord(st), targets, from, via);
            case CREATE_INDEX -> kind(st, named, targets.contains(",") || !sources.isEmpty()
                    ? part("summary.part.on", both(st)) : onTable(st));
            case CREATE_SEQUENCE, CREATE_SYNONYM, CREATE_SCHEMA, CREATE_TYPE, CREATE_OTHER, CREATE_PROCEDURE,
                 CREATE_FUNCTION, CREATE_PACKAGE, CREATE_PACKAGE_BODY, CREATE_TYPE_BODY, ROUTINE -> kind(st, named);
            case ALTER -> targets.isEmpty() ? t("summary.ALTER.unknown") : kind(st, targets);
            case DROP -> targets.isEmpty() ? t("summary.DROP.unknown") : kind(st, objectWord(st), targets);
            case COMMENT -> sources.isEmpty() ? t("summary.COMMENT.unknown") : kind(st, sources);
            case CREATE_TRIGGER -> kind(st, named, part("summary.part.on", sources));
            case LOOP -> sources.isEmpty() ? t("summary.LOOP") : t("summary.LOOP.over", sources);
            case CURSOR -> kind(st, part("summary.part.over", sources), via);
            case CALL -> called.isEmpty() ? t("summary.CALL") : t("summary.CALL.target", called);
            case GRANT, REVOKE -> kind(st, part("summary.part.on", sources));
            case UNPARSED -> kind(st, targets.isEmpty() && sources.isEmpty() ? "" : t("summary.part.touching", both(st)));
            case RENAME, BLOCK, IF, CASE, TRY, EXCEPTION_HANDLER, DYNAMIC_SQL, DECLARE, ASSIGNMENT, RETURN, TRANSACTION,
                 SESSION, CLIENT_COMMAND, DATA, OTHER -> kind(st);
        };
        return result.strip();
    }

    /** The sentence of the statement's kind ({@code summary.<KIND>}) with its parts. */
    private static String kind(Statement st, Object... parts) {
        return t("summary." + st.kind().name(), parts);
    }

    /** A part such as " from a, b"; empty when there are no names. */
    private static String part(String key, String names) {
        return names.isEmpty() ? "" : t(key, names);
    }

    private static String t(String key, Object... args) {
        return Messages.t(key, args);
    }

    private static String objectWord(Statement st) {
        for (ObjectRef r : st.refs()) {
            if (r.access() != Access.READ) {
                return t("summary.object." + r.kind().name());
            }
        }
        return t("summary.object.TABLE");
    }

    private static String onTable(Statement st) {
        return part("summary.part.on", names(st, r -> r.access() == Access.ALTER));
    }

    private static String both(Statement st) {
        return names(st, r -> r.kind() != ObjectKind.CTE && r.kind() != ObjectKind.INDEX);
    }

    private static String names(Statement st, Predicate<ObjectRef> filter) {
        Set<String> names = st.refs().stream().filter(filter).map(r -> r.name().toString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String separator = t("summary.names.separator");
        if (names.size() <= MAX_SOURCES) {
            return String.join(separator, names);
        }
        List<String> shown = names.stream().limit(MAX_SOURCES).toList();
        return t("summary.names.more", String.join(separator, shown), names.size() - MAX_SOURCES);
    }
}
