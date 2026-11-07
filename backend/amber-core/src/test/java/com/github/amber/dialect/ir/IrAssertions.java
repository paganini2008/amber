package com.github.amber.dialect.ir;

import com.github.amber.core.analysis.ObjectLifecycle;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Shared helpers for IR tests.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class IrAssertions {

    private IrAssertions() {
    }

    static Script build(DialectFrontend frontend, String resource) throws IOException {
        ScriptParse parse = frontend.parse(new SourceText(resource, read(resource)));
        assertEquals(ScriptStatus.SUCCESS, parse.status(), () -> parse.diagnostics().toString());
        return ScriptIrBuilder.build(frontend, parse);
    }

    static String read(String resource) throws IOException {
        try (InputStream in = IrAssertions.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Statement first(List<Statement> statements, StatementKind kind) {
        return statements.stream().filter(s -> s.kind() == kind).findFirst()
                .orElseThrow(() -> new AssertionError(kind + " not in " + statements));
    }

    static List<Statement> all(List<Statement> statements, StatementKind kind) {
        return statements.stream().filter(s -> s.kind() == kind).toList();
    }

    /** Non-read refs as "ACCESS KIND KEY". */
    static List<String> writes(Statement st) {
        return st.refs().stream().filter(r -> r.access() != Access.READ)
                .map(r -> r.access() + " " + r.kind() + " " + r.name().key()).toList();
    }

    /** Distinct read keys, excluding CTEs. */
    static List<String> reads(Statement st) {
        return st.refs().stream().filter(r -> r.access() == Access.READ && r.kind() != ObjectKind.CTE)
                .map(r -> r.name().key()).distinct().toList();
    }

    static Map<String, ObjectRef> byKey(Statement st) {
        return st.refs().stream().collect(Collectors.toMap(r -> r.name().key(), r -> r, (a, b) -> a));
    }

    static ObjectLifecycle lifecycle(List<ObjectLifecycle> lifecycles, String key) {
        return lifecycles.stream().filter(l -> l.name().key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError(key + " not in " + lifecycles.stream().map(l -> l.name().key()).toList()));
    }

    static List<Access> accesses(ObjectLifecycle l) {
        return l.events().stream().map(ObjectLifecycle.Event::access).toList();
    }

    static List<StatementKind> kinds(List<Statement> statements) {
        return statements.stream().map(Statement::kind).toList();
    }
}
