import { describe, expect, it } from 'vitest'
import type { Finding, OptimizeResult, Range, RuleInfo } from '@shared/protocol'
import fixture from './__fixtures__/optimize-oracle.json'
import { applyChanges } from './format'
import { canApplyFindingSql, rewrittenStatements, safeOptimizations, skipReasonText, sortChanges, statementChange, wholePair } from './optimize'
import { changedRuleCount, filterRules, groupRules, setRuleEnabled, setRuleSeverity } from './rules'
import { findingSuggestion, findingTitle, ruleDescription } from './kinds'

// Real engine output (`analyze`, `rewrite.optimize`, `engine.rules`) for a small Oracle script.
const text = fixture.text
const ranges = fixture.statementRanges as Record<string, Range>
const optimize = fixture.optimize as OptimizeResult
const findings = fixture.findings as Finding[]
const rules = fixture.rules as RuleInfo[]

describe('optimized statements', () => {
  it('replace exactly the statement text and reproduce the engine\'s optimized script', () => {
    const changes = optimize.statements.map((o) => statementChange(text, ranges[o.statement], o.original, o.optimized)!)
    expect(changes.every(Boolean)).toBe(true)
    expect(applyChanges(text, sortChanges(changes))).toBe(optimize.optimizedText)
  })

  it('refuse a statement the script no longer holds', () => {
    const o = optimize.statements[0]!
    expect(statementChange(text.replace(o.original, 'SELECT 1 FROM dual'), ranges[o.statement], o.original, o.optimized)).toBeNull()
    expect(statementChange(text, undefined, o.original, o.optimized)).toBeNull()
  })

  it('reject overlapping changes', () => {
    expect(() => sortChanges([{ from: 0, to: 10, insert: 'a' }, { from: 5, to: 12, insert: 'b' }])).toThrow()
    expect(sortChanges([{ from: 20, to: 25, insert: 'b' }, { from: 0, to: 10, insert: 'a' }]).map((c) => c.from)).toEqual([0, 20])
  })

  it('apply all safe: EQUIVALENT only', () => {
    const safe = safeOptimizations(optimize.statements)
    expect(safe.map((o) => o.statement)).toEqual(optimize.statements.filter((o) => o.safety === 'EQUIVALENT').map((o) => o.statement))
    expect(safe.length).toBeGreaterThan(0)
    expect(safe.every((o) => o.safety === 'EQUIVALENT')).toBe(true)
    expect(rewrittenStatements([{ ...optimize.statements[0]!, optimized: optimize.statements[0]!.original }])).toEqual([])
  })

  it('build a side-by-side pair with the changed lines', () => {
    const o = optimize.statements[0]!
    const pair = wholePair(o.original, o.optimized, o.statement)
    expect(pair.hunks.length).toBeGreaterThan(0)
    expect(pair.hunks.every((h) => o.original.includes(h.beforeText) && o.optimized.includes(h.afterText))).toBe(true)
  })

  it('word skip reasons', () => {
    expect(skipReasonText('EDGE_CASE')).toBe('changes behavior in edge cases')
    expect(skipReasonText('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })
})

describe('finding SQL', () => {
  it('can be applied for single-statement rewrites only', () => {
    const p007 = findings.find((f) => f.rule === 'AMB-P007')!
    const p004 = findings.find((f) => f.rule === 'AMB-P004')!
    expect(p007.suggestedSqlKind).toBe('REWRITE_OF_STATEMENT')
    expect(canApplyFindingSql(p007)).toBe(true)
    // Combines two UPDATEs: replacing the finding's statement alone would be wrong.
    expect(canApplyFindingSql(p004)).toBe(false)
    expect(canApplyFindingSql({ rule: 'AMB-P001', suggestedSql: 'CREATE INDEX i ON t (c)', suggestedSqlKind: 'ADDITIONAL_DDL', verified: 'NOT_CHECKED' })).toBe(false)
    expect(canApplyFindingSql({ rule: 'AMB-S005', suggestedSql: null, suggestedSqlKind: null, verified: null })).toBe(false)
  })

  it('use the engine text (already in the UI language); the catalog matches it and fills gaps', () => {
    for (const r of rules) {
      expect(findingTitle({ rule: r.id, title: r.title }), r.id).toBe(r.title)
      expect(findingTitle({ rule: r.id, title: '' }), r.id).toBe(r.title)
      expect(ruleDescription({ id: r.id, description: '' }), r.id).toBe(r.description)
    }
    const p004 = findings.find((f) => f.rule === 'AMB-P004')!
    expect(findingSuggestion(p004)).toBe(p004.suggestion)
  })
})

describe('rule settings', () => {
  it('group by category in a fixed order', () => {
    const groups = groupRules(rules)
    expect(groups.map((g) => g.category)).toEqual(['risk', 'performance', 'maintainability', 'info'])
    expect(groups.reduce((n, g) => n + g.rules.length, 0)).toBe(rules.length)
    expect(groupRules([{ ...rules[0]!, category: 'future' }, rules[1]!]).map((g) => g.category)).toEqual(['risk', 'future'])
  })

  it('search ids, titles and descriptions', () => {
    const text = (id: string, title: string, description: string): string => `${id} ${title} ${description}`
    expect(filterRules(rules, 'p001', text).map((r) => r.id)).toEqual(['AMB-P001'])
    expect(filterRules(rules, 'index missing', text).map((r) => r.id)).toContain('AMB-P001')
    expect(filterRules(rules, '  ', text)).toHaveLength(rules.length)
  })

  it('toggle, override and count changes against the defaults', () => {
    let s = { disabled: [] as string[], severity: {} }
    s = setRuleEnabled(s, 'AMB-S009', false)
    s = setRuleEnabled(s, 'AMB-P001', false)
    expect(s.disabled).toEqual(['AMB-P001', 'AMB-S009'])
    s = setRuleEnabled(s, 'AMB-P001', true)
    expect(s.disabled).toEqual(['AMB-S009'])
    s = setRuleSeverity(s, 'AMB-S008', 'HIGH')
    // Same as the default: stored, but not counted as a change.
    s = setRuleSeverity(s, 'AMB-S001', 'HIGH')
    expect(changedRuleCount(rules, s)).toBe(2)
    s = setRuleSeverity(s, 'AMB-S008', null)
    expect(s.severity).toEqual({ 'AMB-S001': 'HIGH' })
  })
})
