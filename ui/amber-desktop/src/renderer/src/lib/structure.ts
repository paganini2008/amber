import type { QueryBlock, QueryBlockSource, Statement } from '@shared/protocol'
import type { ThemeTokens } from '@shared/themes'
import { WRITE_ACCESS } from './model'

/**
 * The nested query structure of one statement (`Statement.structure`): CTEs, set operations,
 * derived tables and subqueries as boxes inside boxes.
 */

/** A block with a stable id: its path from the statement root, e.g. "0", "2.0.1". */
export interface StructureNode {
  id: string
  parentId: string | null
  depth: number
  block: QueryBlock
  children: StructureNode[]
}

export function blockId(path: number[]): string {
  return path.join('.')
}

/** Gives every block a path id. */
export function buildStructureTree(blocks: QueryBlock[] | undefined): StructureNode[] {
  const walk = (list: QueryBlock[], parent: StructureNode | null, path: number[]): StructureNode[] =>
    list.map((block, i) => {
      const p = [...path, i]
      const node: StructureNode = { id: blockId(p), parentId: parent?.id ?? null, depth: p.length - 1, block, children: [] }
      node.children = walk(block.children ?? [], node, p)
      return node
    })
  return walk(blocks ?? [], null, [])
}

/** All nodes depth first, in the order they are drawn. */
export function flattenStructure(tree: StructureNode[]): StructureNode[] {
  const out: StructureNode[] = []
  const walk = (list: StructureNode[]): void => {
    for (const n of list) {
      out.push(n)
      walk(n.children)
    }
  }
  walk(tree)
  return out
}

export interface StructureCounts {
  total: number
  byKind: Record<string, number>
  /** 1 for a flat structure, 0 when there is none. */
  maxDepth: number
}

export function countStructure(blocks: QueryBlock[] | undefined): StructureCounts {
  const byKind: Record<string, number> = {}
  let total = 0
  let maxDepth = 0
  const walk = (list: QueryBlock[], depth: number): void => {
    for (const b of list) {
      total += 1
      byKind[b.kind] = (byKind[b.kind] ?? 0) + 1
      maxDepth = Math.max(maxDepth, depth)
      walk(b.children ?? [], depth + 1)
    }
  }
  walk(blocks ?? [], 1)
  return { total, byKind, maxDepth }
}

/** Ids of the blocks that have children, i.e. the ones a chevron can collapse. */
export function collapsibleIds(tree: StructureNode[]): string[] {
  return flattenStructure(tree)
    .filter((n) => n.children.length > 0)
    .map((n) => n.id)
}

/** Short one-line description, e.g. "4 blocks · 2 CTEs, 1 set operation, 3 subqueries · 3 levels". */
export function describeCounts(c: StructureCounts): string {
  if (c.total === 0) return 'No query structure'
  const parts: string[] = []
  const add = (kind: string, one: string, many: string): void => {
    const n = c.byKind[kind] ?? 0
    if (n) parts.push(`${n} ${n === 1 ? one : many}`)
  }
  add('CTE', 'CTE', 'CTEs')
  add('SET', 'set operation', 'set operations')
  add('DERIVED', 'derived table', 'derived tables')
  add('SUBQUERY', 'subquery', 'subqueries')
  add('QUERY', 'query', 'queries')
  const levels = `${c.maxDepth} ${c.maxDepth === 1 ? 'level' : 'levels'}`
  return [`${c.total} ${c.total === 1 ? 'block' : 'blocks'}`, parts.join(', '), levels].filter(Boolean).join(' · ')
}

/** Badge text of a block: "QUERY", "UNION ALL", "CTE paid", "DERIVED r", "SUBQUERY". */
export function blockTitle(block: QueryBlock): string {
  switch (block.kind) {
    case 'SET':
      return block.name || 'SET'
    case 'CTE':
    case 'DERIVED':
      return block.name ? `${block.kind} ${block.name}` : block.kind
    default:
      return block.kind
  }
}

/** "crm.customer c", or just the name when there is no alias (or it repeats the name). */
export function sourceLabel(source: QueryBlockSource): string {
  return source.alias && source.alias.toLowerCase() !== source.name.toLowerCase() ? `${source.name} ${source.alias}` : source.name
}

/** Header of the statement box, e.g. "INSERT INTO report.top_customer" or "SELECT". */
export function statementTitle(statement: Statement): string {
  const kind = statement.kind.replace(/_/g, ' ')
  const written = [...new Set(statement.refs.filter((r) => WRITE_ACCESS.has(r.access) && r.kind !== 'CTE').map((r) => r.name))]
  if (!written.length) return statement.name ? `${kind} ${statement.name}` : kind
  const verb =
    statement.kind === 'INSERT' || statement.kind === 'SELECT_INTO' ? `${kind} INTO` : statement.kind === 'MERGE' ? 'MERGE INTO' : kind
  return `${verb} ${written.join(', ')}`
}

/**
 * The block a CTE or derived-table source refers to: searches the children of the block itself
 * and of each ancestor, nearest first, then the statement's top level.
 */
export function resolveSourceBlock(tree: StructureNode[], from: StructureNode | null, source: QueryBlockSource): StructureNode | null {
  const wanted = source.kind === 'CTE' ? 'CTE' : source.kind === 'SUBQUERY' ? 'DERIVED' : null
  if (!wanted) return null
  const name = (source.kind === 'SUBQUERY' ? source.name || source.alias : source.name)?.toLowerCase()
  if (!name) return null
  const byId = new Map(flattenStructure(tree).map((n) => [n.id, n]))
  const match = (list: StructureNode[]): StructureNode | null =>
    list.find((n) => n.block.kind === wanted && n.block.name?.toLowerCase() === name) ?? null
  let cur: StructureNode | null = from
  while (cur) {
    const hit = match(cur.children)
    if (hit) return hit
    cur = cur.parentId !== null ? (byId.get(cur.parentId) ?? null) : null
  }
  const top = match(tree)
  if (top) return top
  // CTEs of a set operation's branches sit beside the SET box; fall back to any block with the name.
  return flattenStructure(tree).find((n) => n.block.kind === wanted && n.block.name?.toLowerCase() === name) ?? null
}

/** Blocks whose range contains the offset, innermost last. */
export function blocksAt(tree: StructureNode[], offset: number): StructureNode[] {
  return flattenStructure(tree).filter((n) => offset >= n.block.range.start.offset && offset <= n.block.range.end.offset)
}

// ---------------------------------------------------------------------------------------------
// Colors per kind. The same palette drives the view (as CSS variables set by the theme) and the
// SVG export, so both match in every theme.

export type BlockKindColor = 'query' | 'set' | 'cte' | 'derived' | 'subquery'

export function kindColorKey(kind: string): BlockKindColor {
  switch (kind) {
    case 'SET':
      return 'set'
    case 'CTE':
      return 'cte'
    case 'DERIVED':
      return 'derived'
    case 'SUBQUERY':
      return 'subquery'
    default:
      return 'query'
  }
}

function luminance(hex: string): number {
  const v = parseInt(hex.slice(1), 16)
  const c = [(v >> 16) & 255, (v >> 8) & 255, v & 255].map((x) => {
    const s = x / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * c[0]! + 0.7152 * c[1]! + 0.0722 * c[2]!
}

export function isDarkTokens(t: ThemeTokens): boolean {
  return luminance(t.canvas) < 0.2
}

/**
 * Accent per block kind. Any two boxes can sit side by side, so the hues were validated
 * all-pairs (CVD and normal-vision separation, 3:1 on the surface), which allows three hues:
 * nested queries (DERIVED and SUBQUERY share blue; the badge text and a dashed edge on
 * SUBQUERY tell them apart), CTE green, SET magenta. QUERY stays neutral, and amber is left
 * for selection.
 */
export function structurePalette(t: ThemeTokens): Record<BlockKindColor, string> {
  const dark = isDarkTokens(t)
  return dark
    ? { query: t.inkMuted, set: '#D55181', cte: '#0CA30C', derived: '#3987E5', subquery: '#3987E5' }
    : { query: t.inkMuted, set: '#D55181', cte: '#008300', derived: '#2A78D6', subquery: '#2A78D6' }
}

// ---------------------------------------------------------------------------------------------
// Mermaid

/** Step numbers of a box for exports, e.g. "steps 13 FROM, 19 WHERE, 26 SELECT". */
export type StepBadges = Map<number, { phase: string; step: number }[]>

export function stepsText(badges: StepBadges | undefined, blockId: number | undefined): string {
  const list = typeof blockId === 'number' ? badges?.get(blockId) : undefined
  if (!list?.length) return ''
  return `${list.length === 1 ? 'step' : 'steps'} ${list.map((b) => `${b.step} ${b.phase === 'CTE' ? 'ready' : b.phase}`).join(', ')}`
}

/** Escapes text for a quoted Mermaid label (entity codes, so `#` goes first). */
export function escapeMermaid(text: string): string {
  return text
    .replace(/#/g, '#35;')
    .replace(/"/g, '#quot;')
    .replace(/</g, '#lt;')
    .replace(/>/g, '#gt;')
    .replace(/[\r\n]+/g, ' ')
}

function truncate(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, Math.max(1, max - 1))}…` : text
}

/** "sources: a, b c · WHERE GROUP BY · 3 columns" for a block's inner node. */
export function blockDetail(block: QueryBlock): string {
  const parts: string[] = []
  if (block.sources.length) parts.push(block.sources.map(sourceLabel).join(', '))
  if (block.clauses.length) parts.push(block.clauses.join(' '))
  if (block.outputs.length) parts.push(block.outputs.length <= 4 ? block.outputs.join(', ') : `${block.outputs.length} columns`)
  return parts.join(' · ')
}

/**
 * Nested `subgraph`s, one per block. Each subgraph holds a small node with the block's sources,
 * clauses and outputs (Mermaid draws an empty subgraph poorly).
 */
export function structureToMermaid(statement: Statement, tree: StructureNode[], palette?: Record<BlockKindColor, string>, badges?: StepBadges): string {
  const lines = ['flowchart TB']
  const styles: string[] = []
  const sid = (id: string): string => `b${id.replace(/\./g, '_')}`
  const write = (nodes: StructureNode[], indent: string): void => {
    for (const n of nodes) {
      const id = sid(n.id)
      const title = n.block.role ? `${blockTitle(n.block)} [${n.block.role}]` : blockTitle(n.block)
      lines.push(`${indent}subgraph ${id}["${escapeMermaid(title)}"]`)
      lines.push(`${indent}  direction TB`)
      const detail = truncate(blockDetail(n.block) || 'no sources', 120)
      const steps = stepsText(badges, n.block.id)
      lines.push(`${indent}  ${id}_d["${steps ? `${escapeMermaid(detail)}<br/>${escapeMermaid(steps)}` : escapeMermaid(detail)}"]`)
      write(n.children, `${indent}  `)
      lines.push(`${indent}end`)
      if (palette) styles.push(`  style ${id} stroke:${palette[kindColorKey(n.block.kind)]},stroke-width:1.5px`)
    }
  }
  lines.push(`  subgraph root["${escapeMermaid(`${statementTitle(statement)} (line ${statement.range.start.line})`)}"]`)
  lines.push('    direction TB')
  if (tree.length) write(tree, '    ')
  else lines.push('    root_d["no query structure"]')
  const writeStep = stepsText(badges, -1)
  if (writeStep && tree.length) lines.push(`    root_w["${escapeMermaid(writeStep)}"]`)
  lines.push('  end')
  lines.push(...styles)
  return lines.join('\n') + '\n'
}

// ---------------------------------------------------------------------------------------------
// Box layout for the SVG export: nested rectangles stacked top to bottom.

export interface StructureBox {
  node: StructureNode | null
  x: number
  y: number
  width: number
  height: number
  /** Text lines below the header, already truncated. */
  lines: { text: string; mono: boolean }[]
  title: string
  role: string | null
  collapsedChildren: number
}

export const BOX = { PAD: 10, HEADER: 24, LINE: 17, GAP: 8, INDENT: 12, CHAR: 6.6 }

/**
 * Lays out the statement box and its blocks. Children are indented by a fixed amount, so
 * deep nesting narrows the boxes slowly; the root grows wider when the structure is deep.
 */
export function layoutStructure(
  statement: Statement,
  tree: StructureNode[],
  collapsed: ReadonlySet<string> = new Set(),
  minWidth = 760,
  badges?: StepBadges
): { boxes: StructureBox[]; width: number; height: number } {
  const depth = countStructure(tree.map((n) => n.block)).maxDepth
  const rootWidth = Math.max(minWidth, 320 + depth * (BOX.INDENT + BOX.PAD) * 2)
  const boxes: StructureBox[] = []
  const chars = (w: number): number => Math.max(8, Math.floor((w - BOX.PAD * 2) / BOX.CHAR))

  const place = (node: StructureNode, x: number, y: number, width: number): number => {
    const b = node.block
    const lines: StructureBox['lines'] = []
    if (b.sources.length) lines.push({ text: truncate(b.sources.map(sourceLabel).join(' · '), chars(width)), mono: true })
    if (b.clauses.length) lines.push({ text: truncate(b.clauses.join('  '), chars(width)), mono: false })
    if (b.outputs.length)
      lines.push({ text: truncate(b.outputs.length > 6 ? `${b.outputs.length} columns` : b.outputs.join(', '), chars(width)), mono: true })
    const steps = stepsText(badges, b.id)
    if (steps) lines.push({ text: truncate(steps, chars(width)), mono: false })
    const box: StructureBox = {
      node,
      x,
      y,
      width,
      height: 0,
      lines,
      title: blockTitle(b),
      role: b.role,
      collapsedChildren: collapsed.has(node.id) ? flattenStructure(node.children).length : 0
    }
    boxes.push(box)
    let cy = y + BOX.HEADER + lines.length * BOX.LINE
    if (!collapsed.has(node.id) && node.children.length) {
      cy += BOX.GAP / 2
      for (const c of node.children) {
        cy = place(c, x + BOX.INDENT, cy, width - BOX.INDENT * 2) + BOX.GAP
      }
    } else if (box.collapsedChildren) {
      box.lines.push({ text: `${box.collapsedChildren} nested ${box.collapsedChildren === 1 ? 'block' : 'blocks'} collapsed`, mono: false })
      cy += BOX.LINE
    }
    box.height = cy - y + BOX.PAD / 2
    return y + box.height
  }

  const root: StructureBox = {
    node: null,
    x: 0,
    y: 0,
    width: rootWidth,
    height: 0,
    lines: [],
    title: statementTitle(statement),
    role: null,
    collapsedChildren: 0
  }
  boxes.push(root)
  const writeStep = stepsText(badges, -1)
  if (writeStep) root.lines.push({ text: writeStep, mono: false })
  let y = BOX.HEADER + root.lines.length * BOX.LINE + BOX.GAP / 2
  for (const n of tree) y = place(n, BOX.INDENT, y, rootWidth - BOX.INDENT * 2) + BOX.GAP
  if (!tree.length) {
    root.lines.push({ text: 'No query structure', mono: false })
    y += BOX.LINE
  }
  root.height = y + BOX.PAD / 2
  return { boxes, width: rootWidth, height: root.height }
}
