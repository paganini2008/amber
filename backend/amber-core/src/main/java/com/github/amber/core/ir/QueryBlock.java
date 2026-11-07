package com.github.amber.core.ir;

import com.github.amber.core.source.TextRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One box of a statement's nested query structure: a query block, a set operation, a CTE, a derived
 * table or a subquery inside an expression. Children nest in source order.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class QueryBlock {

    public enum Kind {
        /** {@code SELECT … FROM … WHERE …}. */
        QUERY,
        /** UNION / INTERSECT / EXCEPT of the child queries; {@link #name()} holds the operators. */
        SET,
        /** {@code WITH name AS (…)}. */
        CTE,
        /** A subquery in FROM / JOIN used as a table. */
        DERIVED,
        /** A subquery inside an expression (IN, EXISTS, scalar). */
        SUBQUERY
    }

    /**
     * One logical processing phase of a block, e.g. FROM or GROUP BY, with the source range of its clause.
     * {@code rank} orders phases the way the database applies them (FROM 1 … SELECT 8 … LIMIT 13).
     */
    public record Phase(String name, int rank, TextRange range) {
    }

    /** A data source of a query block: a table, CTE or derived table, with its alias. */
    public record Source(String name, String kind, String alias) {
    }

    private final Kind kind;
    private final String name;
    private final String role;
    private final TextRange range;
    private final List<Source> sources = new ArrayList<>();
    private final List<String> outputs = new ArrayList<>();
    private final List<String> clauses = new ArrayList<>();
    private final List<QueryBlock> children = new ArrayList<>();
    private final List<Phase> phases = new ArrayList<>();
    private int id = -1;

    /**
     * @param name CTE name, derived-table alias or set operators; may be null
     * @param role where a subquery sits in its parent ({@code WHERE IN}, {@code SELECT scalar}, …); may be null
     */
    public QueryBlock(Kind kind, String name, String role, TextRange range) {
        this.kind = kind;
        this.name = name;
        this.role = role;
        this.range = range;
    }

    public Kind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    public String role() {
        return role;
    }

    public TextRange range() {
        return range;
    }

    public List<Source> sources() {
        return Collections.unmodifiableList(sources);
    }

    /** Output column names, in order; {@code *} expansions included when their columns are known. */
    public List<String> outputs() {
        return Collections.unmodifiableList(outputs);
    }

    /** Clauses present in this block, e.g. {@code DISTINCT}, {@code JOIN ×2}, {@code WHERE}, {@code GROUP BY}. */
    public List<String> clauses() {
        return Collections.unmodifiableList(clauses);
    }

    /** Logical phases in processing order (FROM, WHERE, GROUP BY, HAVING, SELECT, DISTINCT, ORDER BY, LIMIT). */
    public List<Phase> phases() {
        return Collections.unmodifiableList(phases);
    }

    /** Pre-order number of this box within its statement's structure; -1 before numbering. */
    public int id() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public void addPhase(Phase phase) {
        phases.add(phase);
        phases.sort(java.util.Comparator.comparingInt(Phase::rank)
                .thenComparingInt(p -> p.range() == null ? 0 : p.range().start().offset()));
    }

    /** Drops phases added after index {@code from} whose name an earlier phase already has. */
    public void dedupePhases(int from) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Phase> keep = new ArrayList<>();
        for (Phase p : phases) {
            if (seen.add(p.name() + "@" + (p.range() == null ? -1 : p.range().start().offset()))) {
                keep.add(p);
            }
        }
        phases.clear();
        phases.addAll(keep);
    }

    public List<QueryBlock> children() {
        return Collections.unmodifiableList(children);
    }

    public void addSource(Source s) {
        sources.add(s);
    }

    public void addOutput(String column) {
        outputs.add(column);
    }

    public void addClause(String clause) {
        clauses.add(clause);
    }

    public void addChild(QueryBlock child) {
        children.add(child);
    }

    /**
     * A copy where a CTE, derived table or subquery holding exactly one plain query takes that query's
     * content, so the structure shows one box instead of two.
     */
    public QueryBlock simplified() {
        QueryBlock from = this;
        if (kind != Kind.QUERY && kind != Kind.SET && children.size() == 1 && children.get(0).kind == Kind.QUERY
                && sources.isEmpty()) {
            from = children.get(0);
        }
        QueryBlock out = new QueryBlock(kind, name, role, range);
        out.sources.addAll(from.sources);
        out.outputs.addAll(from.outputs);
        out.clauses.addAll(from.clauses);
        out.phases.addAll(from.phases);
        for (QueryBlock c : from.children) {
            out.children.add(c.simplified());
        }
        // Built in traversal order (FROM before the select list); shown in source order.
        if (kind == Kind.QUERY && range != null) {
            out.children.sort(java.util.Comparator.comparingInt(c -> c.range == null ? 0 : c.range.start().offset()));
        }
        return out;
    }
}
