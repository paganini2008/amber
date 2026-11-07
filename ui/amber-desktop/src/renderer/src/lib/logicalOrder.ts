import type { LogicalStep, QueryBlock } from '@shared/protocol'
import { flattenStructure, type StructureNode } from './structure'

/**
 * The logical processing order of a statement (`Statement.executionOrder`): the order the SQL
 * standard defines for reading a query. It is not the database's execution plan; nothing runs.
 */

export const LOGICAL_ORDER_NOTE = 'The order the SQL standard defines for reading the query. Nothing is run, and the database’s actual plan may differ.'

/** The WRITE step of DML belongs to no block. */
export const WRITE_BLOCK = -1

export interface PhaseBadge {
  phase: string
  step: number
}

/** Step numbers per block id, in step order, for the badges on each box. */
export function phaseBadges(steps: LogicalStep[] | undefined): Map<number, PhaseBadge[]> {
  const out = new Map<number, PhaseBadge[]>()
  for (const s of steps ?? []) {
    const list = out.get(s.block) ?? []
    list.push({ phase: s.phase, step: s.step })
    out.set(s.block, list)
  }
  return out
}

/** Structure nodes by engine block id (`QueryBlock.id`). */
export function nodesByBlockId(tree: StructureNode[]): Map<number, StructureNode> {
  const out = new Map<number, StructureNode>()
  for (const n of flattenStructure(tree)) if (typeof n.block.id === 'number') out.set(n.block.id, n)
  return out
}

/** Path ids of the ancestors of a node, nearest last; used to expand collapsed boxes. */
export function ancestorIds(tree: StructureNode[], nodeId: string): string[] {
  const byId = new Map(flattenStructure(tree).map((n) => [n.id, n]))
  const out: string[] = []
  for (let p = byId.get(nodeId)?.parentId ?? null; p !== null; p = byId.get(p)?.parentId ?? null) out.unshift(p)
  return out
}

/** Moves a cursor (an index into the steps) by delta, staying inside the list. */
export function moveCursor(cursor: number | null, delta: number, count: number): number | null {
  if (count === 0) return null
  if (cursor === null) return delta >= 0 ? 0 : count - 1
  return Math.max(0, Math.min(count - 1, cursor + delta))
}

export type BlockProgress = 'todo' | 'current' | 'active' | 'done'

/**
 * Where each block stands at a cursor: `current` owns the current step, `active` has started
 * (some steps done, some to come, e.g. a query waiting for its subqueries), `done` has no steps
 * left, `todo` has not started. Blocks without steps follow their children: a SET box is done
 * when its own step (the set operator) is.
 */
export function blockProgress(steps: LogicalStep[], cursor: number | null): Map<number, BlockProgress> {
  const out = new Map<number, BlockProgress>()
  if (cursor === null) return out
  const first = new Map<number, number>()
  const last = new Map<number, number>()
  steps.forEach((s, i) => {
    if (!first.has(s.block)) first.set(s.block, i)
    last.set(s.block, i)
  })
  const current = steps[cursor]?.block
  for (const [block, f] of first) {
    const l = last.get(block)!
    out.set(block, block === current ? 'current' : l < cursor ? 'done' : f < cursor ? 'active' : 'todo')
  }
  return out
}

/** Text of a step for lists: the detail when it says more than the phase. */
export function stepLabel(step: LogicalStep): string {
  if (step.phase === 'WRITE') return step.detail ?? 'Write'
  if (step.phase === 'CTE') return step.detail ? `CTE ${step.detail}` : 'CTE ready'
  return step.detail ? `${step.phase} ${step.detail}` : step.phase
}

/**
 * The outline of the order for a compact list: the steps at the shallowest depth (the
 * statement's own query, its CTEs, set operations and the final write), with how many nested
 * steps run in between.
 */
export function compactOrder(steps: LogicalStep[] | undefined): { label: string; step: number; nested: number }[] {
  if (!steps?.length) return []
  const min = Math.min(...steps.map((s) => s.depth))
  const out: { label: string; step: number; nested: number }[] = []
  let nested = 0
  for (const s of steps) {
    if (s.depth > min) {
      nested += 1
      continue
    }
    out.push({ label: s.phase === 'CTE' || s.phase === 'WRITE' ? stepLabel(s) : s.phase, step: s.step, nested })
    nested = 0
  }
  return out
}

/** Whether the structure carries block ids, so steps can be matched to boxes. */
export function hasBlockIds(blocks: QueryBlock[] | undefined): boolean {
  return Boolean(blocks?.length) && typeof blocks![0]!.id === 'number'
}

/**
 * Progress as the boxes show it: a box whose nested boxes have started is itself started
 * (`active`), so a current box is never inside a dimmed one. Blocks without steps (a SET before
 * its operator step) follow their children the same way.
 */
export function boxProgress(tree: StructureNode[], progress: Map<number, BlockProgress>): Map<number, BlockProgress> {
  const out = new Map(progress)
  if (progress.size === 0) return out
  const walk = (node: StructureNode): boolean => {
    let started = false
    for (const c of node.children) if (walk(c)) started = true
    const id = node.block.id
    if (typeof id !== 'number') return started
    const own = out.get(id)
    if (started && (own === undefined || own === 'todo')) out.set(id, 'active')
    return started || (own !== undefined && own !== 'todo')
  }
  tree.forEach(walk)
  return out
}
