/** Settings → Rules: which checks run and how serious their findings are. */
export const rules = {
  tab: 'Rules',
  lead: 'Which checks run and how serious their findings are. Changes apply at once to the analysis, quick fixes, optimized SQL, the handover document, change impact and the AI context.',
  search: 'Search rules',
  category: {
    risk: 'Risks',
    performance: 'Performance',
    maintainability: 'Maintainability',
    info: 'Information',
    other: 'Other'
  },
  enable: 'Turn {id} on or off',
  severity: 'Severity of {id}',
  severityDefault: 'Default ({severity})',
  needsMetadata: 'Needs table definitions',
  needsMetadataTitle: 'Reports only when table definitions are known: DDL files, a metadata pack or online mode.',
  hasQuickFix: 'Has quick fix',
  hasQuickFixTitle: 'Can offer a verified rewrite of the statement.',
  reset: 'Reset to defaults',
  changed: '{count, plural, =0 {All rules use their defaults} one {# rule changed} other {# rules changed}}',
  off: 'Off',
  loading: 'Loading rules…',
  unavailable: 'This engine version has no rule settings.',
  error: 'Could not load the rules: {message}',
  noMatch: 'No rule matches "{query}".'
}
