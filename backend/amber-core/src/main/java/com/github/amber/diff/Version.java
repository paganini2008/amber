package com.github.amber.diff;

import com.github.amber.core.Dialect;
import com.github.amber.core.analysis.Lifecycles;
import com.github.amber.core.analysis.ObjectLifecycle;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.lineage.LineageGraph;
import com.github.amber.core.source.SourceText;
import com.github.amber.core.source.TextRange;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ParseOptions;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.dialect.findings.Findings;
import com.github.amber.dialect.ir.ScriptIrBuilder;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * One analyzed side of a diff: the parse (strict, or fallback when strict fails), the IR, and every
 * derived view the comparison needs, plus one {@link Node} per statement.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class Version {

    /** A statement of this version with what alignment needs. */
    static final class Node {
        final Statement st;
        final int id;
        final Node parent;
        final List<Node> children = new ArrayList<>();
        final String target;
        final String targetDisplay;
        final boolean definesName;
        private final Version version;
        private List<SqlTokens.Tok> tokens;
        private long hash;
        private boolean hashed;

        Node(Version version, Statement st, int id, Node parent) {
            this.version = version;
            this.st = st;
            this.id = id;
            this.parent = parent;
            ObjectRef write = null;
            for (ObjectRef r : st.refs()) {
                if (r.kind() != ObjectKind.CTE && r.access() != Access.READ && r.access() != Access.EXECUTE
                        && !r.indirect()) {
                    write = r;
                    break;
                }
            }
            this.definesName = st.name() != null;
            if (st.name() != null) {
                target = st.name().key();
                targetDisplay = st.name().toString();
            } else if (write != null) {
                target = write.name().key();
                targetDisplay = write.name().toString();
            } else {
                target = "";
                targetDisplay = null;
            }
        }

        List<SqlTokens.Tok> tokens() {
            if (tokens == null) {
                TextRange r = st.range();
                tokens = SqlTokens.of(version.dialect, version.source.text(), r.start().offset(), r.end().offset());
            }
            return tokens;
        }

        long hash() {
            if (!hashed) {
                hash = SqlTokens.hash(tokens());
                hashed = true;
            }
            return hash;
        }

        /** Alignment signature: kind, target and normalized text. */
        String signature() {
            return st.kind() + "|" + target + "|" + Long.toHexString(hash());
        }

        String raw() {
            return version.source.slice(st.range());
        }

        boolean container() {
            return st.kind().isContainer();
        }

        /** Enclosing containers, outermost first, e.g. {@code CREATE_PROCEDURE p_load (line 3)}. */
        List<String> path() {
            List<String> out = new ArrayList<>();
            for (Node p = parent; p != null; p = p.parent) {
                out.add(0, p.describe());
            }
            return out;
        }

        String describe() {
            return st.kind() + (targetDisplay == null || !definesName ? "" : " " + targetDisplay)
                    + " (line " + st.range().start().line() + ")";
        }

        /** Short label for messages, e.g. {@code UPDATE orders}. */
        String label() {
            return st.kind() + (targetDisplay == null ? "" : " " + targetDisplay);
        }
    }

    final Dialect dialect;
    final SourceText source;
    final ScriptParse parse;
    final boolean fallback;
    final Script script;
    final List<Statement> steps;
    final Map<Statement, Integer> ids = new IdentityHashMap<>();
    final List<Node> roots = new ArrayList<>();
    final List<Node> nodes = new ArrayList<>();
    final List<ObjectLifecycle> lifecycles;
    final LineageGraph graph;
    final List<Finding> findings;
    final String findingsError;

    private Version(Dialect dialect, SourceText source, ScriptParse parse, boolean fallback, Script script,
                    List<Finding> findings, String findingsError) {
        this.dialect = dialect;
        this.source = source;
        this.parse = parse;
        this.fallback = fallback;
        this.script = script;
        this.steps = Lifecycles.steps(script);
        for (int i = 0; i < steps.size(); i++) {
            ids.put(steps.get(i), i);
        }
        for (Statement st : script.statements()) {
            roots.add(build(st, null));
        }
        this.lifecycles = Lifecycles.of(script);
        this.graph = LineageGraph.of(script);
        this.findings = findings;
        this.findingsError = findingsError;
    }

    private Node build(Statement st, Node parent) {
        Node n = new Node(this, st, ids.get(st), parent);
        nodes.add(n);
        for (Statement c : st.children()) {
            n.children.add(build(c, n));
        }
        return n;
    }

    /** Parses strictly, falling back to fallback parsing when strict parsing fails, and analyzes. */
    static Version analyze(Dialect dialect, SourceText source, Catalog catalog) {
        return analyze(dialect, source, catalog, null);
    }

    /** As {@link #analyze(Dialect, SourceText, Catalog)}, with the user's rule settings applied to findings. */
    static Version analyze(Dialect dialect, SourceText source, Catalog catalog,
                           com.github.amber.dialect.findings.RuleConfig rules) {
        DialectFrontend f = DialectFrontends.get(dialect);
        ScriptParse parse = f.parse(source, ParseOptions.STRICT);
        boolean fallback = false;
        if (parse.status() == ScriptStatus.FAILED) {
            parse = f.parse(source, ParseOptions.FALLBACK);
            fallback = true;
        }
        Script script = ScriptIrBuilder.build(f, parse, catalog.copy());
        List<Finding> findings;
        String error = null;
        try {
            findings = Findings.of(script, catalog, rules);
        } catch (RuntimeException e) {
            findings = List.of();                  // findings are hints; never fail the diff
            error = e.toString();
        }
        return new Version(dialect, source, parse.withoutTrees(), fallback, script, findings, error);   // trees: ~1 GB / 100k lines
    }

    Integer id(Statement st) {
        return st == null ? null : ids.get(st);
    }

    int unparsed() {
        int n = 0;
        for (Statement st : steps) {
            if (st.kind() == StatementKind.UNPARSED) {
                n++;
            }
        }
        return n;
    }

    TextRange range(int stepId) {
        return steps.get(stepId).range();
    }
}
