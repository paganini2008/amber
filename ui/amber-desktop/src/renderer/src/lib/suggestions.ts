import type { Finding, OptimizedStatement, Rewrite } from '@shared/protocol'
import { severityRank } from './findings'
import { canApplyFindingSql } from './optimize'
import { sameResultBatch } from './rewrites'

/**
 * The Suggestions view's model: every finding of the script, grouped by statement and then by rule, with
 * what can fix it locally (quick fixes from `rewrite.suggest`, a finding's own SQL, the statement's optimized
 * version from `rewrite.optimize`). What has no local fix is offered to the AI.
 */

/** How a group of findings can be fixed. */
export type FixKind = 'quickFix' | 'findingSql' | 'none'

/** One rule in one statement: e.g. "Table read several times" ×7. */
export interface SuggestionEntry {
  /** Stable key: `${statement}:${rule}`. */
  key: string
  rule: string
  /** The most severe of its findings. */
  severity: string
  statement: number
  /** Sorted by position. */
  findings: { finding: Finding; index: number }[]
  /** Local quick fixes for this rule in this statement. */
  fixes: Rewrite[]
  /** The first finding that carries SQL Amber can apply (rule-based, no quick fix). */
  findingSql: number | null
  fix: FixKind
}

export interface StatementSuggestions {
  statement: number
  /** The most severe entry's severity. */
  severity: string
  entries: SuggestionEntry[]
  /** The statement's optimized version (all verified rewrites together), when it changes the SQL. */
  optimized: OptimizedStatement | null
  /** Entries without any local fix: what the AI is asked about. */
  unfixed: SuggestionEntry[]
}

export interface SuggestionsModel {
  statements: StatementSuggestions[]
  findingCount: number
  entryCount: number
  /** Entries with a local fix. */
  fixableCount: number
  /** Quick fixes that keep the same result for any data and do not overlap: "Apply all safe fixes". */
  safeFixes: Rewrite[]
}

export type SuggestionFilter = 'all' | 'fixable' | 'unfixed'

export function buildSuggestions(findings: Finding[], rewrites: Rewrite[], optimized: OptimizedStatement[]): SuggestionsModel {
  const byStatement = new Map<number, Map<string, SuggestionEntry>>()
  findings.forEach((finding, index) => {
    let rules = byStatement.get(finding.statement)
    if (!rules) byStatement.set(finding.statement, (rules = new Map()))
    const key = `${finding.statement}:${finding.rule}`
    let entry = rules.get(key)
    if (!entry) {
      entry = { key, rule: finding.rule, severity: finding.severity, statement: finding.statement, findings: [], fixes: [], findingSql: null, fix: 'none' }
      rules.set(key, entry)
    }
    entry.findings.push({ finding, index })
    if (severityRank(finding.severity) < severityRank(entry.severity)) entry.severity = finding.severity
  })

  const statements: StatementSuggestions[] = []
  let fixableCount = 0
  let entryCount = 0
  for (const [statement, rules] of byStatement) {
    const entries = [...rules.values()]
    for (const e of entries) {
      e.findings.sort((a, b) => a.finding.range.start.offset - b.finding.range.start.offset)
      e.fixes = rewrites.filter((r) => r.rule === e.rule && r.statement === statement)
      // A finding whose SQL is a quick fix (rewriteId) is fixed through the quick fix.
      const withSql = e.findings.find(({ finding }) => !finding.rewriteId && canApplyFindingSql(finding))
      e.findingSql = withSql ? withSql.index : null
      e.fix = e.fixes.length ? 'quickFix' : e.findingSql !== null ? 'findingSql' : 'none'
      if (e.fix !== 'none') fixableCount++
    }
    entries.sort(byEntry)
    entryCount += entries.length
    const opt = optimized.find((o) => o.statement === statement && o.optimized && o.optimized !== o.original) ?? null
    statements.push({ statement, severity: entries[0]!.severity, entries, optimized: opt, unfixed: entries.filter((e) => e.fix === 'none') })
  }
  statements.sort((a, b) => severityRank(a.severity) - severityRank(b.severity) || firstOffset(a) - firstOffset(b))
  return { statements, findingCount: findings.length, entryCount, fixableCount, safeFixes: sameResultBatch(rewrites).apply }
}

export function filterSuggestions(model: SuggestionsModel, filter: SuggestionFilter): StatementSuggestions[] {
  if (filter === 'all') return model.statements
  return model.statements
    .map((s) => ({ ...s, entries: s.entries.filter((e) => (filter === 'fixable' ? e.fix !== 'none' : e.fix === 'none')) }))
    .filter((s) => s.entries.length > 0)
}

/**
 * What the AI is asked to fix in one statement, in English (the context is built and masked by the engine,
 * which also masks the names in these notes).
 */
export function aiFixNotes(entries: SuggestionEntry[]): string {
  const lines = entries.map((e) => {
    const f = e.findings[0]!.finding
    const where = e.findings.map(({ finding }) => `line ${finding.range.start.line}`).join(', ')
    const more = e.findings.length > 1 ? ` (${e.findings.length} places: ${where})` : ` (${where})`
    return `- ${e.rule} ${f.title}${more}: ${f.message}`
  })
  return [
    'Rewrite this statement to fix the problems below. Keep the result exactly the same for any data:',
    'same rows, same columns, same order of columns. Return one complete rewritten statement per suggestion.',
    ...lines
  ].join('\n')
}

/** What the AI is asked when it refactors a statement: structure for the next maintainer, never the result. */
export function aiRefactorNotes(entries: SuggestionEntry[]): string {
  const known = entries.length ? ['Problems Amber found in it:', ...aiFixNotes(entries).split('\n').slice(2)] : []
  return [
    'Refactor this statement for readability and maintenance. The result must stay exactly the same for any data:',
    'same rows, same columns, same order of columns. Factor repeated subqueries and copy-pasted blocks into CTEs,',
    'give tables and CTEs clear aliases, drop conditions that are always true and sorts nothing uses, and keep the',
    'comments that explain business rules. Return one complete rewritten statement.',
    ...known
  ].join('\n')
}

function byEntry(a: SuggestionEntry, b: SuggestionEntry): number {
  return severityRank(a.severity) - severityRank(b.severity) || a.findings[0]!.finding.range.start.offset - b.findings[0]!.finding.range.start.offset
}

function firstOffset(s: StatementSuggestions): number {
  return Math.min(...s.entries.map((e) => e.findings[0]!.finding.range.start.offset))
}
