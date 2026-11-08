package com.github.amber.er;

import com.github.amber.core.source.TextRange;

import java.util.List;

/**
 * An entity-relationship model inferred from a script: the tables, views and temp tables it uses, and the
 * relationships between them — declared foreign keys (script DDL, imported DDL, metadata) and, for legacy
 * schemas without declared keys, the equality joins the script writes. Nothing is executed.
 *
 * @param entities      tables, views and temp tables in order of first mention
 * @param relationships declared keys first, then joins (most used first), name matches and data flows
 * @param mermaid       the model as Mermaid {@code erDiagram} text, for export
 * @param stats         counts that explain the result
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
public record ErModel(List<Entity> entities, List<Relationship> relationships, String mermaid, Stats stats) {

    public ErModel {
        entities = List.copyOf(entities);
        relationships = List.copyOf(relationships);
    }

    /** Where a column is known from. */
    public enum ColumnSource {
        /** A CREATE TABLE in the script or in an imported DDL file. */
        DDL,
        /** A metadata pack or online metadata. */
        METADATA,
        /** Only seen used by the script (select lists, joins, filters, inserts). */
        USAGE
    }

    /** How many rows of {@code to} one row of {@code from} relates to, and the reverse. */
    public enum Cardinality {
        MANY_TO_ONE, ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY, UNKNOWN
    }

    /** Why the relationship is in the model; the order is the order of strength. */
    public enum Evidence {
        /** A FOREIGN KEY / REFERENCES clause in the script or in an imported DDL file. */
        DECLARED_FK,
        /** A foreign key read from a metadata pack or a database. */
        METADATA_FK,
        /** Equality join conditions written in the script. */
        JOIN,
        /** Column names only ({@code orders.customer_id} next to {@code customers.id}); never joined. */
        NAME_MATCH,
        /** Rows copied from one table into another (INSERT … SELECT, CREATE TABLE AS, SELECT INTO). */
        DATA_FLOW
    }

    /** How sure the relationship and its cardinality are. */
    public enum Certainty {
        /** Declared by a constraint. */
        CERTAIN,
        /** Seen in the script and backed by key metadata (or a data flow that is written out). */
        INFERRED,
        /** A guess from naming conventions, or a join whose cardinality is unknown. */
        POSSIBLE
    }

    /**
     * A column of an entity.
     *
     * @param type     data type when known from DDL or metadata
     * @param nullable nullability when known from DDL or metadata
     * @param pk       part of the primary key
     * @param unique   unique on its own (single-column primary or unique key, unique index)
     * @param fk       on the referencing side of a declared key or of a join that points at a key
     * @param joined   used in an equality join of the script
     */
    public record Column(String name, String type, Boolean nullable, boolean pk, boolean unique, boolean fk,
                         boolean joined, ColumnSource source) {
    }

    /**
     * A table, view, temp table or table variable.
     *
     * @param key             normalized name (as {@code Ref.key}); relationships refer to it
     * @param name            name as first written
     * @param kind            {@code TABLE}, {@code VIEW}, {@code MATERIALIZED_VIEW}, {@code TEMP_TABLE}, {@code TABLE_VARIABLE}
     * @param createdInScript the script creates it (CREATE TABLE / VIEW, CREATE TABLE AS, SELECT INTO)
     * @param definitionRange the creating statement, or null
     * @param metadataSource  {@code source} of the table metadata used (a pack, a data source id, "script DDL"), or null
     */
    public record Entity(String key, String name, String kind, List<Column> columns, boolean createdInScript,
                         TextRange definitionRange, String metadataSource) {

        public Entity {
            columns = List.copyOf(columns);
        }
    }

    /** One end of a relationship: an entity key and its columns (pairwise with the other end). */
    public record End(String entity, List<String> columns) {

        public End {
            columns = List.copyOf(columns);
        }
    }

    /** Where the script shows the relationship: a statement (step id, as in {@code analyze}) and a range. */
    public record Occurrence(int statement, TextRange range) {
    }

    /**
     * A relationship. For {@code MANY_TO_ONE}, {@code from} is the many side (the referencing columns) and
     * {@code to} the one side (the key). {@code count} is the number of occurrences, of which at most
     * {@link ErModelBuilder.Options#maxOccurrences()} are listed.
     *
     * @param constraint name of the declared constraint, or null
     */
    public record Relationship(String id, End from, End to, Cardinality cardinality, Evidence evidence,
                               Certainty confidence, List<Occurrence> occurrences, int count, String note,
                               String constraint) {

        public Relationship {
            occurrences = List.copyOf(occurrences);
        }
    }

    /**
     * @param statements      statements looked at
     * @param joinEqualities  equality conditions between two different table references that were resolved
     * @param unresolved      equality conditions between columns whose table could not be told (no alias,
     *                        several candidate tables, a computed column of a CTE or derived table)
     * @param elapsedMs       time to build the model (parsing excluded)
     */
    public record Stats(int statements, int joinEqualities, int unresolved, long elapsedMs) {
    }
}
