package com.github.amber.dialect.lineage;

import java.util.Set;

/**
 * What a dialect's procedural rules mean for variable lineage (see {@link VariableScopes}). Values flow
 * into variables through assignments, declarations with a default, {@code SELECT … INTO} and
 * {@code FETCH … INTO}, and out of them wherever a statement reads a name that is no column.
 *
 * @param parameters   rules of one routine parameter ({@code p_day IN DATE}, {@code @from date})
 * @param declarations rules of one variable declaration; it may declare several names
 *                     ({@code DECLARE a, b INT DEFAULT 0}) and carry a default after {@code :=},
 *                     {@code =} or {@code DEFAULT}
 * @param assignments  rules holding one or more {@code target := expr} / {@code target = expr} pairs
 *                     (PL/SQL assignment, {@code SET v = …}, T-SQL {@code SET @v = …})
 * @param intoClauses  the {@code INTO v1, v2} clause of a {@code SELECT … INTO} that assigns variables
 * @param fetches      {@code FETCH cursor INTO v1, v2} statements
 * @param blockScopes  whether a nested block ({@code DECLARE … BEGIN … END}) opens a scope of its own;
 *                     false where variables belong to the whole routine or batch (T-SQL)
 * @param assignedAreVariables whether every assignment target is a variable even when it is declared
 *                     elsewhere (PL/SQL {@code :=} targets, package variables); false where {@code SET}
 *                     also sets system variables
 * @param atNames      what {@code @name} means: {@link AtNames#ROUTINE} (T-SQL locals of the batch or
 *                     routine), {@link AtNames#SESSION} (MySQL user variables) or {@link AtNames#NONE}
 * @param returns      rules of {@code RETURN expr} whose value is the function result (written to the
 *                     function's {@code return} variable, see {@link VariableScopes#returnNode})
 * @param loopRecords  rules of a cursor FOR-loop header ({@code r IN (SELECT …)}, {@code r IN cursor}):
 *                     {@code r.col} then reads the query's output column
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record VariableRules(Set<String> parameters, Set<String> declarations, Set<String> assignments,
                            Set<String> intoClauses, Set<String> fetches, boolean blockScopes,
                            boolean assignedAreVariables, AtNames atNames, Set<String> returns,
                            Set<String> loopRecords) {

    /** Rules without RETURN values and cursor FOR-loop records. */
    public VariableRules(Set<String> parameters, Set<String> declarations, Set<String> assignments,
                         Set<String> intoClauses, Set<String> fetches, boolean blockScopes,
                         boolean assignedAreVariables, AtNames atNames) {
        this(parameters, declarations, assignments, intoClauses, fetches, blockScopes, assignedAreVariables, atNames,
                Set.of(), Set.of());
    }

    /** Meaning of {@code @name} references. */
    public enum AtNames {
        /** {@code @} is not a variable prefix. */
        NONE,
        /** Local variables and parameters of the routine or batch (T-SQL). */
        ROUTINE,
        /** User variables living for the whole session (MySQL, MariaDB). */
        SESSION
    }

    /** No procedural rules: variables come only from an external scope (PL/pgSQL bodies). */
    public static final VariableRules NONE = new VariableRules(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), true,
            false, AtNames.NONE);
}
