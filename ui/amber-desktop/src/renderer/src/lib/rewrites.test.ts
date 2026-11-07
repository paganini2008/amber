import { describe, expect, it } from 'vitest'
import type { Finding, Range, Rewrite } from '@shared/protocol'
import oracleJson from './__fixtures__/rewrites-oracle.json'
import pgJson from './__fixtures__/rewrites-pg.json'
import { applyEdits, groupByStatement, isBlocked, previewPair, rewritesAt, rewritesFor, safetyText, sameResultBatch, toChanges } from './rewrites'

interface Fixture {
  text: string
  statements: { id: number; range: Range }[]
  findings: Finding[]
  rewrites: Rewrite[]
}
const oracle = oracleJson as unknown as Fixture
const pg = pgJson as unknown as Fixture
const ranges = (f: Fixture): Map<number, Range> => new Map(f.statements.map((s) => [s.id, s.range]))

describe('safety wording', () => {
  it('words each level plainly and never says safe', () => {
    expect(safetyText('EQUIVALENT')).toMatchObject({ label: 'Same result', tone: 'same' })
    expect(safetyText('EQUIVALENT_WITH_METADATA').label).toBe('Same result given the table definitions')
    expect(safetyText('CHANGES_BEHAVIOR_IN_EDGE_CASES')).toMatchObject({ label: 'Changes behavior in edge cases', tone: 'edge' })
    expect(safetyText('SOMETHING_NEW').tone).toBe('edge')
    for (const s of ['EQUIVALENT', 'EQUIVALENT_WITH_METADATA', 'CHANGES_BEHAVIOR_IN_EDGE_CASES', 'X']) expect(JSON.stringify(safetyText(s))).not.toMatch(/\bsafe/i)
  })
})

describe('grouping and matching', () => {
  it('groups by statement and matches findings by rule and statement', () => {
    expect(groupByStatement(pg.rewrites).map((g) => [g.statement, g.items.map((r) => r.id)])).toEqual([
      [0, ['R1', 'R2']],
      [1, ['R3', 'R4']]
    ])
    const s002 = oracle.findings.find((f) => f.rule === 'AMB-S002')!
    expect(rewritesFor(oracle.rewrites, s002).map((r) => r.id)).toEqual(['R1'])
    const s006 = oracle.findings.find((f) => f.rule === 'AMB-S006')!
    expect(rewritesFor(oracle.rewrites, s006)).toEqual([])
  })
  it('finds the rewrites at a cursor: on an edit first, else in the statement', () => {
    const r2 = pg.rewrites.find((r) => r.id === 'R2')!
    expect(rewritesAt(pg.rewrites, r2.edits[0]!.range.start.offset + 3, ranges(pg)).map((r) => r.id)).toEqual(['R2'])
    expect(rewritesAt(pg.rewrites, pg.statements[0]!.range.start.offset, ranges(pg)).map((r) => r.id)).toEqual(['R1', 'R2'])
    expect(rewritesAt(pg.rewrites, pg.text.length + 10, ranges(pg))).toEqual([])
  })
})

describe('applying edits', () => {
  it('applies every edit last-first and reproduces the engine rewritten statement', () => {
    for (const f of [oracle, pg]) {
      for (const r of f.rewrites) {
        const range = ranges(f).get(r.statement)!
        const p = previewPair(f.text, range, r)
        expect(p.after).toBe(r.rewrittenStatement)
        expect(p.before).toBe(f.text.slice(range.start.offset, range.end.offset))
        // Applying to the whole text equals splicing the rewritten statement in.
        expect(applyEdits(f.text, r.edits)).toBe(f.text.slice(0, range.start.offset) + r.rewrittenStatement + f.text.slice(range.end.offset))
      }
    }
  })
  it('maps each edit to a hunk on both sides of the preview', () => {
    const r = oracle.rewrites.find((x) => x.id === 'R2')!
    const p = previewPair(oracle.text, ranges(oracle).get(r.statement)!, r)
    expect(p.pair.hunks).toHaveLength(3)
    for (const h of p.pair.hunks) {
      expect(p.before.slice(h.before.start.offset, h.before.end.offset)).toBe(h.beforeText)
      expect(p.after.slice(h.after.start.offset, h.after.end.offset)).toBe(h.afterText)
    }
    expect(p.pair.hunks[0]!.afterText).toBe('NOT EXISTS')
  })
  it('rejects overlapping edits', () => {
    const r = (from: number, to: number, replacement: string): Rewrite['edits'][number] => ({
      range: { start: { offset: from, line: 1, column: from + 1 }, end: { offset: to, line: 1, column: to + 1 } },
      replacement
    })
    expect(applyEdits('select a, b from t', [r(10, 11, 'c'), r(7, 8, 'x')])).toBe('select x, c from t')
    expect(() => toChanges([r(0, 5, 'x'), r(3, 8, 'y')])).toThrow(/Overlapping/)
  })
})

describe('batches and conflicts', () => {
  it('takes only "Same result" rewrites and skips conflicting or overlapping ones', () => {
    const { apply, skipped } = sameResultBatch(pg.rewrites)
    expect(apply.map((r) => r.id)).toEqual(['R1', 'R3'])
    expect(skipped).toEqual([])
    const first = pg.rewrites[0]!
    const twin: Rewrite = { ...first, id: 'R9' }
    const blocked: Rewrite = { ...pg.rewrites[2]!, id: 'R10', conflictsWith: 'R3' }
    const b = sameResultBatch([...pg.rewrites, twin, blocked])
    expect(b.apply.map((r) => r.id)).toEqual(['R1', 'R3'])
    expect(b.skipped.map((r) => r.id)).toEqual(['R9', 'R10'])
    expect(isBlocked(blocked)).toBe(true)
    expect(isBlocked(first)).toBe(false)
  })
})
