import { describe, expect, it } from 'vitest'
import type { AnalysisResult } from '@shared/protocol'
import structureJson from './__fixtures__/structure.json'
import { ancestorIds, blockProgress, boxProgress, compactOrder, hasBlockIds, moveCursor, nodesByBlockId, phaseBadges, stepLabel, WRITE_BLOCK } from './logicalOrder'
import { buildStructureTree } from './structure'

const analysis = structureJson as unknown as AnalysisResult
const insert = analysis.statements[0]!
const update = analysis.statements[1]!
const steps = insert.executionOrder!

describe('logical order from the engine', () => {
  it('numbers steps from 1 and ends DML with WRITE', () => {
    expect(steps.map((s) => s.step)).toEqual([...Array(steps.length).keys()].map((i) => i + 1))
    expect(steps.at(-1)).toMatchObject({ phase: 'WRITE', block: WRITE_BLOCK, detail: 'Insert into report.top_customer' })
    expect(analysis.statements[2]!.executionOrder).toEqual([])
  })

  it('maps step numbers to the phases of each box', () => {
    const badges = phaseBadges(steps)
    expect(badges.get(0)).toEqual([
      { phase: 'FROM', step: 1 },
      { phase: 'WHERE', step: 2 },
      { phase: 'SELECT', step: 3 },
      { phase: 'CTE', step: 4 }
    ])
    // The outer query waits for its derived table and subqueries between its phases.
    expect(badges.get(3)).toEqual([
      { phase: 'FROM', step: 13 },
      { phase: 'WHERE', step: 19 },
      { phase: 'SELECT', step: 26 }
    ])
    expect(badges.get(2)).toEqual([{ phase: 'UNION ALL', step: 30 }])
    expect(badges.get(WRITE_BLOCK)).toEqual([{ phase: 'WRITE', step: 31 }])
    expect(phaseBadges(undefined).size).toBe(0)
  })

  it('finds boxes by block id and their ancestors', () => {
    const tree = buildStructureTree(insert.structure)
    expect(hasBlockIds(insert.structure)).toBe(true)
    const byId = nodesByBlockId(tree)
    expect(byId.size).toBe(10)
    expect(byId.get(6)!.block.name).toBe('r')
    expect(byId.get(6)!.id).toBe('2.0.2')
    expect(ancestorIds(tree, '2.0.2')).toEqual(['2', '2.0'])
    expect(ancestorIds(tree, '0')).toEqual([])
    expect(hasBlockIds([])).toBe(false)
  })
})

describe('stepping', () => {
  it('moves inside the list', () => {
    expect(moveCursor(null, 1, 5)).toBe(0)
    expect(moveCursor(null, -1, 5)).toBe(4)
    expect(moveCursor(0, -1, 5)).toBe(0)
    expect(moveCursor(3, 1, 5)).toBe(4)
    expect(moveCursor(4, 1, 5)).toBe(4)
    expect(moveCursor(2, 1, 0)).toBeNull()
  })

  it('tracks which boxes are done, current, started or still to come', () => {
    expect(blockProgress(steps, null).size).toBe(0)
    // Step 14 (index 13): FROM large_c inside the WHERE IN subquery (block 7).
    const p = blockProgress(steps, 13)
    expect(p.get(7)).toBe('current')
    expect(p.get(0)).toBe('done') // CTE paid
    expect(p.get(6)).toBe('done') // derived table r
    expect(p.get(3)).toBe('active') // outer query: FROM done, WHERE and SELECT to come
    expect(p.get(8)).toBe('todo')
    expect(p.get(2)).toBe('todo') // UNION ALL comes after both branches
    const end = blockProgress(steps, steps.length - 1)
    expect(end.get(WRITE_BLOCK)).toBe('current')
    expect([...end.entries()].filter(([b]) => b !== WRITE_BLOCK).every(([, v]) => v === 'done')).toBe(true)
  })
})

describe('box progress', () => {
  it('never leaves a started box inside a dimmed one', () => {
    const tree = buildStructureTree(insert.structure)
    // Step 10 (index 9): FROM crm.region in derived table r (block 6), before the outer FROM.
    const raw = blockProgress(steps, 9)
    expect(raw.get(3)).toBe('todo')
    expect(raw.has(2)).toBe(true)
    const shown = boxProgress(tree, raw)
    expect(shown.get(6)).toBe('current')
    expect(shown.get(3)).toBe('active') // outer query of the first branch
    expect(shown.get(2)).toBe('active') // UNION ALL around it
    expect(shown.get(9)).toBe('todo') // second branch
    expect(boxProgress(tree, new Map()).size).toBe(0)
  })
})

describe('labels', () => {
  it('words steps and the compact outline', () => {
    expect(stepLabel(steps[0]!)).toBe('FROM sales.orders')
    expect(stepLabel(steps[1]!)).toBe('WHERE')
    expect(stepLabel(steps[3]!)).toBe('CTE paid is ready')
    expect(compactOrder(update.executionOrder).map((c) => c.label)).toEqual(['FROM', 'SELECT', 'FROM', 'SELECT', 'Update sales.orders'])
    const c = compactOrder(steps)
    expect(c[0]).toEqual({ label: 'FROM', step: 1, nested: 0 })
    expect(c.at(-1)).toEqual({ label: 'Insert into report.top_customer', step: 31, nested: 0 })
    expect(compactOrder([])).toEqual([])
  })
})

describe('exports with step numbers', () => {
  it('adds steps to Mermaid nodes and SVG box lines', async () => {
    const { layoutStructure, structureToMermaid } = await import('./structure')
    const tree = buildStructureTree(insert.structure)
    const badges = phaseBadges(steps)
    const mmd = structureToMermaid(insert, tree, undefined, badges)
    expect(mmd).toContain('b0_d["sales.orders · WHERE · customer_id, order_id, amount<br/>steps 1 FROM, 2 WHERE, 3 SELECT, 4 ready"]')
    expect(mmd).toContain('root_w["step 31 WRITE"]')
    const layout = layoutStructure(insert, tree, new Set(), 760, badges)
    expect(layout.boxes[0]!.lines[0]!.text).toBe('step 31 WRITE')
    const union = layout.boxes.find((b) => b.node?.block.kind === 'SET')!
    expect(union.lines.map((l) => l.text)).toContain('step 30 UNION ALL')
  })
})
