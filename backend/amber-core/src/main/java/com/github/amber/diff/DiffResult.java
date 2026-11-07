package com.github.amber.diff;

import com.github.amber.core.diagnostic.Diagnostic;
import com.github.amber.core.source.TextRange;

import java.util.List;

/**
 * Result of comparing two versions of a script ({@link ScriptDiff#compare}): how the statements line up,
 * and what the change does to objects, column lineage, query shapes, findings and control flow, with a
 * risk level. Ranges on the {@code before*} side refer to the old text, {@code after*} to the new text;
 * statement ids are step indexes (depth-first source order, as {@code Statement.id} of {@code analyze}).
 * Everything is a plain record, so the result serializes to JSON as is.
 *
 * @param dialect     dialect id, e.g. {@code oracle}
 * @param before      how the old version was read
 * @param after       how the new version was read
 * @param summary     counts, risk level and the reasons behind it
 * @param statements  aligned top-level statements, in the new version's order (removed ones where they were);
 *                    children of containers are nested
 * @param objects     tables, views and temp tables read, written, created or dropped differently
 * @param lineage     changes of the base source columns, transforms and row filters of written columns
 * @param shapes      query structure changes of modified statements
 * @param findings    findings introduced or resolved by the change
 * @param controlFlow statements moved into or out of IF / LOOP / exception handlers / blocks
 * @param elapsedMs   time taken
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record DiffResult(String dialect, Side before, Side after, Summary summary, List<StatementPair> statements,
                         List<ObjectChange> objects, LineageChanges lineage, List<ShapeChange> shapes,
                         List<FindingChange> findings, List<ControlFlowChange> controlFlow, long elapsedMs) {

    /** How a statement of the old version relates to the new version. */
    public enum Status {
        /** Same text. */
        UNCHANGED,
        /** Only whitespace, comments or keyword case differ. */
        FORMAT_ONLY,
        /** Same statement (same kind and target, similar text) with a real change; see {@code hunks}. */
        MODIFIED,
        /** Only in the new version. */
        ADDED,
        /** Only in the old version. */
        REMOVED,
        /** Same statement (formatting aside) at another position or inside another container. */
        MOVED
    }

    public enum Risk {
        LOW, MEDIUM, HIGH
    }

    /**
     * @param status      {@code SUCCESS} or {@code PARTIAL} (parse status of the text that was compared)
     * @param fallback    true when strict parsing failed and the text was compared with fallback parsing
     * @param unparsed    statements only scanned at token level (fallback)
     * @param diagnostics syntax errors and warnings of the parse that was used
     */
    public record Side(String name, String status, boolean fallback, int lineCount, int stepCount, int unparsed,
                       List<Diagnostic> diagnostics) {
    }

    /**
     * @param risk     highest level among {@code reasons}; LOW when nothing structural changed
     * @param reasons  why the risk is what it is, most severe first
     * @param notes    remarks on how the comparison was made (fallback parsing, findings not computed, …)
     * @param headline one-line description of the change
     */
    public record Summary(Risk risk, String headline, Counts statements, int objectChanges, int columnChanges,
                          int rowInfluenceChanges, int shapeChanges, int newFindings, int resolvedFindings,
                          int controlFlowChanges, List<Reason> reasons, List<String> notes) {
    }

    /** Number of statement pairs per status, nested statements included. */
    public record Counts(int unchanged, int formatOnly, int modified, int added, int removed, int moved) {
    }

    /**
     * One reason for the risk level.
     *
     * @param code stable id of the rule, e.g. {@code FILTER_REMOVED}
     */
    public record Reason(Risk level, String code, String message, Integer beforeStatement, Integer afterStatement,
                         TextRange beforeRange, TextRange afterRange) {
    }

    /**
     * Two aligned statements (one side is null for ADDED / REMOVED).
     *
     * @param kind       statement kind (of the new version when present)
     * @param target     object the statement defines or writes, if any
     * @param similarity token similarity 0..1 for MODIFIED pairs, 1 for equal text after normalization
     * @param reordered  true when the statement also changed position relative to its neighbours
     * @param hunks      token-level differences of a MODIFIED pair (empty otherwise)
     * @param children   aligned children of containers
     */
    public record StatementPair(Status status, String kind, String target, Integer beforeId, Integer afterId,
                                TextRange beforeRange, TextRange afterRange, String beforeSummary,
                                String afterSummary, double similarity, boolean reordered, List<Hunk> hunks,
                                List<StatementPair> children) {
    }

    /**
     * One differing stretch of tokens. An empty range (start = end) marks where text was inserted or removed.
     */
    public record Hunk(TextRange before, TextRange after, String beforeText, String afterText) {
    }

    /** Where an object change was seen: over the whole script, or in one pair of statements. */
    public enum Scope {
        SCRIPT, STATEMENT
    }

    public enum ObjectChangeKind {
        /** The object is used only by the new version. */
        ADDED,
        /** The object is used only by the old version. */
        REMOVED,
        /** A new kind of access, e.g. the script now DELETEs from it. */
        ACCESS_ADDED,
        /** An access no longer made. */
        ACCESS_REMOVED,
        /** Lifecycle role changed (SOURCE / INTERMEDIATE / TARGET / STRUCTURE). */
        ROLE_CHANGED,
        /** Object kind changed, e.g. a table became a temp table. */
        KIND_CHANGED,
        /** A modified statement now touches the object (scope STATEMENT). */
        REF_ADDED,
        /** A modified statement no longer touches the object (scope STATEMENT). */
        REF_REMOVED
    }

    /**
     * @param access     the access concerned (READ, INSERT, DELETE, CREATE, DROP, …), when relevant
     * @param objectKind TABLE, TEMP_TABLE, VIEW, …
     */
    public record ObjectChange(Scope scope, ObjectChangeKind kind, String object, String key, String objectKind,
                               String access, String beforeRole, String afterRole, String message,
                               Integer beforeStatement, Integer afterStatement, TextRange beforeRange,
                               TextRange afterRange) {
    }

    /**
     * @param columns  per written column: base sources and transforms before vs after
     * @param indirect per written object: columns that filter, join or group its rows, added or removed
     */
    public record LineageChanges(List<ColumnChange> columns, List<IndirectChange> indirect) {
    }

    public enum ColumnChangeKind {
        /** A column written only by the new version. */
        ADDED,
        /** A column no longer written. */
        REMOVED,
        /** The set of base source columns changed. */
        SOURCES_CHANGED,
        /** The way the value is derived changed (e.g. PASS_THROUGH to AGGREGATE). */
        TRANSFORM_CHANGED
    }

    /**
     * A change of a column written to a table, view or temp table. Sources are base columns ({@code owner.column}),
     * reached by following direct lineage edges through CTEs, subqueries and temp tables.
     */
    public record ColumnChange(ColumnChangeKind kind, String target, String targetKey, String column,
                               String ownerKind, List<String> beforeSources, List<String> afterSources,
                               List<String> addedSources, List<String> removedSources,
                               List<String> beforeTransforms, List<String> afterTransforms, String message,
                               Integer beforeStatement, Integer afterStatement, TextRange beforeRange,
                               TextRange afterRange) {
    }

    /**
     * A base column that decides which rows reach a written object, added or removed.
     *
     * @param role   FILTER, JOIN or GROUP
     * @param change ADDED or REMOVED
     */
    public record IndirectChange(String target, String targetKey, String role, String change, String source,
                                 String message, Integer beforeStatement, Integer afterStatement,
                                 TextRange beforeRange, TextRange afterRange) {
    }

    public enum ShapeChangeKind {
        BLOCK_ADDED, BLOCK_REMOVED, SOURCE_ADDED, SOURCE_REMOVED, CLAUSE_ADDED, CLAUSE_REMOVED,
        JOIN_COUNT_CHANGED, SET_OPERATOR_CHANGED, OUTPUTS_CHANGED, OUTPUT_ORDER_CHANGED,
        /** A CTE or alias renamed with the structure unchanged. */
        RENAMED
    }

    /**
     * A query structure change inside a modified statement.
     *
     * @param block where in the structure, e.g. {@code QUERY > SUBQUERY (WHERE NOT IN)}
     */
    public record ShapeChange(ShapeChangeKind kind, String block, String detail, String message,
                              Integer beforeStatement, Integer afterStatement, TextRange beforeRange,
                              TextRange afterRange) {
    }

    /**
     * @param change NEW (introduced by the change) or RESOLVED (gone in the new version)
     */
    public record FindingChange(String change, String rule, String severity, String title, String message,
                                Integer beforeStatement, Integer afterStatement, TextRange beforeRange,
                                TextRange afterRange) {
    }

    /**
     * A statement that now runs under different control flow.
     *
     * @param beforePath enclosing containers in the old version, outermost first, e.g. {@code CREATE_PROCEDURE p_load}
     * @param afterPath  enclosing containers in the new version
     */
    public record ControlFlowChange(String kind, String target, List<String> beforePath, List<String> afterPath,
                                    String message, Integer beforeStatement, Integer afterStatement,
                                    TextRange beforeRange, TextRange afterRange) {
    }
}
