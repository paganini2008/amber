import { describe, expect, it } from 'vitest'
import type { AnalysisResult } from '@shared/protocol'
import totalsJson from './__fixtures__/totals.json'
import {
  indexLineage,
  lineageToMermaid,
  pickableColumns,
  statementLineage,
  traceLineage,
  type LineageIndex
} from './lineage'

// Fixture: engine output for a script written for these tests:
//   CREATE TEMP TABLE tmp_paid AS SELECT … FROM shop.orders o WHERE o.status = 'paid';
//   INSERT INTO report.customer_totals … WITH per_customer AS (… FROM tmp_paid GROUP BY customer_id)
//   SELECT … FROM per_customer pc JOIN shop.customers c ON …;
const totals = totalsJson as unknown as AnalysisResult
const index = indexLineage(totals.lineage!)

function nodeId(idx: LineageIndex, ownerKey: string, column: string): number {
  for (const n of idx.nodes.values()) if (n.ownerKey === ownerKey && n.column === column) return n.id
  throw new Error(`no node ${ownerKey}.${column}`)
}
const label = (id: number): string => {
  const n = index.nodes.get(id)!
  return `${n.ownerKey}.${n.column}`
}

describe('lineage index', () => {
  it('merges nodes of the same owner key and prefers the specific kind', () => {
    const owner = index.owners.get('TMP_PAID')!
    expect(owner.kind).toBe('TEMP_TABLE')
    expect(owner.nodeIds.length).toBeGreaterThan(1)
    expect(index.rowsOf.has('REPORT.CUSTOMER_TOTALS')).toBe(true)
  })

  it('lists written columns first and never the *rows* pseudo column', () => {
    const cols = pickableColumns(index)
    expect(cols.some((c) => c.column === '*rows*')).toBe(false)
    expect(cols[0]!.derived).toBe(true)
  })
})

describe('traceLineage', () => {
  const target = nodeId(index, 'REPORT.CUSTOMER_TOTALS', 'customer_id')

  it('follows direct edges upstream through the CTE and the temp table to the source', () => {
    const t = traceLineage(index, target, { direction: 'upstream', indirect: false })
    const names = [...t.nodes].map(label)
    expect(names).toContain('SHOP.ORDERS.customer_id')
    expect(names).toContain('TMP_PAID.customer_id')
    expect(names.some((n) => n.startsWith('cte:PER_CUSTOMER'))).toBe(true)
    expect(names.some((n) => n.endsWith('*rows*'))).toBe(false)
    expect([...t.edges].every((e) => index.edges[e]!.kind === 'DIRECT')).toBe(true)
  })

  it('adds FILTER, JOIN and GROUP columns when indirect is on', () => {
    const t = traceLineage(index, target, { direction: 'upstream', indirect: true })
    const roles = new Set([...t.edges].map((e) => index.edges[e]!.role).filter(Boolean))
    expect(roles).toEqual(new Set(['JOIN', 'GROUP', 'FILTER']))
    expect([...t.nodes].map(label)).toContain('SHOP.ORDERS.status')
  })

  it('finds downstream impact of a source column', () => {
    const source = nodeId(index, 'SHOP.ORDERS', 'customer_id')
    const t = traceLineage(index, source, { direction: 'downstream', indirect: false })
    expect([...t.nodes].map(label)).toContain('REPORT.CUSTOMER_TOTALS.customer_id')
    const both = traceLineage(index, source, { direction: 'both', indirect: false })
    expect(both.nodes.size).toBeGreaterThanOrEqual(t.nodes.size)
  })

  it('stops at the node cap and says so', () => {
    const source = nodeId(index, 'SHOP.ORDERS', 'customer_id')
    const t = traceLineage(index, source, { direction: 'downstream', indirect: true, maxNodes: 2 })
    expect(t.nodes.size).toBe(2)
    expect(t.truncated).toBe(true)
  })
})

describe('statementLineage', () => {
  it('maps each output column to its sources through CTE hops', () => {
    const insert = statementLineage(index, 1)
    const outputs = insert.mappings.map((m) => m.output.column)
    expect(outputs).toEqual(expect.arrayContaining(['customer_id', 'region']))
    const cust = insert.mappings.find((m) => m.output.column === 'customer_id')!
    const path = cust.paths[0]!
    expect(path.nodes[0]!.ownerKey).toBe('TMP_PAID')
    expect(path.nodes.some((n) => n.ownerKind === 'CTE')).toBe(true)
    expect(path.nodes[path.nodes.length - 1]!.ownerKey).toBe('REPORT.CUSTOMER_TOTALS')
    expect(insert.indirect.map((i) => i.role)).toEqual(expect.arrayContaining(['JOIN', 'GROUP']))
  })
})

describe('lineage Mermaid', () => {
  it('exports owners, not columns', () => {
    const t = traceLineage(index, nodeId(index, 'REPORT.CUSTOMER_TOTALS', 'customer_id'), { direction: 'upstream', indirect: true })
    const text = lineageToMermaid(index, t)
    expect(text.startsWith('flowchart LR')).toBe(true)
    expect(text).toContain('shop.orders')
    expect(text).not.toContain('customer_id')
    expect(text).toMatch(/-\.->\|"(join|filter|group)/)
  })
})

describe('lineage view model', async () => {
  const { buildLineageView, statementTrace } = await import('./lineageView')
  const { transformLabel } = await import('./lineage')

  it('builds cards only for owners on the traced path', () => {
    const target = nodeId(index, 'REPORT.CUSTOMER_TOTALS', 'customer_id')
    const t = traceLineage(index, target, { direction: 'upstream', indirect: false })
    const model = buildLineageView(index, t, transformLabel)
    const owners = model.cards.map((c) => c.ownerKey)
    expect(owners).toContain('SHOP.ORDERS')
    expect(owners).not.toContain('SHOP.CUSTOMERS')
    expect(model.edges.filter((e) => e.traced)).toHaveLength(t.edges.size)
    for (const c of model.cards) expect(c.rows.some((r) => r.traced)).toBe(true)
  })

  it('shows a single statement during playback', () => {
    const t = statementTrace(index, 0)
    expect([...t.edges].every((e) => index.edges[e]!.statement === 0)).toBe(true)
  })
})

describe('readableViewport', () => {
  it('anchors the focused card where the path flows toward', async () => {
    const { readableViewport } = await import('./lineageView')
    const pane = { width: 600, height: 200 }
    const card = { x: 1000, y: 40, width: 250, height: 100 }
    const up = readableViewport(pane, card, 0.8, 'upstream')
    // Right edge of the card sits 24px from the pane's right edge; vertically centred.
    expect(up.x + (card.x + card.width) * 0.8).toBeCloseTo(576)
    expect(up.y + (card.y + card.height / 2) * 0.8).toBeCloseTo(100)
    const down = readableViewport(pane, card, 0.8, 'downstream')
    expect(down.x + card.x * 0.8).toBeCloseTo(24)
    const both = readableViewport(pane, card, 0.8, 'both')
    expect(both.x + (card.x + card.width / 2) * 0.8).toBeCloseTo(300)
    expect(both.zoom).toBe(0.8)
  })
})
