/** Refactor script: verified local rewrites applied together, pass after pass, then the layout. */
export const refactor = {
  button: 'Refactor script',
  title: 'Refactor script',
  lead: 'Amber applies every rewrite it can prove keeps the result the same for any data, pass after pass, and shows the outcome in Change impact. Nothing changes in the editor until you apply it.',
  format: 'Lay the result out like Format script',
  run: 'Refactor',
  running: 'Refactoring, pass {pass}…',
  nothing: 'Amber found nothing it can refactor without changing the result.',
  unavailable: 'This engine cannot refactor scripts.',
  afterName: '{name} (refactored)',
  applied: 'Refactored script',
  banner: {
    title: 'Refactored script',
    rewrites: '{count, plural, one {# rewrite} other {# rewrites}} in {passes, plural, one {# pass} other {# passes}}, each keeping the result the same.',
    noRewrites: 'No rewrite was needed.',
    formatted: 'Laid out like Format script.',
    logicKept: 'Logic check: the conditions, values, source tables, output columns and lineage are the same as before.',
    logicChanged: 'Logic check: Amber found a difference in the logic. Do not apply this refactoring; please report it.',
    discard: 'Discard',
    apply: 'Apply to editor'
  },
  ai: 'Refactor with AI',
  aiHint: 'The AI restructures the statement for the next maintainer. Amber applies its rewrite only if the logic check finds no difference at all.',
  aiTitle: 'AI refactoring'
}
