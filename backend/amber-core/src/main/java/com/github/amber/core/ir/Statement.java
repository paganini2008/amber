package com.github.amber.core.ir;

import com.github.amber.core.analysis.ExecutionOrder;
import com.github.amber.core.lineage.StatementLineage;
import com.github.amber.core.source.TextRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * A statement, block or control-flow node of a script. Containers ({@link StatementKind#isContainer()})
 * have children; their own {@link #refs()} are the objects they touch outside child statements (for
 * example the query of a cursor {@code FOR} loop).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class Statement {

    private final StatementKind kind;
    private TextRange range;
    private final Confidence confidence;
    private final List<ObjectRef> refs = new ArrayList<>();
    private final List<String> ctes = new ArrayList<>();
    private final List<Statement> children = new ArrayList<>();
    private ObjectName name;
    private String summary = "";
    private StatementLineage lineage;
    private List<QueryBlock> structure = List.of();
    private List<ExecutionOrder.Step> executionOrder = List.of();

    public Statement(StatementKind kind, TextRange range, Confidence confidence) {
        this.kind = kind;
        this.range = range;
        this.confidence = confidence;
    }

    public StatementKind kind() {
        return kind;
    }

    public TextRange range() {
        return range;
    }

    /**
     * Widens the range once a parser knows where a container ends (e.g. a PL/pgSQL {@code IF} read
     * keyword by keyword): it must start where it did and cover all of its children.
     */
    public void extendTo(TextRange wider) {
        if (wider.start().offset() != range.start().offset() || wider.end().offset() < range.end().offset()) {
            throw new IllegalArgumentException("Not a widening of " + range + ": " + wider);
        }
        this.range = wider;
    }

    public Confidence confidence() {
        return confidence;
    }

    public List<ObjectRef> refs() {
        return Collections.unmodifiableList(refs);
    }

    /** Names of the CTEs defined by this statement, in order. */
    public List<String> ctes() {
        return Collections.unmodifiableList(ctes);
    }

    public List<Statement> children() {
        return Collections.unmodifiableList(children);
    }

    /** The name a program or DDL statement defines (procedure, package, trigger, view…), if any. */
    public ObjectName name() {
        return name;
    }

    /** Column lineage of this statement, or {@code null} when not computed for its kind or dialect. */
    public StatementLineage lineage() {
        return lineage;
    }

    public void setLineage(StatementLineage lineage) {
        this.lineage = lineage;
    }

    /** Nested query structure (CTEs, query blocks, set operations, subqueries); empty when not computed. */
    public List<QueryBlock> structure() {
        return structure;
    }

    public void setStructure(List<QueryBlock> structure) {
        this.structure = structure == null ? List.of() : List.copyOf(structure);
    }

    /** Logical processing order of this statement's query (see {@code ExecutionOrder}); empty without a query. */
    public List<ExecutionOrder.Step> executionOrder() {
        return executionOrder;
    }

    public void setExecutionOrder(List<ExecutionOrder.Step> steps) {
        this.executionOrder = steps == null ? List.of() : List.copyOf(steps);
    }

    /** A one-line plain-English description. */
    public String summary() {
        return summary;
    }

    // ----- building ---------------------------------------------------------------------------

    public void addRef(ObjectRef ref) {
        refs.add(ref);
    }

    public void replaceRefs(List<ObjectRef> newRefs) {
        refs.clear();
        refs.addAll(newRefs);
    }

    public void addCte(String cte) {
        ctes.add(cte);
    }

    public void addChild(Statement child) {
        children.add(child);
    }

    public void setName(ObjectName name) {
        this.name = name;
    }

    public void setSummary(String summary) {
        this.summary = summary == null ? "" : summary;
    }

    // ----- traversal ----------------------------------------------------------------------------

    /** Visits this statement and all descendants in source order. */
    public void forEach(Consumer<Statement> visitor) {
        visitor.accept(this);
        for (Statement child : children) {
            child.forEach(visitor);
        }
    }

    @Override
    public String toString() {
        return kind + (name == null ? "" : " " + name) + " " + refs;
    }
}
