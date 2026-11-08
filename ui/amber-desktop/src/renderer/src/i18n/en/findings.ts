/**
 * Rule titles, suggestions and descriptions by rule id. The engine writes these itself in the UI
 * language (it receives `locale`); this catalog has the same wording and only fills gaps, e.g. the rule
 * title next to an optimized statement. Finding messages always come from the engine.
 */
export const findings = {
  rules: {
    'AMB-S001': {
      title: 'Cartesian product',
      suggestion: 'Add join conditions, or write CROSS JOIN if the product is intended.',
      description: 'Several tables in FROM without any join or WHERE condition: every row is combined with every row.'
    },
    'AMB-S002': {
      title: 'LEFT JOIN behaves as INNER JOIN',
      suggestion: 'Move the condition into the ON clause, or use INNER JOIN if that is intended.',
      description: 'A WHERE condition on the right table of a LEFT JOIN drops the rows the outer join kept.'
    },
    'AMB-S003': {
      title: 'Leading wildcard',
      suggestion: 'Anchor the pattern at the start, or use full-text search.',
      description: 'LIKE \'%x\' cannot use an index on the column.'
    },
    'AMB-S004': {
      title: 'NOT IN with a subquery',
      suggestion: 'Use NOT EXISTS, or filter NULLs out of the subquery.',
      description: 'One NULL from the subquery makes NOT IN match no rows (certain when metadata says the column is nullable).'
    },
    'AMB-S005': {
      title: 'Table read several times',
      suggestion: 'Read it once in a CTE (or with conditional aggregation) and reuse the result.',
      description: 'The same table is read several times in one statement.'
    },
    'AMB-S006': {
      title: 'Row-by-row DML in a loop',
      suggestion: 'Replace the loop with one set-based statement (INSERT … SELECT, UPDATE … FROM, MERGE), or batch it (FORALL / bulk binds).',
      description: 'An INSERT, UPDATE, DELETE or MERGE runs once per loop iteration.'
    },
    'AMB-S007': {
      title: 'COMMIT inside a loop',
      suggestion: 'Commit once after the loop, or in larger batches.',
      description: 'Committing every iteration is slow and leaves partial results when the loop fails.'
    },
    'AMB-S008': {
      title: 'Function on a filtered column',
      suggestion: 'Compare the bare column with a transformed value (e.g. a date range instead of TRUNC(col)).',
      description: 'A function or calculation on a column in WHERE prevents an index on it from being used (HIGH when metadata shows the index).'
    },
    'AMB-S009': {
      title: 'UNION removes duplicates',
      suggestion: 'Use UNION ALL when the branches cannot overlap or duplicates are fine.',
      description: 'UNION sorts or hashes the result; UNION ALL may be enough.'
    },
    'AMB-S010': {
      title: 'SELECT * written into a table or view',
      suggestion: 'List the columns explicitly.',
      description: 'The column list changes whenever the source table changes.'
    },
    'AMB-S011': {
      title: 'DISTINCT with GROUP BY',
      suggestion: 'Remove DISTINCT unless the select list adds columns outside the groups.',
      description: 'GROUP BY already returns one row per group.'
    },
    'AMB-S012': {
      title: 'ORDER BY in a subquery',
      suggestion: 'Remove ORDER BY, or move it to the outermost query.',
      description: 'The order of a subquery or CTE without a row limit is not kept by the outer query.'
    },
    'AMB-M001': {
      title: 'Many-to-many join',
      suggestion: 'Join on a unique key, or aggregate one side to one row per join key first. Check whether totals are inflated.',
      description: 'The join columns are unique on neither side, so rows repeat on both sides.'
    },
    'AMB-D001': {
      title: 'Duplicate statement',
      suggestion: 'Remove the repetition or factor it into a procedure.',
      description: 'The same statement appears again (literals ignored).'
    },
    'AMB-D002': {
      title: 'Repeated subquery',
      suggestion: 'Compute it once in a CTE or temp table and reuse it.',
      description: 'The same subquery appears several times in the script.'
    },
    'AMB-I001': {
      title: 'Dynamic SQL',
      suggestion: 'Where possible, use static SQL so the statement can be checked.',
      description: 'SQL built at run time is not analyzed.'
    },
    'AMB-I002': {
      title: 'Columns without a table',
      suggestion: 'Qualify the columns with a table alias, or add metadata (DDL import, metadata pack or online mode).',
      description: 'Columns that could not be attributed to a table.'
    },
    'AMB-I003': {
      title: 'Checks stopped early',
      suggestion: 'Split the script into smaller files, or raise AMBER_FINDINGS_SECONDS.',
      description: 'The checks ran out of their time budget (AMBER_FINDINGS_SECONDS, default 60); later statements were not checked.'
    },
    'AMB-P001': {
      title: 'Possible missing index',
      suggestion: 'Consider an index led by the column. This hint is based on the script\'s usage and the table definition; check real data volumes and existing indexes in the database before adding one.',
      description: 'A column used in joins or filters has no index that starts with it in the known table definition (raised to LOW when used in 3 or more statements). A hint only: check real data volumes and existing indexes.'
    },
    'AMB-P002': {
      title: 'Implicit conversion',
      suggestion: 'Compare with a value of the column\'s type (a number without quotes, a string in quotes, a date literal with an explicit format), or convert the value, never the column.',
      description: 'A column of a known type is compared with a value of another type, so the database converts one side: the column (an index cannot be used) or the value (with session-dependent formats or run-time errors).'
    },
    'AMB-P003': {
      title: 'Correlated subquery in the select list',
      suggestion: 'Compute it once for all rows: join a grouped derived table, or use APPLY / LATERAL. Amber offers a verified rewrite for the simple aggregate form.',
      description: 'A scalar subquery that refers to the outer row runs once per row; a join to a grouped derived table runs once.'
    },
    'AMB-P004': {
      title: 'Statements that can be combined',
      suggestion: 'Combine them into one statement (one UPDATE, or an upsert such as MERGE), which reads and writes the rows once. Check triggers that count statements.',
      description: 'Consecutive UPDATEs of the same rows, or an UPDATE plus INSERT … WHERE NOT EXISTS on the same table (an upsert), can be one statement.'
    },
    'AMB-P005': {
      title: 'Cursor loop that can be one statement',
      suggestion: 'Replace the loop with one set-based statement (UPDATE … FROM / MERGE, INSERT … SELECT, DELETE … WHERE EXISTS). Check first that the cursor cannot return the same target row twice, and that no per-row error handling or commit is needed.',
      description: 'A cursor loop whose body is a single DML using only the cursor row can be written as one set-based statement.'
    },
    'AMB-P006': {
      title: 'OR across indexed columns',
      suggestion: 'Split the query into UNION ALL branches, one per indexed condition (excluding the rows of earlier branches), or check that the plan combines the indexes.',
      description: 'OR between conditions on different indexed columns of one table can keep any single index from being used.'
    },
    'AMB-P007': {
      title: 'Calculation on a compared column',
      suggestion: 'Move the calculation to the other side of the comparison so the column stays bare (e.g. col + 1 = 5 becomes col = 5 - 1).',
      description: 'Arithmetic or concatenation on a column in WHERE / ON, or NVL / COALESCE / ISNULL on a join column, keeps an index on it from being used.'
    },
    'AMB-P008': {
      title: 'COUNT used to test existence',
      suggestion: 'Use EXISTS / NOT EXISTS (SELECT 1 FROM … WHERE …), which stops at the first matching row.',
      description: 'COUNT(*) > 0 counts every matching row; EXISTS stops at the first one.'
    },
    'AMB-P009': {
      title: 'DISTINCT hiding a duplicating join',
      suggestion: 'Test the other tables with EXISTS (SELECT 1 FROM … WHERE …) instead of joining them, and drop DISTINCT: no duplicates are produced and no sort or hash is needed.',
      description: 'The selected columns already include a unique key of one table; DISTINCT only removes duplicates the joins produce.'
    }
  }
}
