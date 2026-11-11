import { describe, expect, it } from 'vitest'
import type { Finding, OptimizedStatement, Rewrite } from '@shared/protocol'
import { aiFixNotes, buildSuggestions, filterSuggestions } from './suggestions'

const pos = (offset: number, line: number) => ({ offset, line, column: 1 })
const finding = (rule: string, statement: number, offset: number, severity = 'MEDIUM', extra: Partial<Finding> = {}): Finding => ({
  rule,
  severity,
  title: `${rule} title`,
  message: `${rule} at ${offset}`,
  suggestion: null,
  statement,
  range: { start: pos(offset, offset / 10 + 1), end: pos(offset + 5, offset / 10 + 1) },
  confidence: 'CERTAIN',
  ...extra
})
const rewrite = (id: string, rule: string, statement: number, safety = 'EQUIVALENT', from = 0): Rewrite => ({
  id,
  rule,
  title: id,
  explanation: '',
  statement,
  edits: [{ range: { start: pos(from, 1), end: pos(from + 3, 1) }, replacement: '' }],
  rewrittenStatement: '',
  safety,
  safetyNote: null,
  check: 'STRUCTURE_PRESERVED',
  checkDifferences: [],
  conflictsWith: null
}) as unknown as Rewrite

describe('buildSuggestions', () => {
  const findings = [
    finding('AMB-S005', 1, 300),
    finding('AMB-S011', 1, 120, 'LOW'),
    finding('AMB-S005', 1, 200),
    finding('AMB-S008', 2, 900, 'MEDIUM', { suggestedSql: 'SELECT 1', suggestedSqlKind: 'REWRITE_OF_STATEMENT', verified: 'STRUCTURE_PRESERVED' }),
    finding('AMB-I002', 0, 10, 'INFO')
  ]
  const rewrites = [rewrite('r1', 'AMB-S011', 1, 'EQUIVALENT', 120), rewrite('r2', 'AMB-S002', 1, 'EDGE_CASES', 400)]
  const optimized = [{ statement: 1, original: 'a', optimized: 'b', applied: [], skipped: [], safety: 'EQUIVALENT', check: null, additionalDdl: [] }] as OptimizedStatement[]

  it('groups findings by statement and rule, worst statement first', () => {
    const m = buildSuggestions(findings, rewrites, optimized)
    expect(m.findingCount).toBe(5)
    expect(m.statements.map((s) => s.statement)).toEqual([1, 2, 0])
    const first = m.statements[0]!
    expect(first.entries.map((e) => [e.rule, e.findings.length])).toEqual([
      ['AMB-S005', 2],
      ['AMB-S011', 1]
    ])
    // Places within one rule are in script order.
    expect(first.entries[0]!.findings.map((f) => f.finding.range.start.offset)).toEqual([200, 300])
    expect(first.optimized?.statement).toBe(1)
  })

  it('tells what each group can be fixed with', () => {
    const m = buildSuggestions(findings, rewrites, optimized)
    const fix = (statement: number, rule: string) => m.statements.find((s) => s.statement === statement)!.entries.find((e) => e.rule === rule)!.fix
    expect(fix(1, 'AMB-S011')).toBe('quickFix')
    expect(fix(2, 'AMB-S008')).toBe('findingSql')
    expect(fix(1, 'AMB-S005')).toBe('none')
    expect(m.fixableCount).toBe(2)
    expect(m.entryCount).toBe(4)
    expect(m.statements[0]!.unfixed.map((e) => e.rule)).toEqual(['AMB-S005'])
    // Only "same result" quick fixes go into the batch.
    expect(m.safeFixes.map((r) => r.id)).toEqual(['r1'])
  })

  it('filters by what can be fixed here', () => {
    const m = buildSuggestions(findings, rewrites, optimized)
    expect(filterSuggestions(m, 'fixable').flatMap((s) => s.entries.map((e) => e.rule))).toEqual(['AMB-S011', 'AMB-S008'])
    expect(filterSuggestions(m, 'unfixed').flatMap((s) => s.entries.map((e) => e.rule))).toEqual(['AMB-S005', 'AMB-I002'])
  })

  it('ignores an optimized version that changes nothing', () => {
    const same = [{ ...optimized[0]!, optimized: 'a' }]
    expect(buildSuggestions(findings, rewrites, same).statements[0]!.optimized).toBeNull()
  })
})

describe('aiFixNotes', () => {
  it('lists every problem with its places for the AI', () => {
    const m = buildSuggestions([finding('AMB-S005', 1, 200), finding('AMB-S005', 1, 300)], [], [])
    const notes = aiFixNotes(m.statements[0]!.entries)
    expect(notes).toContain('Keep the result exactly the same')
    expect(notes).toContain('- AMB-S005 AMB-S005 title (2 places: line 21, line 31): AMB-S005 at 200')
  })
})
