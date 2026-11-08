import { describe, expect, it } from 'vitest'
import type { AnalysisResult } from '@shared/protocol'
import procJson from './__fixtures__/via-proc.json'
import { indexAnalysis } from './model'
import { eventLabel, onlyViaCalls, splitRefs, viaBadgeText, viaSummary } from './via'

const analysis = procJson as unknown as AnalysisResult
const index = indexAnalysis(analysis)

describe('call linking (via)', () => {
  it('keeps direct refs apart from objects reached through a call', () => {
    const call = index.byId.get(8)! // EXEC dw.p_load inside dw.p_run_all
    const { direct, via } = splitRefs(call.refs)
    expect(direct.map((r) => [r.name, r.access])).toEqual([['dw.p_load', 'EXECUTE']])
    expect(via).toHaveLength(1)
    expect(via[0]!.routine).toBe('dw.p_load')
    expect(via[0]!.refs.map((r) => `${r.access} ${r.name}`)).toEqual(['READ src.orders', 'INSERT dw.daily', 'DELETE dw.stage'])
  })

  it('summarizes what each call writes and reads, through nested calls too', () => {
    expect(viaSummary(index.byId.get(8)!)).toEqual([{ routine: 'dw.p_load', writes: 2, reads: 1 }])
    // The top-level EXEC dw.p_run_all reaches dw.p_load's tables through dw.p_run_all.
    expect(viaSummary(index.byId.get(10)!)).toEqual([{ routine: 'dw.p_run_all', writes: 3, reads: 1 }])
    expect(viaSummary(index.byId.get(4)!)).toEqual([])
  })

  it('words badges and lifecycle tooltips', () => {
    expect(viaBadgeText({ routine: 'dw.p_load', writes: 2, reads: 0 })).toBe('writes 2 tables via dw.p_load')
    expect(viaBadgeText({ routine: 'dw.p_load', writes: 0, reads: 1 })).toBe('reads 1 table via dw.p_load')
    expect(viaBadgeText({ routine: 'dw.p_load', writes: 2, reads: 1 })).toBe('writes 2 and reads 1 table via dw.p_load')
    const daily = index.lifecycleByKey.get('DW.DAILY')!
    const viaEvent = daily.events.find((e) => e.via)!
    expect(eventLabel(viaEvent)).toBe('INSERT via dw.p_load, line 14')
    expect(eventLabel(daily.events.find((e) => !e.via)!)).toMatch(/^INSERT, line 7$/)
    expect(onlyViaCalls(daily.events.filter((e) => e.step === 8))).toBe(true)
    expect(onlyViaCalls(daily.events.filter((e) => e.step === 4))).toBe(false)
    expect(onlyViaCalls([])).toBe(false)
  })
})

describe('data flow edges through calls', () => {
  it('labels edges that exist only through a call, not direct ones', async () => {
    const { buildFlowGraph, buildFlowView, edgeId, objectNodeId, statementNodeId } = await import('./dataflow')
    const graph = buildFlowGraph(index)
    expect(graph.edgeVia.get(edgeId(statementNodeId(8), objectNodeId('DW.DAILY')))).toBe('dw.p_load')
    expect(graph.edgeVia.get(edgeId(objectNodeId('SRC.ORDERS'), statementNodeId(10)))).toBe('dw.p_run_all')
    // The INSERT inside dw.p_load names dw.daily itself.
    expect(graph.edgeVia.has(edgeId(statementNodeId(4), objectNodeId('DW.DAILY')))).toBe(false)
    const view = buildFlowView(index, graph)
    const viaEdges = view.edges.filter((e) => e.via)
    expect(viaEdges.length).toBeGreaterThan(0)
    expect(view.edges.filter((e) => !e.via).length).toBeGreaterThan(0)
  })
})

describe('variables and parameters in lineage', () => {
  it('indexes each variable as an owner of its own, with readable titles', async () => {
    const { indexLineage, ownerTitle, ownerSubtitle, columnText, scopeLabel, pickableColumns, traceLineage, lineageToMermaid } = await import('./lineage')
    const lin = indexLineage(analysis.lineage!)
    const vMax = [...lin.owners.values()].find((o) => o.kind === 'VARIABLE')!
    const pDay = [...lin.owners.values()].find((o) => o.kind === 'PARAMETER')!
    expect(ownerTitle(vMax)).toBe('@v_max')
    expect(ownerSubtitle(vMax)).toBe('variable in dw.p_load')
    expect(ownerSubtitle(pDay)).toBe('parameter of dw.p_load')
    expect(scopeLabel('block@L25')).toBe('block (line 25)')
    expect(scopeLabel('session')).toBe('session')
    const node = lin.nodes.get(1)!
    expect(columnText(node, 'VARIABLE')).toBe('@v_max')
    expect(pickableColumns(lin).some((c) => c.column === '@p_day' && c.ownerKind === 'PARAMETER')).toBe(true)

    // dw.daily.max_id comes from @v_max, which comes from MAX(src.orders.id): the trace runs through the variable.
    const maxId = [...lin.nodes.values()].find((n) => n.owner === 'dw.daily' && n.column === 'max_id')!
    const up = traceLineage(lin, maxId.id, { direction: 'upstream', indirect: false })
    const owners = [...up.nodes].map((id) => lin.nodes.get(id)!.ownerKind)
    expect(owners).toContain('VARIABLE')
    expect(owners).toContain('TABLE')
    // Downstream from the parameter reaches dw.daily.load_day.
    const down = traceLineage(lin, 4, { direction: 'downstream', indirect: false })
    expect([...down.nodes].some((id) => lin.nodes.get(id)!.column === 'load_day')).toBe(true)
    // Mermaid draws variables as hexagons.
    expect(lineageToMermaid(lin, up)).toMatch(/\{\{"@v_max<br\/>variable in dw\.p_load"\}\}/)
  })

  it('lets a card title name the variable and its row read "value"', async () => {
    const { indexLineage, traceLineage, transformLabel } = await import('./lineage')
    const { buildLineageView } = await import('./lineageView')
    const lin = indexLineage(analysis.lineage!)
    const model = buildLineageView(lin, traceLineage(lin, 1, { direction: 'upstream', indirect: false }), transformLabel)
    const card = model.cards.find((c) => c.kind === 'VARIABLE')!
    expect(card.title).toBe('@v_max')
    expect(card.subtitle).toBe('variable in dw.p_load')
    expect(card.rows.find((r) => !r.rows)?.label).toBe('value')
  })
})
