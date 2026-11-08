/** Lineage view, column mapping and lineage labels. */
export const lineage = {
  title: 'Column lineage',
  notAvailable: 'Update the engine to see where each column\'s values come from.',
  find: 'Find a column',
  columnsOf: 'Columns of',
  showAllColumns: 'Show all columns',
  columns: 'Columns',
  noMatch: 'No column matches.',
  writtenByStep: 'Columns written by step {step}',
  counts: '{columns, plural, one {# column} other {# columns}} across {owners, plural, one {# object} other {# objects}}',
  truncated: 'showing the first {count} columns of the path',
  direction: {
    label: 'Direction',
    upstream: {
      label: 'Where it comes from',
      hint: 'Upstream: follow the value back to source tables'
    },
    downstream: {
      label: 'What it affects',
      hint: 'Downstream: what breaks if I change this?'
    },
    both: {
      label: 'Both',
      hint: 'Upstream and downstream'
    }
  },
  indirect: {
    short: 'Filters, joins, groups',
    label: 'Include filters, joins and groups',
    hint: 'Columns used in WHERE, JOIN and GROUP BY decide which rows arrive'
  },
  fit: 'Fit the whole path',
  empty: {
    pick: 'What breaks if you change a column? Pick one on the left to see where its values come from and what they feed.',
    step: 'This step moves no columns.',
    column: 'No lineage for this column.'
  },
  layingOut: 'Laying out',
  clipped: 'Part of the path is outside the view. Drag to pan, or use Fit for the whole path.',
  legend: {
    flows: 'value flows',
    decides: 'decides rows (filter, join, group)',
    variable: 'variable',
    parameter: 'parameter',
    clickEdge: 'Click an edge to see its SQL'
  },
  mapping: {
    trace: 'Trace {column} ({kind})',
    traceOutput: 'Trace {column}',
    showWhere: 'Show where this happens',
    moreSources: '+{count, plural, one {# more source} other {# more sources}}',
    decidedBy: 'Which rows arrive is decided by',
    seen: 'Columns seen in the script',
    open: 'Open in lineage'
  },
  card: {
    rows: 'which rows arrive',
    value: 'value',
    moreColumns: '+{count, plural, one {# more column} other {# more columns}}'
  },
  scope: {
    block: 'block (line {line})',
    batch: 'batch (line {line})'
  },
  owner: {
    variableIn: 'variable in {scope}',
    parameterOf: 'parameter of {scope}',
    unresolvedTitle: 'Unresolved table',
    unresolvedSubtitle: 'unresolved: the table could not be determined',
    resultOfStep: 'Result of step {step}',
    queryResult: 'Query result'
  },
  ownerKind: {
    TABLE: 'table',
    TEMP_TABLE: 'temp table',
    TABLE_VARIABLE: 'table variable',
    VIEW: 'view',
    MATERIALIZED_VIEW: 'materialized view',
    CTE: 'CTE',
    SUBQUERY: 'subquery',
    RESULT: 'query result',
    VARIABLE: 'variable',
    PARAMETER: 'parameter',
    UNKNOWN: 'unresolved'
  },
  transform: {
    PASS_THROUGH: 'copy',
    EXPRESSION: 'expression',
    AGGREGATE: 'aggregate',
    WINDOW: 'window',
    CASE: 'case',
    CAST: 'cast',
    CONSTANT: 'constant'
  },
  role: {
    FILTER: 'filter',
    JOIN: 'join',
    GROUP: 'group',
    INDIRECT: 'indirect'
  }
}
