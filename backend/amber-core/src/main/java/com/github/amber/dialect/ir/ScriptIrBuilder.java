package com.github.amber.dialect.ir;

import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.UnitParse;
import com.github.amber.dialect.split.UnitKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the IR {@link Script} from a {@link ScriptParse}.
 *
 * <p>In strict mode a script with errors has no IR beyond its diagnostics: callers must check
 * {@link ScriptParse#status()} first, and this builder refuses {@link ScriptStatus#FAILED} input.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class ScriptIrBuilder {

    private ScriptIrBuilder() {
    }

    public static Script build(DialectFrontend frontend, ScriptParse parse) {
        return build(frontend, parse, new com.github.amber.core.lineage.Catalog());
    }

    /**
     * @param catalog known table metadata (imported DDL, metadata packs, online reads); the script's own
     *                DDL is added to it as statements are read, so pass a copy if it is shared
     */
    public static Script build(DialectFrontend frontend, ScriptParse parse, com.github.amber.core.lineage.Catalog catalog) {
        if (parse.status() == ScriptStatus.FAILED) {
            throw new IllegalArgumentException("Script has syntax errors; fix them or parse with fallback allowed");
        }
        SourceText source = parse.source();
        IrRules rules = frontend.irRules();
        List<Statement> statements = new ArrayList<>();
        List<com.github.amber.core.diagnostic.Diagnostic> diagnostics = new ArrayList<>(parse.diagnostics());
        for (UnitParse unit : parse.units()) {
            switch (unit.status()) {
                case SKIPPED -> statements.add(new Statement(
                        unit.unit().kind() == UnitKind.DATA ? StatementKind.DATA : StatementKind.CLIENT_COMMAND,
                        unit.unit().range(), Confidence.CERTAIN));
                case PARSED -> {
                    if (rules != null) {
                        TreeIrBuilder builder = new TreeIrBuilder(rules, source, unit.unit().range().start().offset(), catalog);
                        statements.addAll(builder.build(unit.tree()));
                        diagnostics.addAll(builder.diagnostics());
                    } else {
                        statements.add(scanned(frontend, source, unit));
                    }
                }
                case DEGRADED, FAILED -> statements.add(scanned(frontend, source, unit));
            }
        }
        resolveScriptObjects(statements);
        CallLinker.link(parse.dialect(), source, statements);
        statements.forEach(s -> s.forEach(st -> {
            if (st.summary().isEmpty()) {
                st.setSummary(Summaries.describe(st));
            }
        }));
        return new Script(parse.dialect(), source, statements, diagnostics);
    }

    private static Statement scanned(DialectFrontend frontend, SourceText source, UnitParse unit) {
        FallbackScanner.Result r = FallbackScanner.scan(frontend.dialect(), source, unit.unit().range());
        Statement st = new Statement(r.kind(), unit.unit().range(), Confidence.PARTIAL);
        r.refs().forEach(st::addRef);
        return st;
    }

    /**
     * Objects created by the script itself are known for certain, and their kind carries over to
     * later references: a table created as GLOBAL TEMPORARY stays a temp table wherever it is used.
     */
    private static void resolveScriptObjects(List<Statement> statements) {
        List<ObjectRef> created = new ArrayList<>();
        for (Statement top : statements) {
            top.forEach(st -> {
                List<ObjectRef> updated = new ArrayList<>(st.refs().size());
                for (ObjectRef ref : st.refs()) {
                    ObjectRef r = ref;
                    if (r.kind() != ObjectKind.CTE && r.access() != Access.CREATE) {
                        ObjectRef def = find(created, r.name());
                        if (def != null) {
                            ObjectKind kind = r.kind() == ObjectKind.TABLE ? def.kind() : r.kind();
                            r = new ObjectRef(r.name(), kind, r.access(), r.range(),
                                    r.confidence() == Confidence.PARTIAL ? Confidence.PARTIAL : Confidence.CERTAIN);
                        }
                    }
                    if (r.access() == Access.CREATE) {
                        created.add(r);
                    }
                    updated.add(r);
                }
                st.replaceRefs(updated);
            });
        }
    }

    private static ObjectRef find(List<ObjectRef> created, ObjectName name) {
        for (int i = created.size() - 1; i >= 0; i--) {
            if (created.get(i).name().sameObject(name)) {
                return created.get(i);
            }
        }
        return null;
    }
}
