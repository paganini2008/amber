import { describe, expect, it } from 'vitest'
import type { ErModel } from '@shared/protocol'
import fixture from './__fixtures__/er-oracle.json'
import { bySchema, cardModel, DEFAULT_FILTERS, dashFor, ends, filterEr, orderedColumns, relationshipKey, relationshipsOf, schemaOf, searchEntities, upgradedRelationships } from './er'

// Real `er.model` output for a small Oracle script: declared FKs, joins, a name match, an unused table.
const model = fixture.model as ErModel
const withFlow = fixture.withDataFlow as ErModel

describe('ER filters', () => {
  it('show every relationship by default and collapse tables without one', () => {
    const v = filterEr(model, DEFAULT_FILTERS)
    expect(v.relationships).toHaveLength(model.relationships.length)
    expect(v.isolated.map((e) => e.key)).toEqual(['AUDIT.EVENTS'])
    expect(v.entities.length + v.isolated.length).toBe(model.entities.length)
  })

  it('filter by evidence and minimum confidence', () => {
    const joins = filterEr(model, { ...DEFAULT_FILTERS, evidence: new Set(['JOIN']) })
    expect(joins.relationships.every((r) => r.evidence === 'JOIN')).toBe(true)
    const certain = filterEr(model, { ...DEFAULT_FILTERS, minConfidence: 'CERTAIN' })
    expect(certain.relationships.map((r) => r.evidence)).toEqual(['DECLARED_FK', 'DECLARED_FK'])
    const inferred = filterEr(model, { ...DEFAULT_FILTERS, minConfidence: 'INFERRED' })
    expect(inferred.relationships.some((r) => r.confidence === 'POSSIBLE')).toBe(false)
    expect(inferred.relationships.length).toBeGreaterThan(certain.relationships.length)
  })

  it('show only the tables around one, by steps', () => {
    const one = filterEr(model, { ...DEFAULT_FILTERS, around: { entity: 'SALES.REGIONS', hops: 1 } })
    expect(one.entities.map((e) => e.key).sort()).toEqual(['SALES.CUSTOMERS', 'SALES.REGIONS'])
    const two = filterEr(model, { ...DEFAULT_FILTERS, around: { entity: 'SALES.REGIONS', hops: 2 } })
    expect(two.entities.map((e) => e.key)).toContain('SALES.ORDERS')
    expect(two.isolated).toEqual([])
    expect(two.relationships.every((r) => two.entities.some((e) => e.key === r.from.entity) && two.entities.some((e) => e.key === r.to.entity))).toBe(true)
  })

  it('list relationships of a table and find tables by name', () => {
    expect(relationshipsOf(model.relationships, 'SALES.CUSTOMERS').length).toBeGreaterThanOrEqual(2)
    expect(searchEntities(model.entities, 'sales ord').map((e) => e.name)).toEqual(['sales.orders', 'sales.order_lines'])
    expect(searchEntities(model.entities, '  ')).toEqual([])
  })
})

describe('ER cards and lines', () => {
  it('put key columns first and size the card to what it shows', () => {
    const lines = model.entities.find((e) => e.key === 'SALES.ORDER_LINES')!
    const { keys, rest } = orderedColumns(lines)
    expect(keys.map((c) => c.name)).toEqual(['order_id', 'line_no', 'product_id'])
    expect(rest.map((c) => c.name)).toEqual(['qty'])
    const collapsed = cardModel(lines, false)
    const expanded = cardModel(lines, true)
    expect(collapsed.hidden).toBe(1)
    expect(expanded.rows).toHaveLength(4)
    expect(expanded.height).toBeGreaterThan(collapsed.height)
  })

  it('draw crow\'s feet many → one, line style by confidence, arrows for data flow', () => {
    expect(ends({ cardinality: 'MANY_TO_ONE', evidence: 'JOIN' })).toEqual({ from: 'many', to: 'one' })
    expect(ends({ cardinality: 'ONE_TO_ONE', evidence: 'JOIN' })).toEqual({ from: 'one', to: 'one' })
    const flow = withFlow.relationships.find((r) => r.evidence === 'DATA_FLOW')!
    expect(ends(flow).to).toBe('arrow')
    expect(dashFor('CERTAIN')).toBeUndefined()
    expect(dashFor('INFERRED')).not.toEqual(dashFor('POSSIBLE'))
  })

  it('group tables by schema', () => {
    expect(schemaOf('sales.orders')).toBe('sales')
    expect(schemaOf('orders')).toBe('')
    const names = bySchema(model.entities).map((e) => schemaOf(e.name))
    expect(names).toEqual([...names].sort())
  })
})

describe('online upgrades', () => {
  it('count relationships whose confidence rose, matched by tables and columns', () => {
    const before = model
    const after: ErModel = {
      ...model,
      relationships: model.relationships.map((r, i) => ({ ...r, id: `X${i}`, confidence: r.evidence === 'JOIN' ? 'CERTAIN' : r.confidence, evidence: r.evidence === 'JOIN' ? 'METADATA_FK' : r.evidence }))
    }
    const upgraded = upgradedRelationships(before, after)
    expect(upgraded.length).toBe(model.relationships.filter((r) => r.evidence === 'JOIN').length)
    expect(upgradedRelationships(model, model)).toEqual([])
    expect(relationshipKey(model.relationships[0]!)).toBe(relationshipKey({ ...model.relationships[0]!, from: { ...model.relationships[0]!.from, columns: model.relationships[0]!.from.columns.map((c) => c.toUpperCase()) } }))
  })
})

describe('ER export', () => {
  it('draws every card and a line per relationship', async () => {
    const { erToSvg } = await import('./exportSvg')
    const { THEMES } = await import('@shared/themes')
    const v = filterEr(model, DEFAULT_FILTERS)
    const cards = v.entities.map((e) => cardModel(e, false))
    const positions = new Map(cards.map((c, i) => [c.entity.key, { x: (i % 3) * 320, y: Math.floor(i / 3) * 200 }]))
    const svg = erToSvg(cards, v.relationships, { positions, width: 960, height: 600 }, THEMES['amber-light'].tokens, 'er')
    for (const c of cards) expect(svg).toContain(c.entity.name)
    expect(svg.match(/stroke-width="1.4"/g)!.length).toBe(v.relationships.length * 2)
    expect(svg).toContain('stroke-dasharray')
    expect(svg.startsWith('<svg')).toBe(true)
  })
})

describe('large graph layout', () => {
  it('falls back to a linear layered layout above the ELK limits', async () => {
    const { simpleLayout, ELK_MAX_NODES } = await import('./layout')
    const nodes = Array.from({ length: ELK_MAX_NODES + 100 }, (_, i) => ({ id: `n${i}`, width: 200, height: 60 }))
    const edges = nodes.slice(1).map((n, i) => ({ id: `e${i}`, source: `n${i % 50}`, target: n.id }))
    // A cycle must not hang the layering.
    edges.push({ id: 'cycle', source: 'n60', target: 'n1' })
    const started = performance.now()
    const r = simpleLayout({ nodes, edges })
    expect(performance.now() - started).toBeLessThan(1000)
    expect(r.positions.size).toBe(nodes.length)
    expect(r.positions.get('n0')!.x).toBe(0)
    expect(r.positions.get('n60')!.x).toBeGreaterThan(0)
    expect(r.width).toBeGreaterThan(0)
  })
})
