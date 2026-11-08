/** Data flow view: objects and the statements that move data between them. */
export const dataflow = {
  summary: '{objects, plural, one {# object} other {# objects}} · {statements, plural, one {# statement moves} other {# statements move}} data',
  grouped: {
    label: '{count, plural, one {# group} other {# groups}} (over 300 nodes): double-click a group to open it',
    hint: 'Grouped into {count, plural, one {# group} other {# groups}} because the graph has more than 300 nodes. Double-click a group to open it.'
  },
  group: {
    openHint: 'Double-click to open this group',
    steps: 'Steps {from}–{to}',
    internal: '{count, plural, one {# internal table} other {# internal tables}}'
  },
  role: {
    SOURCE: 'source',
    INTERMEDIATE: 'intermediate',
    TARGET: 'target',
    STRUCTURE: 'structure only'
  },
  edge: {
    via: 'via {routine}'
  },
  regroup: 'Regroup',
  focus: 'Focus selection',
  fit: 'Fit graph',
  graphLabel: 'Data flow graph',
  empty: 'No statement in this script moves data between tables.',
  layingOut: 'Laying out {count, plural, one {# node} other {# nodes}}',
  layoutFailed: 'Could not lay out the graph: {error}'
}
