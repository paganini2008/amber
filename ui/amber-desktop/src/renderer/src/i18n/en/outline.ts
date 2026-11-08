/** Outline view: each statement as one line of reading. Stat labels follow their number. */
export const outline = {
  stats: {
    lines: '{count, plural, one {line} other {lines}}',
    statements: '{count, plural, one {statement} other {statements}}',
    sources: '{count, plural, one {source} other {sources}}',
    intermediate: '{count, plural, other {intermediate}}',
    targets: '{count, plural, one {target} other {targets}}',
    ms: '{count, plural, other {ms to analyze}}'
  },
  listLabel: 'Statements in source order',
  expand: 'Expand',
  collapse: 'Collapse',
  findings: '{count, plural, one {# finding} other {# findings}}',
  loop: 'loop',
  via: {
    hint: 'What {routine} does when this statement calls it',
    writes: 'writes {writes, plural, one {# table} other {# tables}} via {routine}',
    reads: 'reads {reads, plural, one {# table} other {# tables}} via {routine}',
    writesReads: 'writes {writes} and reads {reads, plural, one {# table} other {# tables}} via {routine}'
  }
}
