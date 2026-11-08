/** Statement view: one statement (or object) in full. */
export const statement = {
  empty: 'What does this statement really do? Select it in the outline, a graph or the editor.',
  stepOf: 'Step {step} of {total}',
  previous: 'Previous statement',
  next: 'Next statement',
  topLevel: 'Top level',
  containing: 'Containing statements',
  category: 'Category',
  confidence: 'Confidence',
  defines: 'Defines',
  objects: 'Objects',
  throughCall: 'Through the call to',
  via: 'via {routine}',
  contains: 'Contains {count, plural, one {# statement} other {# statements}}',
  object: {
    untracked: 'This object is not tracked in the script.',
    find: 'Find references',
    findHint: 'Every place this script uses it (Shift+F12)',
    facts: '{origin, select, created {Created in this script} other {Exists before the script}}{dropped, select, yes {, dropped before the end} other {}}, {count, plural, one {# access} other {# accesses}}.',
    lifecycle: 'Lifecycle'
  }
}
