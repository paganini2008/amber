/** ER diagram view: tables, keys and the relationships the script implies. */
export const er = {
  title: 'ER diagram',
  graphLabel: 'Entity-relationship diagram',
  loading: 'Reading tables and joins…',
  layingOut: 'Laying out the diagram…',
  unavailable: 'This engine version cannot build an ER diagram.',
  error: 'Could not build the ER diagram: {message}',
  analyzeFirst: 'Analyze the script first; the diagram is built from the analysis.',
  empty: {
    noTables: 'This script does not read or write any table.',
    noRelationships: 'No relationships found. Foreign keys, joins and matching column names create them.',
    filtered: 'No relationship matches the filters.'
  },
  search: 'Find a table',
  searchMatches: '{count, plural, =0 {No match} one {# match} other {# matches}}',
  fit: 'Fit to view',
  copyMermaid: 'Copy Mermaid',
  mermaidCopied: 'Mermaid erDiagram copied.',
  dataFlow: 'Data flow',
  dataFlowHint: 'Also draw where data moves between tables (INSERT … SELECT), not only keys and joins.',
  minConfidence: 'Show',
  confidenceAtLeast: {
    POSSIBLE: 'All relationships',
    INFERRED: 'Inferred and certain',
    CERTAIN: 'Certain only'
  },
  evidenceLabel: 'Evidence',
  evidence: {
    DECLARED_FK: 'Declared FK',
    METADATA_FK: 'Database FK',
    JOIN: 'Joins',
    NAME_MATCH: 'Name matches',
    DATA_FLOW: 'Data flow'
  },
  evidenceLong: {
    DECLARED_FK: 'A foreign key declared in the script',
    METADATA_FK: 'A foreign key from the database (metadata)',
    JOIN: 'Joined in the script',
    NAME_MATCH: 'Only the column names suggest it',
    DATA_FLOW: 'Data moves from one table into the other'
  },
  confidence: {
    CERTAIN: 'Certain',
    INFERRED: 'Inferred',
    POSSIBLE: 'Possible'
  },
  cardinality: {
    MANY_TO_ONE: 'many to one',
    ONE_TO_ONE: 'one to one',
    ONE_TO_MANY: 'one to many',
    MANY_TO_MANY: 'many to many',
    UNKNOWN: 'cardinality unknown'
  },
  around: {
    label: 'Around this table',
    hops: '{count, plural, one {# step} other {# steps}}',
    showing: 'Around {name}',
    clear: 'Show all tables'
  },
  card: {
    pk: 'Primary key',
    fk: 'Foreign key',
    joined: 'Used in a join',
    inScript: 'Created in this script',
    more: '{count, plural, one {+# more column} other {+# more columns}}',
    less: 'Show key columns only',
    noColumns: 'No columns known',
    source: {
      DDL: 'from the script\'s DDL',
      METADATA: 'from database metadata',
      USAGE: 'from how the script uses it'
    }
  },
  isolated: {
    title: '{count, plural, one {# table without relationships} other {# tables without relationships}}',
    show: 'Show',
    hide: 'Hide'
  },
  legend: {
    certain: 'certain (foreign key)',
    inferred: 'inferred from joins',
    possible: 'possible (names only)',
    many: 'many',
    one: 'one',
    flow: 'data flow',
    hint: 'Click a line for the joins behind it'
  },
  panel: {
    relationship: 'Relationship',
    entity: 'Table',
    close: 'Close',
    evidence: 'Evidence',
    confidence: 'Confidence',
    cardinality: 'Cardinality',
    constraint: 'Constraint',
    note: 'Engine note',
    occurrences: '{count, plural, =0 {Not joined in the script} one {Seen in # place} other {Seen in # places}}',
    occurrencesShown: 'Showing the first {shown} of {count}.',
    occurrenceLine: 'Line {line}',
    relationships: '{count, plural, =0 {No relationships} one {# relationship} other {# relationships}}',
    columns: '{count, plural, one {# column} other {# columns}}',
    kind: 'Kind',
    focus: 'Show around this table',
    jump: 'Show in the editor'
  },
  stats: '{entities, plural, one {# table} other {# tables}} · {relationships, plural, one {# relationship} other {# relationships}} · {joins, plural, one {# join condition read} other {# join conditions read}} · {unresolved} unresolved',
  upgraded: '{count, plural, one {Database metadata confirmed # relationship.} other {Database metadata confirmed # relationships.}}',
  upgradedDismiss: 'Dismiss'
}
