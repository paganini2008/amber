import type { QueryBlock, Range, Statement } from '@shared/protocol'
import type { AnalysisIndex } from './model'

/** A named step inside one statement: a CTE of a WITH query. */
export interface StatementPart {
  name: string
  range: Range
  /** Tables and earlier CTEs it reads. */
  sources: string[]
}

export interface OutlineRow {
  statement: Statement
  depth: number
  hasChildren: boolean
  collapsed: boolean
  /** Set for a CTE row under its statement; the statement row itself has none. */
  part?: StatementPart
}

/**
 * The CTEs of a statement in source order. A script written as one long WITH query reads as its steps:
 * each CTE is a stage the next ones build on.
 */
export function statementParts(s: Statement): StatementPart[] {
  if (s.container || !s.structure?.length) return []
  const out: StatementPart[] = []
  const walk = (blocks: QueryBlock[]): void => {
    for (const b of blocks) {
      if (b.kind === 'CTE' && b.name) out.push({ name: b.name, range: b.range, sources: readsInside(s, b) })
      walk(b.children ?? [])
    }
  }
  walk(s.structure)
  return out.sort((a, b) => a.range.start.offset - b.range.start.offset)
}

/**
 * What a CTE reads: the statement's references inside its text (tables and earlier CTEs), which also covers
 * subqueries and derived tables the structure does not break down; the block's own sources otherwise.
 */
function readsInside(s: Statement, block: QueryBlock): string[] {
  const { start, end } = block.range
  const names = new Set<string>()
  for (const r of s.refs) {
    if (r.access !== 'READ' || r.via || r.range.start.offset < start.offset || r.range.end.offset > end.offset) continue
    if (r.name.toUpperCase() !== block.name?.toUpperCase()) names.add(r.name)
  }
  if (!names.size) for (const x of block.sources) if (x.kind === 'TABLE' || x.kind === 'CTE' || x.kind === 'VIEW') names.add(x.name)
  return [...names]
}

/** Flattens the statement tree into visible rows, honouring collapsed containers; CTEs show as children. */
export function flattenOutline(statements: Statement[], collapsed: ReadonlySet<number>): OutlineRow[] {
  const rows: OutlineRow[] = []
  const walk = (list: Statement[], depth: number): void => {
    for (const s of list) {
      const parts = s.children.length ? [] : statementParts(s)
      const hasChildren = s.children.length > 0 || parts.length > 1
      const isCollapsed = hasChildren && collapsed.has(s.id)
      rows.push({ statement: s, depth, hasChildren, collapsed: isCollapsed })
      if (!hasChildren || isCollapsed) continue
      if (s.children.length) walk(s.children, depth + 1)
      else for (const part of parts) rows.push({ statement: s, depth: depth + 1, hasChildren: false, collapsed: false, part })
    }
  }
  walk(statements, 0)
  return rows
}

/** The row to scroll to for a statement: itself, or its nearest visible ancestor. */
export function visibleRowFor(index: AnalysisIndex, rows: OutlineRow[], id: number): number {
  const positions = new Map<number, number>()
  rows.forEach((r, i) => {
    if (!r.part && !positions.has(r.statement.id)) positions.set(r.statement.id, i)
  })
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
