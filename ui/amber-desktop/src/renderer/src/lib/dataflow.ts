import type { Lifecycle, Statement } from '@shared/protocol'
import { dataRefs, writesData, type AnalysisIndex } from './model'
import { t, tOr } from '../i18n'
import { kindText, objectKindText } from './kinds'

/**
 * Data-flow graph: source objects → statements → intermediate objects → targets.
 * Built from statement refs. Objects are lifecycle keys; statements are step ids.
 */
export interface FlowGraph {
  statements: number[]
  objects: string[]
  /** Objects each statement reads / writes data into. */
  readsOf: Map<number, Set<string>>
  writesOf: Map<number, Set<string>>
  readersOf: Map<string, number[]>
  writersOf: Map<string, number[]>
  /** Weakest confidence seen on each edge. */
  edgeConfidence: Map<string, string>
  /**
   * Edges that exist only because a call reaches the object through a routine defined in the script:
   * edge id → routine. A direct mention of the same object by the same statement removes the entry.
   */
  edgeVia: Map<string, string>
}

export const objectNodeId = (key: string): string => `o:${key}`
export const statementNodeId = (id: number): string => `s:${id}`
export const groupNodeId = (id: string): string => `g:${id}`
export const edgeId = (from: string, to: string): string => `${from}->${to}`

const CONFIDENCE_RANK: Record<string, number> = { CERTAIN: 0, INFERRED: 1, PARTIAL: 2, UNRESOLVED: 3 }

function weaker(a: string | undefined, b: string): string {
  if (!a) return b
  return (CONFIDENCE_RANK[b] ?? 1) > (CONFIDENCE_RANK[a] ?? 1) ? b : a
}

export function buildFlowGraph(index: AnalysisIndex): FlowGraph {
  const readsOf = new Map<number, Set<string>>()
  const writesOf = new Map<number, Set<string>>()
  const readersOf = new Map<string, number[]>()
  const writersOf = new Map<string, number[]>()
  const edgeConfidence = new Map<string, string>()
  const edgeVia = new Map<string, string>()
  const direct = new Set<string>()
  const noteVia = (id: string, via: string | null | undefined): void => {
    if (!via) {
      direct.add(id)
      edgeVia.delete(id)
    } else if (!direct.has(id) && !edgeVia.has(id)) edgeVia.set(id, via)
  }
  const statements: number[] = []
  const objects = new Set<string>()

  for (const s of index.order) {
    const refs = dataRefs(index, s)
    const reads = new Set<string>()
    const writes = new Set<string>()
    for (const r of refs) {
      if (r.access === 'READ') {
        reads.add(r.key)
        const id = edgeId(objectNodeId(r.key), statementNodeId(s.id))
        edgeConfidence.set(id, weaker(edgeConfidence.get(id), r.confidence))
        noteVia(id, r.via)
      } else if (writesData(s, r)) {
        writes.add(r.key)
        const id = edgeId(statementNodeId(s.id), objectNodeId(r.key))
        edgeConfidence.set(id, weaker(edgeConfidence.get(id), r.confidence))
        noteVia(id, r.via)
      }
    }
    // A statement that reads and writes the same object (UPDATE t ... FROM t) keeps only the write.
    for (const k of writes) {
      if (reads.has(k)) {
        reads.delete(k)
        edgeConfidence.delete(edgeId(objectNodeId(k), statementNodeId(s.id)))
      }
    }
    if (reads.size === 0 && writes.size === 0) continue
    statements.push(s.id)
    readsOf.set(s.id, reads)
    writesOf.set(s.id, writes)
    for (const k of reads) {
      objects.add(k)
      push(readersOf, k, s.id)
    }
    for (const k of writes) {
      objects.add(k)
      push(writersOf, k, s.id)
    }
  }
  return { statements, objects: [...objects], readsOf, writesOf, readersOf, writersOf, edgeConfidence, edgeVia }
}

function push<K, V>(map: Map<K, V[]>, key: K, value: V): void {
  const list = map.get(key)
  if (list) list.push(value)
  else map.set(key, [value])
}

export interface Trace {
  statements: Set<number>
  objects: Set<string>
  edges: Set<string>
}

export function emptyTrace(): Trace {
  return { statements: new Set(), objects: new Set(), edges: new Set() }
}

/**
 * The path through a statement: everything upstream that fed its inputs, and everything
 * downstream that later read its outputs. Respects step order.
 */
export function traceStatement(graph: FlowGraph, id: number): Trace {
  const trace = emptyTrace()
  trace.statements.add(id)
  upstream(graph, id, trace, new Set())
  downstream(graph, id, trace, new Set())
  return trace
}

/** The path through an object: all writers and their inputs, all readers and their outputs. */
export function traceObject(graph: FlowGraph, key: string): Trace {
  const trace = emptyTrace()
  trace.objects.add(key)
  const seenUp = new Set<number>()
  const seenDown = new Set<number>()
  for (const w of graph.writersOf.get(key) ?? []) {
    trace.statements.add(w)
    trace.edges.add(edgeId(statementNodeId(w), objectNodeId(key)))
    upstream(graph, w, trace, seenUp)
  }
  for (const r of graph.readersOf.get(key) ?? []) {
    trace.statements.add(r)
    trace.edges.add(edgeId(objectNodeId(key), statementNodeId(r)))
    downstream(graph, r, trace, seenDown)
  }
  return trace
}

function upstream(graph: FlowGraph, start: number, trace: Trace, seen: Set<number>): void {
  const queue = [start]
  while (queue.length > 0) {
    const s = queue.shift()!
    if (seen.has(s)) continue
    seen.add(s)
    for (const k of graph.readsOf.get(s) ?? []) {
      trace.objects.add(k)
      trace.edges.add(edgeId(objectNodeId(k), statementNodeId(s)))
      for (const w of graph.writersOf.get(k) ?? []) {
        if (w >= s) continue
        trace.statements.add(w)
        trace.edges.add(edgeId(statementNodeId(w), objectNodeId(k)))
        queue.push(w)
      }
    }
  }
}

function downstream(graph: FlowGraph, start: number, trace: Trace, seen: Set<number>): void {
  const queue = [start]
  while (queue.length > 0) {
    const s = queue.shift()!
    if (seen.has(s)) continue
    seen.add(s)
    for (const k of graph.writesOf.get(s) ?? []) {
      trace.objects.add(k)
      trace.edges.add(edgeId(statementNodeId(s), objectNodeId(k)))
      for (const r of graph.readersOf.get(k) ?? []) {
        if (r <= s) continue
        trace.statements.add(r)
        trace.edges.add(edgeId(objectNodeId(k), statementNodeId(r)))
        queue.push(r)
      }
    }
  }
}

/** The view model handed to layout and rendering, after grouping. */
export interface FlowNode {
  id: string
  type: 'object' | 'statement' | 'group'
  label: string
  sublabel: string
  /** Object role, statement kind, or group size. */
  role?: string
  kind?: string
  confidence?: string
  /** Statement ids this node stands for (one for statements, many for groups). */
  statementIds: number[]
  objectKey?: string
  width: number
  height: number
}

export interface FlowEdge {
  id: string
  source: string
  target: string
  confidence: string
  /** Statement ids this edge belongs to (for playback and tracing of groups). */
  statementIds: number[]
  /** Underlying statement-level edge ids, for tracing through groups. */
  members: string[]
  /** Set when every member exists only through a call to this routine (shown as "via …"). */
  via?: string
}

export interface FlowView {
  nodes: FlowNode[]
  edges: FlowEdge[]
  grouped: boolean
  groupOf: Map<number, string>
  /** Objects hidden inside a group because nothing outside it touches them. */
  folded: Map<string, string>
}

export const GROUP_THRESHOLD = 300
const GROUP_CHUNK = 25

const CHAR_W = 7.2

function nodeWidth(label: string, sub: string): number {
  return Math.round(Math.min(320, Math.max(150, Math.max(label.length * CHAR_W, sub.length * 6.4) + 40)))
}

/**
 * Builds the visible graph. When it has more than GROUP_THRESHOLD nodes, statements collapse into
 * groups: nested statements by their top-level container, top-level ones in runs of GROUP_CHUNK.
 * Groups listed in `expanded` stay open.
 */
export function buildFlowView(index: AnalysisIndex, graph: FlowGraph, expanded: ReadonlySet<string> = new Set()): FlowView {
  const total = graph.statements.length + graph.objects.length
  const grouped = total > GROUP_THRESHOLD
  const groupOf = new Map<number, string>()
  const groupMembers = new Map<string, number[]>()

  if (grouped) {
    let chunkStart: number | null = null
    let chunkCount = 0
    for (const id of graph.statements) {
      const root = rootOf(index, id)
      let gid: string
      if (root !== id) {
        gid = `c${root}`
      } else {
        if (chunkStart === null || chunkCount >= GROUP_CHUNK) {
          chunkStart = id
          chunkCount = 0
        }
        chunkCount++
        gid = `r${chunkStart}`
      }
      if (expanded.has(gid)) continue
      groupOf.set(id, gid)
      push(groupMembers, gid, id)
    }
    // A group of one is just the statement.
    for (const [gid, members] of [...groupMembers]) {
      if (members.length === 1) {
        groupMembers.delete(gid)
        groupOf.delete(members[0]!)
      }
    }
  }

  // Objects only written and read inside one group (e.g. chained temp tables) fold into it.
  const folded = new Map<string, string>()
  if (grouped) {
    for (const key of graph.objects) {
      const touching = [...(graph.writersOf.get(key) ?? []), ...(graph.readersOf.get(key) ?? [])]
      const groups = new Set(touching.map((id) => groupOf.get(id) ?? `s${id}`))
      const only = groups.size === 1 ? [...groups][0]! : null
      if (only && groupMembers.has(only)) folded.set(key, only)
    }
  }
  const foldedCount = new Map<string, number>()
  for (const g of folded.values()) foldedCount.set(g, (foldedCount.get(g) ?? 0) + 1)

  const nodes: FlowNode[] = []
  for (const key of graph.objects) {
    if (folded.has(key)) continue
    const l = index.lifecycleByKey.get(key)
    const label = l?.name ?? key
    const sub = l ? `${kindLabel(l)} · ${roleLabel(l.role)}` : ''
    nodes.push({
      id: objectNodeId(key),
      type: 'object',
      label,
      sublabel: sub,
      role: l?.role,
      kind: l?.kind,
      statementIds: graph.writersOf.get(key) ?? [],
      objectKey: key,
      width: nodeWidth(label, sub),
      height: 46
    })
  }
  for (const id of graph.statements) {
    if (groupOf.has(id)) continue
    const s = index.byId.get(id)!
    const label = statementTitle(s)
    const sub = t('common.stepLine', { step: id + 1, line: s.range.start.line })
    nodes.push({
      id: statementNodeId(id),
      type: 'statement',
      label,
      sublabel: sub,
      kind: s.kind,
      confidence: s.confidence,
      statementIds: [id],
      width: nodeWidth(label, sub),
      height: 46
    })
  }
  for (const [gid, members] of groupMembers) {
    const first = index.byId.get(members[0]!)!
    const last = index.byId.get(members[members.length - 1]!)!
    const container = gid.startsWith('c') ? index.byId.get(Number(gid.slice(1))) : undefined
    const label = container ? statementTitle(container) : t('dataflow.group.steps', { from: first.id + 1, to: last.id + 1 })
    const inner = foldedCount.get(gid) ?? 0
    const sub = [
      t('common.statements', { count: members.length }),
      inner ? t('dataflow.group.internal', { count: inner }) : '',
      t('common.lines', { from: first.range.start.line, to: last.range.end.line })
    ]
      .filter(Boolean)
      .join(' · ')
    nodes.push({
      id: groupNodeId(gid),
      type: 'group',
      label,
      sublabel: sub,
      role: String(members.length),
      statementIds: members,
      width: nodeWidth(label, sub),
      height: 46
    })
  }

  const visible = (id: number): string => {
    const g = groupOf.get(id)
    return g ? groupNodeId(g) : statementNodeId(id)
  }
  const edges = new Map<string, FlowEdge>()
  const addEdge = (source: string, target: string, stmt: number, member: string): void => {
    const id = edgeId(source, target)
    const confidence = graph.edgeConfidence.get(member) ?? 'INFERRED'
    const existing = edges.get(id)
    if (existing) {
      existing.statementIds.push(stmt)
      existing.members.push(member)
      existing.confidence = weaker(existing.confidence, confidence)
    } else {
      edges.set(id, { id, source, target, confidence, statementIds: [stmt], members: [member] })
    }
  }
  for (const id of graph.statements) {
    const v = visible(id)
    for (const k of graph.readsOf.get(id) ?? []) {
      if (folded.has(k)) continue
      addEdge(objectNodeId(k), v, id, edgeId(objectNodeId(k), statementNodeId(id)))
    }
    for (const k of graph.writesOf.get(id) ?? []) {
      if (folded.has(k)) continue
      addEdge(v, objectNodeId(k), id, edgeId(statementNodeId(id), objectNodeId(k)))
    }
  }
  for (const e of edges.values()) {
    const vias = new Set(e.members.map((m) => graph.edgeVia.get(m) ?? ''))
    if (vias.size === 1 && !vias.has('')) e.via = [...vias][0]
  }
  return { nodes, edges: [...edges.values()], grouped, groupOf, folded }
}

function rootOf(index: AnalysisIndex, id: number): number {
  let current = id
  let parent = index.parentOf.get(current) ?? null
  while (parent !== null) {
    current = parent
    parent = index.parentOf.get(current) ?? null
  }
  return current
}

export function statementTitle(s: Statement): string {
  const base = kindText(s.kind)
  return s.name ? `${base} ${s.name}` : base
}

export function kindLabel(l: Pick<Lifecycle, 'kind'>): string {
  return objectKindText(l.kind)
}

export function roleLabel(role: string): string {
  return tOr(`dataflow.role.${role}`, role.toLowerCase())
}
