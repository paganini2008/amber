import type { Statement } from '@shared/protocol'

/**
 * Execution playback: a logical replay in source order. Nothing is executed.
 * Amber has no runtime values, so at IF/CASE containers the user decides what plays.
 */

export const BRANCH_KINDS = new Set(['IF', 'CASE'])
export const LOOP_KINDS = new Set(['LOOP'])

/** "all" plays every child in order, "skip" plays none, any other value plays that branch only. */
export type BranchDecision = 'all' | 'skip' | string

export interface PlanStep {
  id: number
  depth: number
  /** Inside a loop: played once, as one representative iteration. */
  inLoop: boolean
  /** This step is a loop container. */
  loop: boolean
  /** This step is an IF/CASE that needs or has a decision. */
  branch: boolean
}

/** Distinct `branch` labels on a container's children (empty until the engine sends them). */
export function branchOptions(statement: Statement): string[] {
  const out: string[] = []
  for (const c of statement.children) {
    if (c.branch && !out.includes(c.branch)) out.push(c.branch)
  }
  return out
}

export function buildPlan(statements: Statement[], decisions: ReadonlyMap<number, BranchDecision>): PlanStep[] {
  const plan: PlanStep[] = []
  const walk = (list: Statement[], depth: number, inLoop: boolean): void => {
    for (const s of list) {
      const loop = LOOP_KINDS.has(s.kind)
      const branch = BRANCH_KINDS.has(s.kind) && s.children.length > 0
      plan.push({ id: s.id, depth, inLoop, loop, branch })
      if (!s.children.length) continue
      let children = s.children
      if (branch) {
        const decision = decisions.get(s.id) ?? 'all'
        if (decision === 'skip') continue
        if (decision !== 'all') children = s.children.filter((c) => !c.branch || c.branch === decision)
      }
      walk(children, depth + 1, inLoop || loop)
    }
  }
  walk(statements, 0, false)
  return plan
}

export const SPEEDS = [0.5, 1, 2, 4] as const
export const BASE_INTERVAL_MS = 1400

/** Steps already played (inclusive of the current one) for fast lookup in views. */
export function playedSet(plan: PlanStep[], cursor: number): Set<number> {
  const out = new Set<number>()
  for (let i = 0; i <= cursor && i < plan.length; i++) out.add(plan[i]!.id)
  return out
}
