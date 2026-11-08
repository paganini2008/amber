package com.github.amber.dialect.ir;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.StatementKind;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the rules of one dialect's grammar mean, so that {@link TreeIrBuilder} can turn any parse tree
 * into Amber's IR without a hand-written visitor per dialect. Built with {@link #builder}.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public class IrRules {

    private final Dialect dialect;
    private final String[] ruleNames;
    private final Map<String, StatementKind> kinds;
    private final Map<String, Set<String>> onlyUnder;
    private final Set<String> statementParents;
    private final Set<String> wrappers;
    private final Set<String> nameRules;
    private final Set<String> subqueryRules;
    private final Set<String> opaqueRules;
    private final Map<String, String> cteRules;
    private final Set<String> aliasRules;
    private final int aliasDepth;
    private final List<TargetSpec> targets;

    protected IrRules(Builder b) {
        this.dialect = b.dialect;
        this.ruleNames = b.ruleNames;
        this.kinds = Map.copyOf(b.kinds);
        this.onlyUnder = Map.copyOf(b.onlyUnder);
        this.statementParents = Set.copyOf(b.statementParents);
        this.wrappers = Set.copyOf(b.wrappers);
        this.nameRules = Set.copyOf(b.nameRules);
        this.subqueryRules = Set.copyOf(b.subqueryRules);
        this.opaqueRules = Set.copyOf(b.opaqueRules);
        this.cteRules = Map.copyOf(b.cteRules);
        this.aliasRules = Set.copyOf(b.aliasRules);
        this.aliasDepth = b.aliasDepth;
        this.targets = List.copyOf(b.targets);
    }

    public static Builder builder(Dialect dialect, String[] ruleNames) {
        return new Builder(dialect, ruleNames);
    }

    public Dialect dialect() {
        return dialect;
    }

    public String ruleName(ParserRuleContext ctx) {
        return ruleNames[ctx.getRuleIndex()];
    }

    /** The statement kind of {@code ctx}, or {@code null} when it is not a statement node. */
    public StatementKind kindOf(ParserRuleContext ctx) {
        String rule = ruleName(ctx);
        if (wrappers.contains(rule)) {
            return null;
        }
        String parent = ctx.getParent() != null ? ruleName(ctx.getParent()) : null;
        Set<String> allowedParents = onlyUnder.get(rule);
        if (allowedParents != null && (parent == null || !allowedParents.contains(parent))) {
            return null;
        }
        StatementKind kind = kinds.get(rule);
        if (kind == null && parent != null && statementParents.contains(parent)) {
            kind = inferKind(rule);
        }
        return kind == null ? null : refineKind(kind, ctx);
    }

    public boolean isNameRule(String rule) {
        return nameRules.contains(rule);
    }

    /**
     * Whether a node of a name rule really names an object here. Grammars that reuse one name rule
     * for tables and columns (SQLite {@code table_name} in {@code t.col}, H2 {@code qualified_name})
     * override this to keep only table positions.
     */
    public boolean acceptName(ParserRuleContext node) {
        return true;
    }

    /** True when {@code ctx}'s parent has rule {@code rule}. */
    protected boolean parentIs(ParserRuleContext ctx, String rule) {
        return ctx.getParent() != null && ruleName(ctx.getParent()).equals(rule);
    }

    /** Rules that never contain object names, such as column references ({@code t.col}). */
    public boolean isOpaqueRule(String rule) {
        return opaqueRules.contains(rule);
    }

    public boolean isSubqueryRule(String rule) {
        return subqueryRules.contains(rule);
    }

    /** Rule defining a CTE mapped to the child rule holding its name. */
    public Map<String, String> cteRules() {
        return cteRules;
    }

    public List<TargetSpec> targets() {
        return targets;
    }

    public boolean isAliasRule(String rule) {
        return aliasRules.contains(rule);
    }

    /** How deep below a table name's siblings to look for an alias rule (0 = direct siblings only). */
    public int aliasDepth() {
        return aliasDepth;
    }

    // ----- dialect hooks ------------------------------------------------------------------------

    /**
     * Whether a token is itself an object name, such as a SQL Server table variable {@code @t} in
     * {@code FROM @t}. Returns the access it implies (usually READ), or {@code null} when the token
     * is not a name. Grammars that model every name as a rule need not override this.
     */
    public Access nameToken(TerminalNode token) {
        return null;
    }

    /**
     * Adds what the grammar leaves opaque, such as the statements inside a PostgreSQL {@code $$} body.
     * Called for every statement after it is built. Returns problems found (as warnings), never null.
     *
     * @param source the text the statement was built from; offsets are script offsets
     */
    public List<com.github.amber.core.diagnostic.Diagnostic> enrich(com.github.amber.core.ir.Statement statement,
                                                                   ParserRuleContext node,
                                                                   com.github.amber.core.source.SourceText source,
                                                                   com.github.amber.core.lineage.Catalog catalog) {
        return List.of();
    }

    /** Column-lineage rules of this dialect, or {@code null} when lineage is not mapped yet. */
    public com.github.amber.dialect.lineage.LineageRules lineage() {
        return null;
    }

    /** Refines a statement kind from the node's content, e.g. CREATE TABLE … AS SELECT. */
    protected StatementKind refineKind(StatementKind kind, ParserRuleContext ctx) {
        return kind;
    }

    /** Refines an object's kind, e.g. temporary tables by name prefix or by a TEMPORARY keyword. */
    public ObjectKind refineObjectKind(ObjectRef ref, ParserRuleContext statementNode) {
        return ref.kind();
    }

    /** Statement kind guessed from a rule name, for statement rules not listed explicitly. */
    protected StatementKind inferKind(String rule) {
        String r = rule.toLowerCase(Locale.ROOT);
        if (r.startsWith("create")) {
            return StatementKind.CREATE_OTHER;
        }
        if (r.startsWith("drop")) {
            return StatementKind.DROP;
        }
        if (r.startsWith("alter")) {
            return StatementKind.ALTER;
        }
        if (r.startsWith("grant")) {
            return StatementKind.GRANT;
        }
        if (r.startsWith("revoke")) {
            return StatementKind.REVOKE;
        }
        if (r.startsWith("truncate")) {
            return StatementKind.TRUNCATE;
        }
        if (r.startsWith("comment")) {
            return StatementKind.COMMENT;
        }
        if (r.startsWith("commit") || r.startsWith("rollback") || r.startsWith("savepoint")
                || r.startsWith("release") || r.startsWith("begin") || r.startsWith("start_transaction")) {
            return StatementKind.TRANSACTION;
        }
        return StatementKind.OTHER;
    }

    // ----- helpers for hooks --------------------------------------------------------------------

    /** True when a descendant of {@code ctx} (not crossing into subqueries) has rule {@code rule}. */
    protected boolean hasDescendant(ParserRuleContext ctx, String rule) {
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (ctx.getChild(i) instanceof ParserRuleContext c) {
                if (ruleName(c).equals(rule) || hasDescendant(c, rule)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when one of the first {@code limit} tokens of {@code ctx} equals {@code word}, ignoring case. */
    protected static boolean leadingTokensContain(ParserRuleContext ctx, int limit, String word) {
        List<String> tokens = new ArrayList<>();
        collectTokens(ctx, tokens, limit);
        return tokens.stream().anyMatch(word::equalsIgnoreCase);
    }

    private static void collectTokens(ParseTree t, List<String> out, int max) {
        if (out.size() >= max) {
            return;
        }
        if (t instanceof TerminalNode term) {
            out.add(term.getText());
            return;
        }
        for (int i = 0; i < t.getChildCount() && out.size() < max; i++) {
            collectTokens(t.getChild(i), out, max);
        }
    }

    // ----- builder ------------------------------------------------------------------------------

    public static class Builder {
        private final Dialect dialect;
        private final String[] ruleNames;
        private final Set<String> knownRules;
        private final Map<String, StatementKind> kinds = new HashMap<>();
        private final Map<String, Set<String>> onlyUnder = new HashMap<>();
        private final Set<String> statementParents = new HashSet<>();
        private final Set<String> wrappers = new HashSet<>();
        private final Set<String> nameRules = new HashSet<>();
        private final Set<String> subqueryRules = new HashSet<>();
        private final Set<String> opaqueRules = new HashSet<>();
        private final Map<String, String> cteRules = new HashMap<>();
        private final Set<String> aliasRules = new HashSet<>();
        private int aliasDepth = 2;
        private final List<TargetSpec> targets = new ArrayList<>();

        Builder(Dialect dialect, String[] ruleNames) {
            this.dialect = dialect;
            this.ruleNames = ruleNames;
            this.knownRules = Set.of(ruleNames);
        }

        public Dialect dialect() {
            return dialect;
        }

        public String[] ruleNames() {
            return ruleNames;
        }

        /** Maps grammar rules to a statement kind. */
        public Builder kind(StatementKind kind, String... rules) {
            for (String r : rules) {
                kinds.put(check(r), kind);
            }
            return this;
        }

        /** Restricts a statement rule to nodes whose parent is one of {@code parents}. */
        public Builder onlyUnder(String rule, String... parents) {
            onlyUnder.put(check(rule), Set.of(parents));
            return this;
        }

        /** Rules whose children are statements; unlisted children get a kind inferred from their name. */
        public Builder statementParents(String... rules) {
            for (String r : rules) {
                statementParents.add(check(r));
            }
            return this;
        }

        /**
         * Rules that only group other statement rules (e.g. {@code data_manipulation_language_statements});
         * they are never statements themselves and their children are statement candidates.
         */
        public Builder wrappers(String... rules) {
            for (String r : rules) {
                wrappers.add(check(r));
                statementParents.add(r);
            }
            return this;
        }

        public Builder nameRules(String... rules) {
            for (String r : rules) {
                nameRules.add(check(r));
            }
            return this;
        }

        public Builder subqueryRules(String... rules) {
            for (String r : rules) {
                subqueryRules.add(check(r));
            }
            return this;
        }

        /**
         * Rules whose subtrees contain no object names even if a name rule appears inside them, e.g.
         * T-SQL {@code full_column_name}, whose qualifier reuses {@code full_table_name}.
         */
        public Builder opaqueRules(String... rules) {
            for (String r : rules) {
                opaqueRules.add(check(r));
            }
            return this;
        }

        public Builder cte(String definitionRule, String nameRule) {
            cteRules.put(check(definitionRule), check(nameRule));
            return this;
        }

        /**
         * Rules holding a table alias next to a table name ({@code FROM orders o}). Used to resolve
         * targets written as aliases, as in SQL Server's {@code UPDATE o SET … FROM orders o}.
         */
        public Builder aliasRules(String... rules) {
            for (String r : rules) {
                aliasRules.add(check(r));
            }
            return this;
        }

        /** Limits the alias search; use 0 when the alias rule is generic (e.g. MariaDB's {@code uid}). */
        public Builder aliasDepth(int depth) {
            this.aliasDepth = depth;
            return this;
        }

        public Builder target(TargetSpec spec) {
            check(spec.parentRule());
            check(spec.childRule());
            targets.add(spec);
            return this;
        }

        /** Fails fast when a rule name does not exist in the grammar (catches typos and upstream renames). */
        private String check(String rule) {
            if (!knownRules.contains(rule)) {
                throw new IllegalArgumentException(dialect + " grammar has no rule '" + rule + "'");
            }
            return rule;
        }
    }
}
