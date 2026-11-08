package com.github.amber.dialect.ir;

import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.source.SourceText;
import com.github.amber.core.source.TextRange;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the parse tree of one statement unit into IR statements, driven by {@link IrRules}.
 *
 * <ul>
 *   <li>A node whose rule maps to a {@link StatementKind} starts a statement when it is at top level
 *       or directly inside a container (procedure, block, IF, LOOP…). Inside a non-container statement
 *       the same rules are subqueries and stay part of that statement.</li>
 *   <li>Every name-rule node inside a statement becomes an {@link ObjectRef}. Names in a
 *       {@link TargetSpec} position get the spec's access (INSERT, CREATE, DROP…); the rest are READ.
 *       Names matching a CTE of the statement are CTE references.</li>
 *   <li>Name-rule nodes met directly by a container (a cursor loop's query, a trigger's table) are
 *       READ references of the container.</li>
 * </ul>
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class TreeIrBuilder {

    private final IrRules rules;
    private final SourceText source;
    private final int base;
    private final List<com.github.amber.core.diagnostic.Diagnostic> diagnostics = new ArrayList<>();
    private final com.github.amber.core.lineage.Catalog catalog;
    /** Variables of the tree (procedural code), created on first use; {@code null} for dialects without variables. */
    private com.github.amber.dialect.lineage.VariableScopes variables;
    private boolean variablesChecked;

    /**
     * @param base offset of the unit in the script; token offsets in the tree are relative to the unit
     */
    public TreeIrBuilder(IrRules rules, SourceText source, int base) {
        this(rules, source, base, new com.github.amber.core.lineage.Catalog());
    }

    /** @param catalog table columns known so far in the script; updated by DDL and CREATE TABLE AS */
    public TreeIrBuilder(IrRules rules, SourceText source, int base, com.github.amber.core.lineage.Catalog catalog) {
        this.rules = rules;
        this.source = source;
        this.base = base;
        this.catalog = catalog;
    }

    /** Uses a scope prepared by the caller instead of the tree's own (statements of a PL/pgSQL body). */
    public TreeIrBuilder withVariables(com.github.amber.dialect.lineage.VariableScopes scopes) {
        this.variables = scopes;
        this.variablesChecked = true;
        return this;
    }

    private com.github.amber.dialect.lineage.VariableScopes variables() {
        if (!variablesChecked) {
            variablesChecked = true;
            var lr = rules.lineage();
            if (lr != null && lr.hooks().variables() != null) {
                variables = com.github.amber.dialect.lineage.VariableScopes.tree(rules, lr.hooks().variables(), source, base);
            }
        }
        return variables;
    }

    public List<Statement> build(ParserRuleContext root) {
        List<Statement> top = new ArrayList<>();
        visit(root, null, top);
        return top;
    }

    /** Warnings raised while enriching statements (e.g. unparsable PL/pgSQL body statements). */
    public List<com.github.amber.core.diagnostic.Diagnostic> diagnostics() {
        return diagnostics;
    }

    // ----- walking ------------------------------------------------------------------------------

    private void visit(ParseTree node, Statement container, List<Statement> top) {
        if (node instanceof TerminalNode token) {
            Access access = container == null ? null : rules.nameToken(token);
            ObjectName name = access == null ? null : nameOf(token);
            if (name != null) {
                container.addRef(new ObjectRef(name, ObjectKind.TABLE, access, range(token), Confidence.INFERRED));
            }
            return;
        }
        if (!(node instanceof ParserRuleContext ctx) || rules.isOpaqueRule(rules.ruleName(ctx))) {
            return;
        }
        StatementKind kind = rules.kindOf(ctx);
        if (kind != null) {
            Statement st = new Statement(kind, range(ctx), Confidence.INFERRED);
            if (container == null) {
                top.add(st);
            } else {
                container.addChild(st);
            }
            if (kind.isContainer()) {
                defineName(ctx, st);
                if (variables() != null) {
                    variables.register(ctx, st, container);
                }
                for (int i = 0; i < ctx.getChildCount(); i++) {
                    visit(ctx.getChild(i), st, top);
                }
                containerLineage(ctx, st);
            } else {
                collect(ctx, st);
                lineage(ctx, st);
            }
            diagnostics.addAll(rules.enrich(st, ctx, source, catalog));
            return;
        }
        if (container != null && rules.isNameRule(rules.ruleName(ctx)) && rules.acceptName(ctx)) {
            ObjectName name = qualifiedNameOf(ctx);
            if (name != null) {
                container.addRef(new ObjectRef(name, ObjectKind.TABLE, Access.READ, range(ctx), Confidence.INFERRED));
            }
            return;
        }
        for (int i = 0; i < ctx.getChildCount(); i++) {
            visit(ctx.getChild(i), container, top);
        }
    }

    /**
     * For containers: the name the program defines (procedure, package, trigger…), looked up on the
     * node itself and on its direct children (e.g. MySQL {@code createStatement → createProcedure}).
     */
    private void defineName(ParserRuleContext ctx, Statement st) {
        if (defineNameOn(ctx, st)) {
            return;
        }
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (ctx.getChild(i) instanceof ParserRuleContext c && defineNameOn(c, st)) {
                return;
            }
        }
    }

    private boolean defineNameOn(ParserRuleContext ctx, Statement st) {
        String rule = rules.ruleName(ctx);
        for (TargetSpec spec : rules.targets()) {
            if (!spec.parentRule().equals(rule)) {
                continue;
            }
            for (ParserRuleContext child : childrenOf(ctx, spec)) {
                ObjectName name = qualifiedNameOf(child);
                if (name != null) {
                    st.setName(name);
                    st.addRef(new ObjectRef(name, spec.kind(), spec.access(), range(child), Confidence.CERTAIN));
                    return true;
                }
            }
        }
        return false;
    }

    /** Column lineage; a failure here never affects the rest of the analysis. */
    private void lineage(ParserRuleContext ctx, Statement st) {
        var lr = rules.lineage();
        if (lr == null) {
            return;
        }
        try {
            var extractor = new com.github.amber.dialect.lineage.LineageExtractor(rules, lr, source, base, catalog)
                    .variables(variables());
            st.setLineage(extractor.extract(st, ctx));
            st.setStructure(extractor.structure());
            st.setExecutionOrder(com.github.amber.core.analysis.ExecutionOrder.of(st));
        } catch (RuntimeException | StackOverflowError e) {
            diagnostics.add(new com.github.amber.core.diagnostic.Diagnostic(
                    com.github.amber.core.diagnostic.Severity.WARNING, "lineage",
                    "Column lineage could not be computed for this statement (" + e.getClass().getSimpleName() + ")",
                    st.range(), null, List.of()));
        }
    }

    /** Variable writes of a container that are no statements of their own (PL/SQL {@code v := expr}, declarations). */
    private void containerLineage(ParserRuleContext ctx, Statement st) {
        var lr = rules.lineage();
        if (lr == null || variables() == null) {
            return;
        }
        try {
            var lineage = new com.github.amber.dialect.lineage.LineageExtractor(rules, lr, source, base, catalog)
                    .variables(variables).extract(st, ctx);
            if (lineage != null) {
                st.setLineage(lineage);
            }
        } catch (RuntimeException | StackOverflowError e) {
            diagnostics.add(new com.github.amber.core.diagnostic.Diagnostic(
                    com.github.amber.core.diagnostic.Severity.WARNING, "lineage",
                    "Variable lineage could not be computed for this block (" + e.getClass().getSimpleName() + ")",
                    st.range(), null, List.of()));
        }
    }

    /** For non-container statements: CTEs, targets and every object reference in the subtree. */
    private void collect(ParserRuleContext stmtNode, Statement st) {
        if (st.kind().category() == StatementKind.Category.TCL) {
            return;                                   // COMMIT, ROLLBACK, SAVEPOINT touch no objects
        }
        Set<String> cteKeys = new HashSet<>();
        collectCtes(stmtNode, st, cteKeys);

        Map<ParseTree, TargetSpec> targetNames = new IdentityHashMap<>();
        markTargets(stmtNode, targetNames);

        List<ObjectRef> refs = new ArrayList<>();
        Map<String, ObjectRef> aliases = new java.util.HashMap<>();
        Set<ParseTree> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        collectNames(stmtNode, node -> {
            seen.add(node);
            TargetSpec spec = targetNames.get(node);
            ObjectRef ref = addRef(refs, node, spec, cteKeys, stmtNode, st);
            String alias = ref == null ? null : aliasOf(node);
            if (alias != null) {
                aliases.putIfAbsent(alias, ref);
            }
        });
        targetNames.forEach((node, spec) -> {
            if (!seen.contains(node)) {
                addRef(refs, node, spec, cteKeys, stmtNode, st);
            }
        });
        resolveAliasTargets(refs, aliases);
        refs.sort(Comparator.comparingInt(r -> r.range().start().offset()));
        st.replaceRefs(refs);
    }

    /**
     * A target written as an alias ({@code UPDATE s … FROM stage s}) is the aliased table. The FROM
     * entry that only binds the alias is then dropped, since it is the target itself.
     */
    private void resolveAliasTargets(List<ObjectRef> refs, Map<String, ObjectRef> aliases) {
        for (int i = 0; i < refs.size(); i++) {
            ObjectRef r = refs.get(i);
            if (r.access() == Access.READ || r.name().parts().size() != 1) {
                continue;
            }
            ObjectRef aliased = aliases.get(r.name().simpleKey());
            if (aliased != null && aliased != r && !aliased.name().sameObject(r.name())) {
                refs.set(i, new ObjectRef(aliased.name(), aliased.kind(), r.access(), r.range(), aliased.confidence()));
                refs.remove(aliased);
                i = -1;                                   // indexes shifted; rescan
                aliases.values().remove(aliased);
            }
        }
    }

    /** The alias declared next to a table name: alias rules among the siblings of its parent or grandparent. */
    private String aliasOf(ParseTree nameNode) {
        ParseTree level = nameNode;
        for (int depth = 0; depth < 2 && level.getParent() instanceof ParserRuleContext parent; depth++) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                ParseTree sibling = parent.getChild(i);
                if (sibling != level && sibling instanceof ParserRuleContext c) {
                    ParserRuleContext alias = findAlias(c, rules.aliasDepth());
                    if (alias != null) {
                        ObjectName name = nameOf(alias);
                        return name == null ? null : name.simpleKey();
                    }
                }
            }
            level = parent;
        }
        return null;
    }

    private ParserRuleContext findAlias(ParserRuleContext ctx, int depth) {
        if (rules.isAliasRule(rules.ruleName(ctx))) {
            return ctx;
        }
        if (depth == 0) {
            return null;
        }
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (ctx.getChild(i) instanceof ParserRuleContext c) {
                ParserRuleContext found = findAlias(c, depth - 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private ObjectRef addRef(List<ObjectRef> refs, ParseTree node, TargetSpec spec, Set<String> cteKeys,
                             ParserRuleContext stmtNode, Statement st) {
        ObjectName name = node instanceof ParserRuleContext ctx ? qualifiedNameOf(ctx) : nameOf((TerminalNode) node);
        if (name == null) {
            return null;
        }
        Access tokenAccess = node instanceof TerminalNode t ? rules.nameToken(t) : null;
        Access access = spec != null ? spec.access() : tokenAccess != null ? tokenAccess : Access.READ;
        ObjectKind kind = spec != null ? spec.kind() : ObjectKind.TABLE;
        if (spec == null && name.parts().size() == 1 && cteKeys.contains(name.key())) {
            kind = ObjectKind.CTE;
        }
        Confidence confidence = kind == ObjectKind.CTE ? Confidence.CERTAIN : Confidence.INFERRED;
        ObjectRef ref = new ObjectRef(name, kind, access, range(node), confidence);
        ref = ref.withKind(rules.refineObjectKind(ref, stmtNode));
        if (spec != null && spec.access() == Access.CREATE && st.name() == null) {
            st.setName(name);
        }
        refs.add(ref);
        return ref;
    }

    private void collectCtes(ParserRuleContext ctx, Statement st, Set<String> cteKeys) {
        String nameRule = rules.cteRules().get(rules.ruleName(ctx));
        if (nameRule != null) {
            ParserRuleContext nameNode = childOf(ctx, nameRule, 0);
            ObjectName name = nameNode == null ? null : nameOf(nameNode);
            if (name != null) {
                st.addCte(name.toString());
                cteKeys.add(name.key());
            }
        }
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (ctx.getChild(i) instanceof ParserRuleContext c) {
                collectCtes(c, st, cteKeys);
            }
        }
    }

    /** Finds every TargetSpec position in the subtree and maps its name nodes to the spec. */
    private void markTargets(ParserRuleContext ctx, Map<ParseTree, TargetSpec> out) {
        String rule = rules.ruleName(ctx);
        for (TargetSpec spec : rules.targets()) {
            if (spec.parentRule().equals(rule)) {
                for (ParserRuleContext child : childrenOf(ctx, spec)) {
                    List<ParseTree> names = new ArrayList<>();
                    findNamesOutsideSubqueries(child, names);
                    if (names.isEmpty() && !rules.isNameRule(spec.childRule())) {
                        names.add(child);
                    }
                    if (spec.occurrence() == TargetSpec.FIRST_NAME && names.size() > 1) {
                        names = names.subList(0, 1);
                    }
                    names.forEach(n -> out.putIfAbsent(n, spec));
                }
            }
        }
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (ctx.getChild(i) instanceof ParserRuleContext c) {
                markTargets(c, out);
            }
        }
    }

    private void findNamesOutsideSubqueries(ParserRuleContext ctx, List<ParseTree> out) {
        String rule = rules.ruleName(ctx);
        if (rules.isNameRule(rule)) {
            if (rules.acceptName(ctx)) {
                out.add(ctx);
            }
            return;
        }
        if (rules.isSubqueryRule(rule) || rules.isOpaqueRule(rule)) {
            return;
        }
        for (int i = 0; i < ctx.getChildCount(); i++) {
            ParseTree child = ctx.getChild(i);
            if (child instanceof ParserRuleContext c) {
                findNamesOutsideSubqueries(c, out);
            } else if (child instanceof TerminalNode t && rules.nameToken(t) != null) {
                out.add(t);
            }
        }
    }

    private void collectNames(ParserRuleContext ctx, java.util.function.Consumer<ParseTree> sink) {
        String rule = rules.ruleName(ctx);
        if (rules.isOpaqueRule(rule)) {
            return;
        }
        if (rules.isNameRule(rule)) {
            if (rules.acceptName(ctx)) {
                sink.accept(ctx);
            }
            return;
        }
        for (int i = 0; i < ctx.getChildCount(); i++) {
            ParseTree child = ctx.getChild(i);
            if (child instanceof ParserRuleContext c) {
                collectNames(c, sink);
            } else if (child instanceof TerminalNode t && rules.nameToken(t) != null) {
                sink.accept(t);
            }
        }
    }

    // ----- names and ranges -----------------------------------------------------------------------

    private static ParserRuleContext childOf(ParserRuleContext ctx, String rule, int occurrence,
                                             IrRules rules) {
        int seen = 0;
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (ctx.getChild(i) instanceof ParserRuleContext c && rules.ruleName(c).equals(rule)) {
                if (seen++ == occurrence) {
                    return c;
                }
            }
        }
        return null;
    }

    private ParserRuleContext childOf(ParserRuleContext ctx, String rule, int occurrence) {
        return childOf(ctx, rule, occurrence, rules);
    }

    /** Children selected by a spec: one occurrence, or all of them for {@link TargetSpec#ALL}. */
    private List<ParserRuleContext> childrenOf(ParserRuleContext ctx, TargetSpec spec) {
        if (spec.occurrence() != TargetSpec.ALL) {
            int occurrence = spec.occurrence() == TargetSpec.FIRST_NAME ? 0 : spec.occurrence();
            ParserRuleContext one = childOf(ctx, spec.childRule(), occurrence);
            return one == null ? List.of() : List.of(one);
        }
        List<ParserRuleContext> all = new ArrayList<>();
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (ctx.getChild(i) instanceof ParserRuleContext c && rules.ruleName(c).equals(spec.childRule())) {
                all.add(c);
            }
        }
        return all;
    }

    private TextRange range(ParseTree node) {
        return node instanceof TerminalNode t ? range(t) : range((ParserRuleContext) node);
    }

    private ObjectName nameOf(TerminalNode token) {
        return ObjectName.parse(rules.dialect(), token.getText());
    }

    private TextRange range(TerminalNode token) {
        Token t = token.getSymbol();
        int a = Math.min(base + Math.max(0, t.getStartIndex()), source.length());
        int b = Math.min(base + t.getStopIndex() + 1, source.length());
        return source.range(a, Math.max(a, b));
    }

    private ObjectName nameOf(ParserRuleContext ctx) {
        TextRange r = range(ctx);
        if (r.length() == 0) {
            return null;
        }
        return ObjectName.parse(rules.dialect(), source.slice(r));
    }

    /**
     * Name of a node that may be preceded by {@code schema .} siblings, as in
     * {@code CREATE TABLE (schema_name '.')? table_name}.
     */
    private ObjectName qualifiedNameOf(ParserRuleContext ctx) {
        ParserRuleContext parent = ctx.getParent();
        if (parent != null) {
            int idx = parent.children.indexOf(ctx);
            if (idx >= 2 && parent.getChild(idx - 1) instanceof TerminalNode dot && ".".equals(dot.getText())
                    && parent.getChild(idx - 2) instanceof ParserRuleContext schema) {
                TextRange r = new TextRange(range(schema).start(), range(ctx).end());
                return ObjectName.parse(rules.dialect(), source.slice(r));
            }
        }
        return nameOf(ctx);
    }

    private TextRange range(ParserRuleContext ctx) {
        Token start = ctx.getStart();
        Token stop = ctx.getStop();
        int from = start == null || start.getStartIndex() < 0 ? 0 : start.getStartIndex();
        int to = stop == null || stop.getStopIndex() < from ? from : stop.getStopIndex() + 1;
        int a = Math.min(base + from, source.length());
        int b = Math.min(base + to, source.length());
        return source.range(a, Math.max(a, b));
    }
}
