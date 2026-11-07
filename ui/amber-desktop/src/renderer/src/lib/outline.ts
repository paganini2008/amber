import type { Statement } from '@shared/protocol'
import type { AnalysisIndex } from './model'

export interface OutlineRow {
  statement: Statement
  depth: number
  hasChildren: boolean
  collapsed: boolean
}

/** Flattens the statement tree into visible rows, honouring collapsed containers. */
export function flattenOutline(statements: Statement[], collapsed: ReadonlySet<number>): OutlineRow[] {
  const rows: OutlineRow[] = []
  const walk = (list: Statement[], depth: number): void => {
    for (const s of list) {
      const hasChildren = s.children.length > 0
      const isCollapsed = hasChildren && collapsed.has(s.id)
      rows.push({ statement: s, depth, hasChildren, collapsed: isCollapsed })
      if (hasChildren && !isCollapsed) walk(s.children, depth + 1)
    }
  }
  walk(statements, 0)
  return rows
}

/** The row to scroll to for a statement: itself, or its nearest visible ancestor. */
export function visibleRowFor(index: AnalysisIndex, rows: OutlineRow[], id: number): number {
  const positions = new Map<number, number>()
  rows.forEach((r, i) => positions.set(r.statement.id, i))
  let current: number | null = id
  while (current !== null) {
    const pos = positions.get(current)
    if (pos !== undefined) return pos
    current = index.parentOf.get(current) ?? null
  }
  return -1
}

/** Statement ids on the path from the root to `id`, inclusive. */
export function pathTo(index: AnalysisIndex, id: number): Set<number> {
  const out = new Set<number>()
  let current: number | null = id
  while (current !== null) {
    out.add(current)
    current = index.parentOf.get(current) ?? null
  }
  return out
}
