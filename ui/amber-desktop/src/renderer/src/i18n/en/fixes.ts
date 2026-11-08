/** Rule-based quick fixes: safety wording, the Fixes tab and the preview dialog. */
export const fixes = {
  safety: {
    EQUIVALENT: {
      label: 'Same result',
      detail: 'Returns the same rows for any data.'
    },
    EQUIVALENT_WITH_METADATA: {
      label: 'Same result given the table definitions',
      detail: 'The same rows, as long as the column types, nullability and keys are as described.'
    },
    CHANGES_BEHAVIOR_IN_EDGE_CASES: {
      label: 'Changes behavior in edge cases',
      detail: 'Read the note before applying it.'
    },
    notRated: 'Not rated'
  },
  quickFix: 'Quick fix: {title}',
  count: '{count, plural, one {# quick fix} other {# quick fixes}}',
  fixItem: 'Fix: {title}',
  fixButton: 'Fix…',
  loading: 'Looking for fixes',
  unavailable: 'This engine version does not suggest fixes yet.',
  stale: 'The script was edited since the last analysis. Analyze it again to see the fixes that still apply.',
  none: 'No quick fixes. Amber has rule-based fixes for some legacy patterns, such as NOT IN subqueries and LEFT JOINs that behave as inner joins.',
  summary: '{count, plural, one {# suggested fix} other {# suggested fixes}} to clean up legacy patterns',
  summaryHint: 'Each one is checked by the parser. Applying changes only the editor, never the file on disk.',
  applyAllTitle: 'Applies every fix rated “Same result” that does not overlap another one',
  applyAll: 'Apply all with “Same result” ({count})',
  statement: 'Statement {number}',
  applyFirst: 'Apply {id} first, then re-check',
  preview: 'Preview',
  dialog: {
    title: 'Quick fix',
    description: '{rule} · clean up this legacy pattern. Applying changes the editor only; save the file yourself when you are happy.',
    blocked: 'Apply {id} first, then re-check: the two fixes touch the same text.',
    apply: 'Apply to editor',
    noLongerMatches: 'This fix no longer matches the script. Analyze it again to refresh the suggestions.',
    edgeCase: 'Edge case: ',
    parserCheck: 'Parser check:',
    now: 'Now',
    after: 'After the fix'
  }
}
