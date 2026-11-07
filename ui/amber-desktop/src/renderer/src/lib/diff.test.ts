import { describe, expect, it } from 'vitest'
import type { DiffReason, DiffResult } from '@shared/protocol'
import diffJson from './__fixtures__/diff.json'
import { columnRows, countsText, diffMarkdown, lineText, noChanges, pairFor, pairRows, riskText, sortReasons } from './diff'

const result = diffJson as unknown as DiffResult

describe('reasons', () => {
  it('puts the highest risk first, then follows the script', () => {
    const sorted = sortReasons(result.summary.reasons)
    expect(sorted.map((r) => r.level)).toEqual(['HIGH', 'HIGH', 'HIGH', 'HIGH', 'MEDIUM', 'MEDIUM', 'MEDIUM', 'LOW'])
    expect(sorted[0]!.code).toBe('FILTER_REMOVED')
    // Within HIGH: DELETE (line 39), INSERT (42), then the new TRUNCATE (65).
    expect(sorted.slice(0, 4).map((r) => r.code)).toEqual(['FILTER_REMOVED', 'COLUMN_SOURCE_CHANGED', 'TRUNCATE_ADDED', 'DESTRUCTIVE_ACCESS'])
    const odd: DiffReason[] = [
      { level: 'LOW', code: 'a', message: 'a', beforeStatement: null, afterStatement: null, beforeRange: null, afterRange: null },
      { level: 'WEIRD', code: 'b', message: 'b', beforeStatement: null, afterStatement: null, beforeRange: null, afterRange: null },
      { level: 'HIGH', code: 'c', message: 'c', beforeStatement: null, afterStatement: null, beforeRange: null, afterRange: null }
    ]
    expect(sortReasons(odd).map((r) => r.code)).toEqual(['c', 'a', 'b'])
    expect(riskText('HIGH')).toBe('High risk')
  })
})

describe('column changes', () => {
  it('merges the sources and transform change of one column into one row', () => {
    const rows = columnRows(result.lineage.columns)
    expect(rows).toHaveLength(1)
    expect(rows[0]).toMatchObject({
      target: 'report.daily_revenue',
      column: 'revenue',
      kinds: ['SOURCES_CHANGED', 'TRANSFORM_CHANGED'],
      before: ['shop.orders.total_amount'],
      after: ['shop.orders.customer_id'],
      removed: ['shop.orders.total_amount'],
      added: ['shop.orders.customer_id'],
      beforeTransforms: ['AGGREGATE', 'EXPRESSION'],
      afterTransforms: ['AGGREGATE']
    })
  })
})

describe('statements', () => {
  it('hides unchanged statements unless asked, keeping containers with changes inside', () => {
    const changed = pairRows(result.statements, false)
    expect(changed.map((r) => r.pair.status)).toEqual(['FORMAT_ONLY', 'MODIFIED', 'MODIFIED', 'ADDED'])
    expect(pairRows(result.statements, true)).toHaveLength(15)
    const nested = pairRows(
      [{ ...result.statements[0]!, status: 'UNCHANGED', children: [{ ...result.statements[6]!, children: [] }] }],
      false
    )
    expect(nested.map((r) => [r.depth, r.pair.status])).toEqual([
      [0, 'UNCHANGED'],
      [1, 'MODIFIED']
    ])
  })
  it('finds the pair of a change and words lines and counts', () => {
    expect(pairFor(result.statements, 6, 6)?.kind).toBe('DELETE')
    expect(pairFor(result.statements, null, 14)?.status).toBe('ADDED')
    expect(pairFor(result.statements, 99, null)).toBeNull()
    expect(countsText(result.summary.statements)).toBe('2 modified, 1 added, 1 formatting only, 11 unchanged')
    const r = result.summary.reasons
    expect(lineText(r[0]!.beforeRange, r[0]!.afterRange)).toBe('line 39')
    expect(lineText(r[3]!.beforeRange, r[3]!.afterRange)).toBe('line 43 → 42')
    expect(lineText(null, r[1]!.afterRange)).toBe('new line 65')
  })
})

describe('diffMarkdown', () => {
  it('writes a ticket-ready summary', () => {
    const md = diffMarkdown(result)
    expect(md.startsWith('## Change impact: high risk\n')).toBe(true)
    expect(md).toContain('Compared `before.sql` with `after.sql`: 2 modified, 1 added, 1 formatting only, 11 unchanged.')
    expect(md).toContain('- **HIGH** DELETE report.daily_revenue lost its WHERE clause')
    expect(md).toContain('| report.daily_revenue.revenue | shop.orders.total_amount | shop.orders.customer_id (AGGREGATE, EXPRESSION → AGGREGATE) |')
    expect(md).toContain('### Filters, joins and grouping')
    expect(md).toContain('Nothing was executed')
    expect(md).not.toMatch(/safe|equivalent/i)
    expect(md.indexOf('**HIGH**')).toBeLessThan(md.indexOf('**MEDIUM**'))
  })
  it('escapes table cells', () => {
    const r = structuredClone(result)
    r.lineage.columns = [{ ...r.lineage.columns[0]!, column: 'a|b' }]
    expect(diffMarkdown(r)).toContain('report.daily_revenue.a\\|b')
  })
})

describe('no changes', () => {
  it('recognises two versions that read the same', () => {
    expect(noChanges(result.summary)).toBe(false)
    const same = { ...result.summary, reasons: [], statements: { unchanged: 14, formatOnly: 0, modified: 0, added: 0, removed: 0, moved: 0 } }
    expect(noChanges(same)).toBe(true)
    expect(noChanges({ ...same, statements: { ...same.statements, formatOnly: 1 } })).toBe(false)
  })
})
