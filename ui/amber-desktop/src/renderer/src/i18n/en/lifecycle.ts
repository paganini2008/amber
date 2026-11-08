/** Lifecycle view: what happens to each object over the script. */
export const lifecycle = {
  filter: 'Filter objects',
  groupBy: 'Group by',
  groupByLabel: 'Group or sort objects',
  sort: {
    role: 'Role',
    kind: 'Kind',
    firstUse: 'First use (no groups)',
    name: 'Name (no groups)'
  },
  group: {
    SOURCE: 'Sources',
    INTERMEDIATE: 'Intermediate',
    TARGET: 'Targets',
    STRUCTURE: 'Structure only'
  },
  allSteps: 'Every step',
  allStepsLabel: 'Show every step',
  empty: {
    filtered: 'No object matches the filter.',
    none: 'This script does not touch any table or view.'
  },
  matrixLabel: 'Object lifecycle matrix',
  createdHere: 'created here',
  droppedHere: 'dropped here',
  columnLineage: 'Column lineage',
  columnLineageOf: 'Column lineage of {name}',
  cellTitle: '{name} · step {step}',
  shortKind: {
    TEMP_TABLE: 'temp',
    TABLE_VARIABLE: 'var',
    MATERIALIZED_VIEW: 'mview'
  },
  legend: 'Legend',
  via: 'through a call',
  viaHint: 'The object is reached through a call to a routine defined in the script',
  event: {
    direct: '{access}, line {line}',
    via: '{access} via {via}, line {line}'
  }
}
