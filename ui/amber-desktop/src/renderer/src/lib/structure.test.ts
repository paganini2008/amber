import { describe, expect, it } from 'vitest'
import type { AnalysisResult, QueryBlock, Statement } from '@shared/protocol'
import { THEMES } from '@shared/themes'
import structureJson from './__fixtures__/structure.json'
import { structureToSvg } from './exportSvg'
import {
  blockTitle,
  blocksAt,
  buildStructureTree,
  collapsibleIds,
  countStructure,
  describeCounts,
  escapeMermaid,
  flattenStructure,
  isDarkTokens,
  layoutStructure,
  resolveSourceBlock,
  sourceLabel,
  statementTitle,
  structurePalette,
  structureToMermaid
} from './structure'

const analysis = structureJson as unknown as AnalysisResult
const insert = analysis.statements[0]!
const update = analysis.statements[1]!
const create = analysis.statements[2]!

function block(kind: string, children: QueryBlock[] = [], extra: Partial<QueryBlock> = {}): QueryBlock {
  const range = { start: { offset: 0, line: 1, column: 1 }, end: { offset: 10, line: 1, column: 11 } }
  return { kind, name: null, role: null, range, sources: [], outputs: [], clauses: [], children, ...extra }
}

/** A chain of `depth` subqueries, each inside the previous one. */
function deep(depth: number): QueryBlock[] {
  let b = block('SUBQUERY', [], { role: 'WHERE IN', sources: [{ name: 't_last', kind: 'TABLE', alias: null }] })
  for (let i = 1; i < depth; i++) b = block(i % 3 === 0 ? 'DERIVED' : 'SUBQUERY', [b], { name: i % 3 === 0 ? `d${i}` : null, role: 'WHERE IN' })
  return [b]
}

describe('structure tree', () => {
  it('assigns path ids and flattens depth first in source order', () => {
    const tree = buildStructureTree(insert.structure)
    const flat = flattenStructure(tree)
    expect(flat.map((n) => n.id)).toEqual(['0', '1', '2', '2.0', '2.0.0', '2.0.1', '2.0.2', '2.0.3', '2.0.4', '2.1'])
    expect(flat.map((n) => blockTitle(n.block))).toEqual([
      'CTE paid',
      'CTE large_c',
      'UNION ALL',
      'QUERY',
      'SUBQUERY',
      'SUBQUERY',
      'DERIVED r',
      'SUBQUERY',
      'SUBQUERY',
      'QUERY'
    ])
    expect(flat.find((n) => n.id === '2.0.3')!.parentId).toBe('2.0')
    expect(flat.find((n) => n.id === '2.0.3')!.depth).toBe(2)
    expect(collapsibleIds(tree)).toEqual(['2', '2.0'])
  })

  it('handles statements without structure', () => {
    expect(buildStructureTree(create.structure)).toEqual([])
    expect(buildStructureTree(undefined)).toEqual([])
    expect(countStructure(undefined)).toEqual({ total: 0, byKind: {}, maxDepth: 0 })
    expect(describeCounts(countStructure([]))).toBe('No query structure')
  })

  it('counts blocks by kind and depth', () => {
    const c = countStructure(insert.structure)
    expect(c).toEqual({ total: 10, byKind: { CTE: 2, SET: 1, QUERY: 2, DERIVED: 1, SUBQUERY: 4 }, maxDepth: 3 })
    expect(describeCounts(c)).toBe('10 blocks · 2 CTEs, 1 set operation, 1 derived table, 4 subqueries, 2 queries · 3 levels')
    expect(describeCounts(countStructure(update.structure))).toBe('3 blocks · 1 derived table, 2 subqueries · 2 levels')
    const d = countStructure(deep(12))
    expect(d.total).toBe(12)
    expect(d.maxDepth).toBe(12)
  })

  it('labels sources and statements', () => {
    expect(sourceLabel({ name: 'crm.customer', kind: 'TABLE', alias: 'c' })).toBe('crm.customer c')
    expect(sourceLabel({ name: 'paid', kind: 'CTE', alias: null })).toBe('paid')
    expect(sourceLabel({ name: 'Paid', kind: 'CTE', alias: 'paid' })).toBe('Paid')
    expect(statementTitle(insert)).toBe('INSERT INTO report.top_customer')
    expect(statementTitle(update)).toBe('UPDATE sales.orders')
    const select = { ...insert, kind: 'SELECT', refs: insert.refs.filter((r) => r.access === 'READ') } as Statement
    expect(statementTitle(select)).toBe('SELECT')
  })

  it('resolves CTE and derived-table sources to their blocks', () => {
    const tree = buildStructureTree(insert.structure)
    const flat = flattenStructure(tree)
    const query = flat.find((n) => n.id === '2.0')!
    const scalar = flat.find((n) => n.id === '2.0.0')!
    expect(resolveSourceBlock(tree, query, { name: 'r', kind: 'SUBQUERY', alias: null })?.id).toBe('2.0.2')
    expect(resolveSourceBlock(tree, scalar, { name: 'paid', kind: 'CTE', alias: 'p' })?.id).toBe('0')
    expect(resolveSourceBlock(tree, null, { name: 'LARGE_C', kind: 'CTE', alias: null })?.id).toBe('1')
    expect(resolveSourceBlock(tree, query, { name: 'crm.customer', kind: 'TABLE', alias: 'c' })).toBeNull()
    expect(resolveSourceBlock(tree, query, { name: 'nope', kind: 'CTE', alias: null })).toBeNull()
  })

  it('finds the blocks around an offset, innermost last', () => {
    const tree = buildStructureTree(insert.structure)
    const inner = flattenStructure(tree).find((n) => n.id === '2.0.0')!
    const ids = blocksAt(tree, inner.block.range.start.offset + 1).map((n) => n.id)
    expect(ids).toEqual(['2', '2.0', '2.0.0'])
  })
})

describe('structure export', () => {
  it('writes nested Mermaid subgraphs with escaped labels', () => {
    const mmd = structureToMermaid(insert, buildStructureTree(insert.structure))
    const lines = mmd.trim().split('\n')
    expect(lines[0]).toBe('flowchart TB')
    expect(mmd).toContain('subgraph root["INSERT INTO report.top_customer (line 1)"]')
    expect(mmd).toContain('subgraph b0["CTE paid"]')
    expect(mmd).toContain('subgraph b2_0_3["SUBQUERY [WHERE IN]"]')
    expect(mmd).toContain('b0_d["sales.orders · WHERE · customer_id, order_id, amount"]')
    // Every subgraph is closed.
    expect(lines.filter((l) => l.trim().startsWith('subgraph')).length).toBe(lines.filter((l) => l.trim() === 'end').length)
    // Nesting shows in the indentation.
    expect(lines.find((l) => l.includes('subgraph b2_0_0'))!.indexOf('subgraph')).toBeGreaterThan(lines.find((l) => l.includes('subgraph b2_0['))!.indexOf('subgraph'))
  })

  it('escapes Mermaid label syntax', () => {
    expect(escapeMermaid('a "b" <c> #d\nx')).toBe('a #quot;b#quot; #lt;c#gt; #35;d x')
    const odd = { ...insert, structure: [block('CTE', [], { name: 'say "hi" <now>', sources: [{ name: '#tmp', kind: 'TEMP_TABLE', alias: null }] })] }
    const mmd = structureToMermaid(odd, buildStructureTree(odd.structure))
    expect(mmd).toContain('subgraph b0["CTE say #quot;hi#quot; #lt;now#gt;"]')
    expect(mmd).toContain('b0_d["#35;tmp"]')
  })

  it('adds kind styles when given a palette', () => {
    const palette = structurePalette(THEMES['amber-light'].tokens)
    const mmd = structureToMermaid(update, buildStructureTree(update.structure), palette)
    expect(mmd).toContain(`style b1_0 stroke:${palette.derived}`)
    expect(mmd).toContain(`style b0 stroke:${palette.subquery}`)
  })

  it('states when there is no structure', () => {
    expect(structureToMermaid(create, [])).toContain('root_d["no query structure"]')
  })

  it('lays out nested boxes inside their parents', () => {
    const tree = buildStructureTree(insert.structure)
    const layout = layoutStructure(insert, tree)
    expect(layout.boxes).toHaveLength(11)
    const byId = new Map(layout.boxes.filter((b) => b.node).map((b) => [b.node!.id, b]))
    for (const b of layout.boxes) {
      const parent = b.node?.parentId ? byId.get(b.node.parentId)! : b.node ? layout.boxes[0]! : null
      if (!parent) continue
      expect(b.x).toBeGreaterThan(parent.x)
      expect(b.y).toBeGreaterThan(parent.y)
      expect(b.x + b.width).toBeLessThan(parent.x + parent.width)
      expect(b.y + b.height).toBeLessThanOrEqual(parent.y + parent.height)
    }
    // Siblings do not overlap.
    expect(byId.get('1')!.y).toBeGreaterThanOrEqual(byId.get('0')!.y + byId.get('0')!.height)
  })

  it('hides collapsed children and keeps deep nesting wide enough', () => {
    const tree = buildStructureTree(insert.structure)
    const collapsed = layoutStructure(insert, tree, new Set(['2']))
    expect(collapsed.boxes).toHaveLength(4)
    expect(collapsed.boxes[3]!.lines.at(-1)!.text).toBe('7 nested blocks collapsed')
    expect(collapsed.height).toBeLessThan(layoutStructure(insert, tree).height)

    const d = { ...insert, structure: deep(14) }
    const layout = layoutStructure(d, buildStructureTree(d.structure))
    const innermost = layout.boxes.at(-1)!
    expect(innermost.node!.depth).toBe(13)
    expect(innermost.width).toBeGreaterThanOrEqual(240)
  })

  it('renders the layout as SVG in light and dark themes', () => {
    const tree = buildStructureTree(insert.structure)
    for (const theme of ['amber-light', 'amber-dark'] as const) {
      const t = THEMES[theme].tokens
      const svg = structureToSvg(layoutStructure(insert, tree), t, 'structure.sql · structure')
      expect(svg.startsWith('<svg')).toBe(true)
      expect(svg).toContain('CTE paid')
      expect(svg).toContain(structurePalette(t).cte)
      expect(svg).toContain('WHERE NOT EXISTS')
      // Background, one fill per box, and a solid accent bar for every box but the 4 subqueries (dashed line).
      expect((svg.match(/<rect /g) ?? []).length).toBe(1 + 11 + 7)
      expect((svg.match(/stroke-dasharray="6 3"/g) ?? []).length).toBe(4)
    }
    expect(isDarkTokens(THEMES['amber-dark'].tokens)).toBe(true)
    expect(isDarkTokens(THEMES['amber-light'].tokens)).toBe(false)
  })
})
