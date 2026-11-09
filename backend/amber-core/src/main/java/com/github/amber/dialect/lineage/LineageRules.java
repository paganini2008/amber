package com.github.amber.dialect.lineage;

import org.antlr.v4.runtime.ParserRuleContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a dialect's query rules mean for column lineage, so {@link LineageExtractor} can work on any
 * grammar. Unset optional rules are {@code null}.
 *
 * @param queryBlocks     rules of one SELECT … FROM … WHERE … block (Oracle {@code query_block})
 * @param queryRoots      rules that start a nested query (subqueries, derived tables)
 * @param selectItem      one item of the select list
 * @param selectAliases   alias rules inside a select item
 * @param columnRefs      rules that reference a column ({@code t.col}, {@code col})
 * @param fromClause      the FROM clause of a block
 * @param filterClauses   WHERE / HAVING clauses (indirect FILTER edges)
 * @param joinClauses     JOIN … ON / USING conditions (indirect JOIN edges)
 * @param groupClauses    GROUP BY clauses (indirect GROUP edges)
 * @param cteDefinition   one CTE definition
 * @param cteNameRule     rule of the CTE name inside a definition
 * @param cteColumnRule   rule of one column name in a CTE column list, or {@code null}
 * @param insertColumnsParent rule that holds the INSERT target column list
 * @param columnNameRule  rule of one plain column name (insert lists, SET targets, DDL columns)
 * @param setClauses      rules of one {@code col = expr} assignment (UPDATE SET, MERGE UPDATE SET)
 * @param columnDefinition one column definition in CREATE TABLE
 * @param valuesExpressionList rule holding one row of VALUES expressions (MERGE INSERT, INSERT … VALUES)
 * @param mergeInsert     the MERGE … WHEN NOT MATCHED THEN INSERT clause
 * @param pseudoColumns   upper-case names that are never table columns (SYSDATE, ROWNUM, CURRENT_DATE…)
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record LineageRules(Set<String> queryBlocks, Set<String> queryRoots, String selectItem, Set<String> selectAliases,
                           Set<String> columnRefs, String fromClause, Set<String> filterClauses,
                           Set<String> joinClauses, Set<String> groupClauses, String cteDefinition,
                           String cteNameRule, String cteColumnRule, String insertColumnsParent,
                           String columnNameRule, Set<String> setClauses, String columnDefinition,
                           String valuesExpressionList, String mergeInsert, Set<String> pseudoColumns,
                           Hooks hooks) {

    /** Dialect-specific decisions the rule names alone cannot express. */
    public interface Hooks {

        /** Whether a column-reference node really is a column (not a function call or a variable). */
        default boolean isColumnRef(ParserRuleContext node, String text) {
            return true;
        }

        /** The alias node of a select item when the alias rules cannot find it (e.g. {@code AS identifier}); else null. */
        default ParserRuleContext selectAlias(ParserRuleContext item) {
            return null;
        }

        /**
         * Whether a block selects a bare {@code *} that is not a select item in the grammar (Oracle's
         * {@code selected_list : '*'}).
         */
        default boolean selectsAll(ParserRuleContext block, String blockText) {
            return BARE_STAR.matcher(blockText).lookingAt();
        }

        /**
         * Rules that are always expressions and never hold data sources, for grammars whose WHERE has
         * no rule of its own (SQLite {@code expr}); subqueries inside them are not derived tables.
         */
        default boolean isExpression(String rule) {
            return false;
        }

        /**
         * WHERE / HAVING expressions of a block, or the WHERE of an UPDATE / DELETE statement, that have
         * no rule of their own (labelled fields).
         */
        default List<ParserRuleContext> filterNodes(ParserRuleContext block) {
            return List.of();
        }

        /**
         * {@code column = expression} pairs of an UPDATE whose grammar has no rule per assignment (SQLite
         * {@code update_stmt}); the target node may be a column list {@code (a, b)}.
         */
        default List<Map.Entry<ParserRuleContext, ParserRuleContext>> assignments(ParserRuleContext statement) {
            return List.of();
        }

        /** GROUP BY expressions of a block that have no rule of their own. */
        default List<ParserRuleContext> groupNodes(ParserRuleContext block) {
            return List.of();
        }

        /**
         * Rules of a DML {@code RETURNING exprs [INTO targets]} clause (first child the RETURNING keyword):
         * its expressions flow into the {@code INTO} variables, or into the statement's result columns.
         */
        default Set<String> returningClauses() {
            return Set.of();
        }

        /** Procedural rules for variable lineage, or {@code null} when the dialect has no variables. */
        default VariableRules variables() {
            return null;
        }

        /** Whether a select item selects {@code *} or {@code t.*}; return the qualifier or "" for bare {@code *}, else null. */
        default String starQualifier(ParserRuleContext item, String text) {
            String t = text.strip();
            if (t.equals("*")) {
                return "";
            }
            if (t.endsWith(".*")) {
                return t.substring(0, t.length() - 2).strip();
            }
            return null;
        }
    }

    /** {@code SELECT [DISTINCT|ALL|UNIQUE] *} where the star is a bare token, not a select item. */
    static final Pattern BARE_STAR = Pattern.compile(
            "(?is)\\s*SELECT\\s+(?:/\\*.*?\\*/\\s*)?(?:DISTINCT\\s+|UNIQUE\\s+|ALL\\s+)?\\*(?:\\s|,|$)");

    public static final Hooks DEFAULT_HOOKS = new Hooks() {
    };
}
