import { describe, expect, it } from 'vitest'
import type { AnalysisResult, Statement } from '@shared/protocol'
import nightlyJson from './__fixtures__/nightly.json'
import inventoryJson from './__fixtures__/inventory.json'
import { buildFlowGraph, buildFlowView, edgeId, objectNodeId, statementNodeId, traceObject, traceStatement, GROUP_THRESHOLD } from './dataflow'
import { buildMatrix, cellKey } from './lifecycle'
import { toMermaid } from './mermaid'
import { indexAnalysis, statementAtOffset } from './model'
import { buildPlan, playedSet } from './playback'

const nightly = nightlyJson as unknown as AnalysisResult
const inventory = inventoryJson as unknown as AnalysisResult

describe('indexAnalysis', () => {
  it('orders statements by step and finds the innermost statement at an offset', () => {
    const index = indexAnalysis(inventory)
    expect(index.order.map((s) => s.id)).toEqual([...Array(inventory.stepCount).keys()])
    const update = index.byId.get(13)!
    const mid = Math.floor((update.range.start.offset + update.range.end.offset) / 2)
    expect(statementAtOffset(index, mid)).toBe(13)
    expect(index.parentOf.get(13)).toBe(12)
    expect(statementAtOffset(index, inventory.statements[0]!.range.start.offset)).toBe(0)
  })
})

describe('data flow', () => {
  const index = indexAnalysis(nightly)
  const graph = buildFlowGraph(index)

  it('links sources to statements to targets and leaves out CTEs', () => {
    expect(graph.objects).toContain('SHOP.ORDERS')
    expect(graph.objects).not.toContain('spend')
    // Step 1 creates tmp_orders from shop.orders (CTAS counts as a data write)
    expect([...graph.readsOf.get(1)!]).toEqual(['SHOP.ORDERS'])
    expect([...graph.writesOf.get(1)!]).toEqual(['TMP_ORDERS'])
    // Plain CREATE TABLE, CREATE INDEX and DROP carry no data
    expect(graph.statements).not.toContain(3)
    expect(graph.statements).not.toContain(12)
  })

  it('traces a statement upstream and downstream in step order', () => {
    const trace = traceStatement(graph, 7) // insert into report.daily_revenue from tmp_orders, tmp_customer_tier
    expect(trace.objects).toEqual(new Set(['TMP_ORDERS', 'TMP_CUSTOMER_TIER', 'SHOP.ORDERS', 'REPORT.DAILY_REVENUE']))
    expect(trace.statements.has(1)).toBe(true) // created tmp_orders
    expect(trace.statements.has(4)).toBe(true) // filled tmp_customer_tier
    expect(trace.statements.has(8)).toBe(false) // merge is a sibling consumer, not on the path
    expect(trace.edges.has(edgeId(statementNodeId(7), objectNodeId('REPORT.DAILY_REVENUE')))).toBe(true)
  })

  it('traces an object to all its writers and readers', () => {
    const trace = traceObject(graph, 'TMP_CUSTOMER_TIER')
    expect([...trace.statements].sort((a, b) => a - b)).toEqual([4, 5, 7, 8, 9, 10])
    expect(trace.objects.has('REPORT.CUSTOMER_SNAPSHOT')).toBe(true)
  })

  it('groups statements above the node threshold', () => {
    const statements: Statement[] = []
    const lifecycles = []
    for (let i = 0; i < 400; i++) {
      const range = { start: { offset: i * 10, line: i + 1, column: 1 }, end: { offset: i * 10 + 9, line: i + 1, column: 10 } }
      statements.push({
        id: i, kind: 'INSERT', category: 'DML', container: false, confidence: 'CERTAIN', name: null,
        summary: '', range, ctes: [], children: [],
        refs: [
          { name: 'src', key: 'src', kind: 'TABLE', access: 'READ', confidence: 'CERTAIN', range },
          { name: `t${i}`, key: `t${i}`, kind: 'TABLE', access: 'INSERT', confidence: 'CERTAIN', range }
        ]
      })
      lifecycles.push({ name: `t${i}`, key: `t${i}`, kind: 'TABLE', role: 'TARGET', createdInScript: false, droppedInScript: false, events: [{ step: i, access: 'INSERT', range }] })
    }
    lifecycles.push({ name: 'src', key: 'src', kind: 'TABLE', role: 'SOURCE', createdInScript: false, droppedInScript: false, events: [] })
    const big = indexAnalysis({ status: 'SUCCESS', dialect: 'h2', name: 'big.sql', lineCount: 400, elapsedMs: 1, diagnostics: [], statements, lifecycles, stepCount: 400 })
    const g = buildFlowGraph(big)
    const view = buildFlowView(big, g)
    expect(view.grouped).toBe(true)
    expect(view.nodes.filter((n) => n.type === 'statement')).toHaveLength(0)
    expect(view.nodes.filter((n) => n.type === 'group')).toHaveLength(16)
    expect(view.nodes.length).toBeLessThan(400 + 401)
    const expanded = buildFlowView(big, g, new Set(['r0']))
    expect(expanded.nodes.filter((n) => n.type === 'statement')).toHaveLength(25)
    expect(GROUP_THRESHOLD).toBe(300)
  })

  it('folds tables used only inside one group into it', () => {
    const statements: Statement[] = []
    const lifecycles = []
    for (let i = 0; i < 400; i++) {
      const range = { start: { offset: i * 10, line: i + 1, column: 1 }, end: { offset: i * 10 + 9, line: i + 1, column: 10 } }
      const refs = [{ name: `t${i}`, key: `T${i}`, kind: 'TEMP_TABLE', access: 'CREATE', confidence: 'CERTAIN', range }]
      refs.push({ name: i === 0 ? 'src' : `t${i - 1}`, key: i === 0 ? 'SRC' : `T${i - 1}`, kind: 'TABLE', access: 'READ', confidence: 'CERTAIN', range })
      statements.push({ id: i, kind: 'CREATE_TABLE_AS', category: 'DDL', container: false, confidence: 'CERTAIN', name: null, summary: '', range, ctes: [], children: [], refs })
      lifecycles.push({ name: `t${i}`, key: `T${i}`, kind: 'TEMP_TABLE', role: 'INTERMEDIATE', createdInScript: true, droppedInScript: false, events: [] })
    }
    lifecycles.push({ name: 'src', key: 'SRC', kind: 'TABLE', role: 'SOURCE', createdInScript: false, droppedInScript: false, events: [] })
    const chain = indexAnalysis({ status: 'SUCCESS', dialect: 'h2', name: 'chain.sql', lineCount: 400, elapsedMs: 1, diagnostics: [], statements, lifecycles, stepCount: 400 })
    const view = buildFlowView(chain, buildFlowGraph(chain))
    expect(view.grouped).toBe(true)
    expect(view.nodes.length).toBeLessThan(60)
    expect(view.folded.size).toBeGreaterThan(300)
  })
})

describe('lifecycle matrix', () => {
  const index = indexAnalysis(nightly)

  it('groups rows by role and keeps only steps with access', () => {
    const m = buildMatrix(index, { sort: 'role', filter: '', allSteps: false })
    expect(m.rows[0]).toMatchObject({ type: 'header', label: 'Sources' })
    expect(m.columns).not.toContain(0) // CREATE SCHEMA touches no data object
    expect(m.cells.get(cellKey('TMP_ORDERS', 12))).toEqual(['DROP'])
    const all = buildMatrix(index, { sort: 'name', filter: 'tmp', allSteps: true })
    expect(all.rows.every((r) => r.type === 'object' && r.label.startsWith('tmp'))).toBe(true)
    expect(all.columns).toHaveLength(nightly.stepCount)
  })
})

describe('playback plan', () => {
  const statements = inventory.statements

  it('plays every child in order by default and marks loops', () => {
    const plan = buildPlan(statements, new Map())
    expect(plan.map((p) => p.id)).toEqual([...Array(inventory.stepCount).keys()])
    expect(plan.find((p) => p.id === 11)!.loop).toBe(true)
    expect(plan.find((p) => p.id === 13)!.inLoop).toBe(true)
    expect(plan.find((p) => p.id === 5)!.branch).toBe(true)
  })

  it('skips the children of an IF when asked', () => {
    const plan = buildPlan(statements, new Map([[5, 'skip']]))
    expect(plan.map((p) => p.id)).not.toContain(7)
    expect(plan.map((p) => p.id)).toContain(5)
    expect(playedSet(plan, 2)).toEqual(new Set([0, 1, 2]))
  })

  it('plays a single branch once the engine labels them', () => {
    const range = nightly.statements[0]!.range
    const leaf = (id: number, branch?: string): Statement => ({
      id, kind: 'UPDATE', category: 'DML', container: false, confidence: 'CERTAIN', name: null, summary: '', range, ctes: [], refs: [], children: [], branch
    })
    const ifStatement: Statement = { ...leaf(0), kind: 'IF', container: true, children: [leaf(1, 'THEN'), leaf(2, 'ELSE')] }
    expect(buildPlan([ifStatement], new Map([[0, 'ELSE']])).map((p) => p.id)).toEqual([0, 2])
  })
})

describe('mermaid export', () => {
  it('builds an object-level flowchart', () => {
    const index = indexAnalysis(nightly)
    const text = toMermaid(index, buildFlowGraph(index))
    expect(text.startsWith('flowchart LR\n')).toBe(true)
    expect(text).toContain('[("shop.orders")]:::source')
    expect(text).toMatch(/-->\|"INSERT"\|/)
    expect(text).not.toContain('spend')
  })
})

describe('analyzeParams', () => {
  it('lets the engine detect the encoding when the setting is auto', async () => {
    const { analyzeParams } = await import('../store')
    const doc = { token: 1, path: '/x/a.sql', name: 'a.sql', text: 'select 1', encoding: 'UTF-8', edited: false, modified: false }
    const settings = { encoding: 'auto' } as Parameters<typeof analyzeParams>[2]
    expect(analyzeParams(doc, 'oracle', settings, false)).toEqual({ dialect: 'oracle', path: '/x/a.sql', name: 'a.sql', allowFallback: false })
    expect(analyzeParams(doc, 'oracle', { encoding: 'GB18030' } as Parameters<typeof analyzeParams>[2], false).encoding).toBe('GB18030')
    expect(analyzeParams({ ...doc, modified: true }, 'oracle', settings, false)).toEqual({ dialect: 'oracle', text: 'select 1', name: 'a.sql', allowFallback: false })
  })
})

describe('engine answers over IPC', () => {
  it('arrive as JSON text and become a Result again; errors and plain results pass through', async () => {
    const { fromWire } = await import('./api')
    expect(fromWire<{ a: number }>({ ok: true, json: '{"a":1}' })).toEqual({ ok: true, value: { a: 1 } })
    const error = { ok: false as const, error: { code: -32601, message: 'x' } }
    expect(fromWire(error)).toBe(error)
    expect(fromWire({ ok: true, value: 2 })).toEqual({ ok: true, value: 2 })
  })
})
