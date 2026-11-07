import { ROWS_COLUMN, type Lineage, type LineageEdge, type LineageNode } from '@shared/protocol'

/**
 * Column lineage (L3). The engine sends the whole script's graph; the UI only ever renders the
 * traced subgraph around one column, so scripts with thousands of columns stay responsive.
 */

export interface LineageOwner {
  key: string
  name: string
  kind: string
  nodeIds: number[]
  /** Statement ids whose edges touch this owner (for labels and playback). */
  statements: Set<number>
}

export interface LineageIndex {
  nodes: Map<number, LineageNode>
  edges: LineageEdge[]
  incoming: Map<number, number[]>
  outgoing: Map<number, number[]>
  owners: Map<string, LineageOwner>
  /** The `*rows*` node of each owner, if any. */
  rowsOf: Map<string, number>
  byStatement: Map<number, number[]>
}

/** More specific kinds win when the same owner key is reported with different kinds. */
const KIND_RANK: Record<string, number> = {
  TEMP_TABLE: 0,
  TABLE_VARIABLE: 0,
  MATERIALIZED_VIEW: 1,
  VIEW: 1,
  CTE: 2,
  SUBQUERY: 2,
  RESULT: 2,
  TABLE: 3,
  UNKNOWN: 4
}

export function isRows(node: LineageNode): boolean {
  return node.column === ROWS_COLUMN
}

export function indexLineage(lineage: Lineage): LineageIndex {
  const nodes = new Map<number, LineageNode>()
  const owners = new Map<string, LineageOwner>()
  const rowsOf = new Map<string, number>()
  for (const n of lineage.nodes) {
    nodes.set(n.id, n)
    let owner = owners.get(n.ownerKey)
    if (!owner) {
      owner = { key: n.ownerKey, name: n.owner, kind: n.ownerKind, nodeIds: [], statements: new Set() }
      owners.set(n.ownerKey, owner)
    } else if ((KIND_RANK[n.ownerKind] ?? 3) < (KIND_RANK[owner.kind] ?? 3)) {
      owner.kind = n.ownerKind
    }
    owner.nodeIds.push(n.id)
    if (isRows(n)) rowsOf.set(n.ownerKey, n.id)
  }
  const incoming = new Map<number, number[]>()
  const outgoing = new Map<number, number[]>()
  const byStatement = new Map<number, number[]>()
  lineage.edges.forEach((e, i) => {
    push(incoming, e.to, i)
    push(outgoing, e.from, i)
    push(byStatement, e.statement, i)
    const from = nodes.get(e.from)
    const to = nodes.get(e.to)
    if (from) owners.get(from.ownerKey)?.statements.add(e.statement)
    if (to) owners.get(to.ownerKey)?.statements.add(e.statement)
  })
  return { nodes, edges: lineage.edges, incoming, outgoing, owners, rowsOf, byStatement }
}

function push<K>(map: Map<K, number[]>, key: K, value: number): void {
  const list = map.get(key)
  if (list) list.push(value)
  else map.set(key, [value])
}

export type LineageDirection = 'upstream' | 'downstream' | 'both'

export interface LineageTraceOptions {
  direction: LineageDirection
  indirect: boolean
  /** Stop after this many column nodes; the trace is then marked truncated. */
  maxNodes?: number
}

export interface LineageTrace {
  nodes: Set<number>
  edges: Set<number>
  truncated: boolean
}

export const MAX_TRACE_NODES = 600

/**
 * Upstream: follow edges into the column, back to source tables. With `indirect`, the rows of each
 * owner on the way are also traced (FILTER/JOIN/GROUP columns decide which rows arrive).
 * Downstream: follow edges out of the column. With `indirect`, a column that filters, joins or
 * groups another owner affects every column of that owner.
 */
export function traceLineage(index: LineageIndex, start: number, options: LineageTraceOptions): LineageTrace {
  const max = options.maxNodes ?? MAX_TRACE_NODES
  const trace: LineageTrace = { nodes: new Set([start]), edges: new Set(), truncated: false }
  const add = (id: number): boolean => {
    if (trace.nodes.has(id)) return false
    if (trace.nodes.size >= max) {
      trace.truncated = true
      return false
    }
    trace.nodes.add(id)
    return true
  }

  if (options.direction !== 'downstream') {
    const queue = [start]
    const seen = new Set<number>()
    while (queue.length) {
      const id = queue.shift()!
      if (seen.has(id)) continue
      seen.add(id)
      const inEdges = [...(index.incoming.get(id) ?? [])]
      if (options.indirect) {
        const node = index.nodes.get(id)
        const rows = node ? index.rowsOf.get(node.ownerKey) : undefined
        if (rows !== undefined && rows !== id) {
          add(rows)
          inEdges.push(...(index.incoming.get(rows) ?? []))
        }
      }
      for (const ei of inEdges) {
        const e = index.edges[ei]!
        if (e.kind !== 'DIRECT' && !options.indirect) continue
        trace.edges.add(ei)
        if (add(e.from) || trace.nodes.has(e.from)) queue.push(e.from)
      }
    }
  }

  if (options.direction !== 'upstream') {
    const queue = [start]
    const seen = new Set<number>()
    while (queue.length) {
      const id = queue.shift()!
      if (seen.has(id)) continue
      seen.add(id)
      for (const ei of index.outgoing.get(id) ?? []) {
        const e = index.edges[ei]!
        if (e.kind !== 'DIRECT' && !options.indirect) continue
        trace.edges.add(ei)
        if (add(e.to) || trace.nodes.has(e.to)) queue.push(e.to)
        const target = index.nodes.get(e.to)
        if (target && isRows(target)) {
          for (const sibling of index.owners.get(target.ownerKey)?.nodeIds ?? []) {
            if (sibling !== e.to && (add(sibling) || trace.nodes.has(sibling))) queue.push(sibling)
          }
        }
      }
    }
  }
  return trace
}

/** Columns that can be traced, best candidates first: results and written tables before sources. */
export interface PickableColumn {
  nodeId: number
  owner: string
  ownerKey: string
  ownerKind: string
  column: string
  /** Has edges coming in (it is computed from something). */
  derived: boolean
}

const PICK_RANK: Record<string, number> = { RESULT: 2, CTE: 3, SUBQUERY: 4, UNKNOWN: 5 }

export function pickableColumns(index: LineageIndex): PickableColumn[] {
  const out: PickableColumn[] = []
  for (const n of index.nodes.values()) {
    if (isRows(n)) continue
    const owner = index.owners.get(n.ownerKey)
    out.push({
      nodeId: n.id,
      owner: n.owner,
      ownerKey: n.ownerKey,
      ownerKind: owner?.kind ?? n.ownerKind,
      column: n.column,
      derived: (index.incoming.get(n.id)?.length ?? 0) > 0
    })
  }
  const rank = (c: PickableColumn): number => (PICK_RANK[c.ownerKind] ?? (c.derived ? 0 : 1))
  return out.sort((a, b) => rank(a) - rank(b) || a.owner.localeCompare(b.owner) || a.column.localeCompare(b.column))
}

/** Lineage owners use upper-cased keys; lifecycles use the engine's own normalization. */
export function ownerKeyForObject(index: LineageIndex, objectKey: string): string | null {
  if (index.owners.has(objectKey)) return objectKey
  const upper = objectKey.toUpperCase()
  for (const k of index.owners.keys()) if (k.toUpperCase() === upper) return k
  return null
}

/** One output column of a statement and how it is computed, through CTE/subquery hops. */
export interface ColumnMapping {
  output: LineageNode
  /** Each path runs from a source column (first) to the output (last). */
  paths: { nodes: LineageNode[]; edges: LineageEdge[] }[]
}

export interface StatementLineage {
  mappings: ColumnMapping[]
  /** Columns that decide which rows arrive: FILTER, JOIN, GROUP. */
  indirect: { role: string; source: LineageNode; target: LineageNode; edge: LineageEdge }[]
}

const HOP_KINDS = new Set(['CTE', 'SUBQUERY'])

/** The statement's own column mapping, from the lineage edges it creates. */
export function statementLineage(index: LineageIndex, statementId: number, maxPaths = 12): StatementLineage {
  const edgeIds = index.byStatement.get(statementId) ?? []
  const direct = edgeIds.map((i) => index.edges[i]!).filter((e) => e.kind === 'DIRECT')
  const into = new Map<number, LineageEdge[]>()
  for (const e of direct) {
    const list = into.get(e.to)
    if (list) list.push(e)
    else into.set(e.to, [e])
  }
  const kindOf = (id: number): string => {
    const n = index.nodes.get(id)
    return n ? (index.owners.get(n.ownerKey)?.kind ?? n.ownerKind) : 'UNKNOWN'
  }
  // Outputs: written columns that are not intermediate hops inside this statement.
  const outputs = [...into.keys()].filter((id) => !HOP_KINDS.has(kindOf(id)))
  const mappings: ColumnMapping[] = []
  for (const out of outputs) {
    const paths: ColumnMapping['paths'] = []
    const walk = (id: number, nodes: LineageNode[], edges: LineageEdge[], depth: number): void => {
      if (paths.length >= maxPaths) return
      const incoming = into.get(id)
      const node = index.nodes.get(id)!
      const isHop = HOP_KINDS.has(kindOf(id))
      if (!incoming || depth > 8 || (id !== out && !isHop)) {
        paths.push({ nodes: [node, ...nodes], edges })
        return
      }
      for (const e of incoming) {
        if (nodes.some((n) => n.id === e.from)) continue
        walk(e.from, [node, ...nodes], [e, ...edges], depth + 1)
      }
    }
    walk(out, [], [], 0)
    const outputNode = index.nodes.get(out)
    if (outputNode) mappings.push({ output: outputNode, paths: paths.map((p) => ({ nodes: p.nodes, edges: p.edges })) })
  }
  mappings.sort((a, b) => a.output.owner.localeCompare(b.output.owner) || a.output.id - b.output.id)
  const indirect = edgeIds
    .map((i) => index.edges[i]!)
    .filter((e) => e.kind !== 'DIRECT')
    .map((e) => ({ role: e.role ?? 'INDIRECT', source: index.nodes.get(e.from)!, target: index.nodes.get(e.to)!, edge: e }))
    .filter((x) => x.source && x.target)
  return { mappings, indirect }
}

export function transformLabel(edge: LineageEdge): string {
  if (edge.kind !== 'DIRECT') return (edge.role ?? 'indirect').toLowerCase()
  switch (edge.transform) {
    case 'PASS_THROUGH':
      return 'copy'
    case null:
    case undefined:
      return ''
    default:
      return edge.transform.toLowerCase().replace(/_/g, ' ')
  }
}

export function ownerKindLabel(kind: string): string {
  switch (kind) {
    case 'TEMP_TABLE':
      return 'temp table'
    case 'TABLE_VARIABLE':
      return 'table variable'
    case 'MATERIALIZED_VIEW':
      return 'materialized view'
    case 'CTE':
      return 'CTE'
    case 'SUBQUERY':
      return 'subquery'
    case 'RESULT':
      return 'query result'
    case 'UNKNOWN':
      return 'unresolved'
    default:
      return kind.toLowerCase().replace(/_/g, ' ')
  }
}

/** Owner display name: results and subqueries get their statement context. */
export function ownerTitle(owner: LineageOwner): string {
  if (owner.kind === 'UNKNOWN') return 'Unresolved table'
  if (owner.kind === 'RESULT') {
    const step = [...owner.statements][0]
    return step !== undefined ? `Result of step ${step + 1}` : 'Query result'
  }
  return owner.name
}

/**
 * Object-level Mermaid for a lineage subgraph. Column-level Mermaid becomes unreadable quickly, so
 * owners are the nodes and an edge means "some column flows from A to B" (labelled with transforms).
 */
export function lineageToMermaid(index: LineageIndex, trace: LineageTrace): string {
  const ids = new Map<string, string>()
  const idFor = (key: string): string => {
    let id = ids.get(key)
    if (!id) {
      id = `o${ids.size}`
      ids.set(key, id)
    }
    return id
  }
  const owners = new Set<string>()
  for (const n of trace.nodes) {
    const node = index.nodes.get(n)
    if (node) owners.add(node.ownerKey)
  }
  const lines = ['flowchart LR']
  for (const key of owners) {
    const o = index.owners.get(key)!
    const label = `${ownerTitle(o)}<br/>${ownerKindLabel(o.kind)}`.replace(/"/g, '#quot;')
    const shape = o.kind === 'CTE' || o.kind === 'SUBQUERY' ? `(["${label}"])` : o.kind === 'RESULT' ? `[["${label}"]]` : `["${label}"]`
    lines.push(`  ${idFor(key)}${shape}`)
  }
  const agg = new Map<string, { labels: Set<string>; indirect: boolean }>()
  for (const ei of trace.edges) {
    const e = index.edges[ei]!
    const a = index.nodes.get(e.from)
    const b = index.nodes.get(e.to)
    if (!a || !b || a.ownerKey === b.ownerKey) continue
    const k = `${a.ownerKey}\u0000${b.ownerKey}`
    const entry = agg.get(k) ?? { labels: new Set<string>(), indirect: true }
    entry.labels.add(transformLabel(e))
    if (e.kind === 'DIRECT') entry.indirect = false
    agg.set(k, entry)
  }
  for (const [k, v] of agg) {
    const [a, b] = k.split('\u0000') as [string, string]
    const label = [...v.labels].filter(Boolean).join(', ')
    lines.push(`  ${idFor(a)} ${v.indirect ? '-.->' : '-->'}${label ? `|"${label}"|` : ''} ${idFor(b)}`)
  }
  return lines.join('\n') + '\n'
}
