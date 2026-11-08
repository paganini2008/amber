/** Optimized SQL computed by Amber's rules (`rewrite.optimize`, finding SQL): no AI involved. */
export const optimize = {
  badge: 'Rule-based · no AI',
  badgeTitle: 'Computed by Amber\'s rules from the analysis and checked by the parser. No AI is involved and nothing is executed.',
  card: {
    title: 'Optimized SQL',
    statementLine: 'Statement {number} · line {line}',
    whatChanged: 'What changed',
    why: 'Why',
    notApplied: 'Not applied',
    check: 'Parser check:',
    differences: 'Structural differences',
    compare: 'Compare',
    compareTitle: 'Show the statement and its optimized version side by side',
    apply: 'Apply to editor',
    applyTitle: 'Replaces the statement in the editor as one undoable step. The file is not saved.',
    stale: 'The script changed since this was computed. Analyze again to refresh it.',
    note: 'Note'
  },
  ddl: {
    title: 'Suggested DDL (not applied to the script)',
    hint: 'Hints from the script\'s usage and the table definitions. Check data volumes, existing indexes and write load before creating anything.',
    copy: 'Copy DDL'
  },
  skip: {
    CONFLICT: 'overlaps a change that was applied',
    EDGE_CASE: 'changes behavior in edge cases',
    DISABLED: 'rule turned off in Settings → Rules',
    UNVERIFIED: 'the combined statement did not pass the parser check'
  },
  verified: {
    STRUCTURE_PRESERVED: 'Same structure',
    STRUCTURE_CHANGED: 'Structure changed',
    NOT_CHECKED: 'Parsed only',
    INVALID_SQL: 'Invalid SQL'
  },
  panel: {
    title: 'Optimized statements',
    summary: '{count, plural, one {# statement has an optimized version} other {# statements have an optimized version}}',
    applySafe: 'Apply all safe optimizations ({count})',
    applySafeTitle: 'Applies only the statements whose changes return the same rows for any data ("Same result"), as one undoable step.',
    loading: 'Computing optimized statements…',
    none: 'No optimized statements for this script.',
    unavailable: 'This engine version cannot optimize statements.',
    quickFixes: 'Quick fixes, one at a time',
    ddlOnly: 'Suggested DDL only',
    open: 'Open'
  },
  finding: {
    show: 'Show optimized SQL',
    showDdl: 'Show suggested DDL',
    copyOnly: 'This SQL replaces more than this statement. Copy it and edit the script by hand.'
  },
  dialog: {
    title: 'Optimized SQL',
    description: 'Rule-based, no AI. Nothing is executed or saved: Apply changes the editor only, as one undoable step.',
    now: 'Now',
    after: 'Optimized'
  },
  toast: {
    applied: '{count, plural, one {Applied the optimized statement} other {Applied # optimized statements}}',
    noMatch: 'The script no longer matches the optimized statement. Analyze again.'
  },
  details: {
    title: 'Optimized SQL',
    rewrites: '{count, plural, one {# change} other {# changes}}'
  }
}
