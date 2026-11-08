import type { LineageEdge } from '@shared/protocol'
import { isRows, isVariableKind, ownerSubtitle, ownerTitle, type LineageIndex, type LineageTrace } from './lineage'
import { t } from '../i18n'

/**
 * View model for the Lineage view: the traced subgraph as owner cards with column rows.
 * Only traced columns (plus a few receded neighbours for context) are ever built.
 */
export interface LineageCardRow {
  nodeId: number
  label: string
  rows: boolean
  traced: boolean
}

export interface LineageCard {
  id: string
  ownerKey: string
  kind: string
  title: string
  subtitle: string
  rows: LineageCardRow[]
  hidden: number
  width: number
  height: number
}

export interface LineageViewEdge {
  id: string
  index: number
  edge: LineageEdge
  sourceCard: string
  targetCard: string
  traced: boolean
  label: string
}

export interface LineageViewModel {
  cards: LineageCard[]
  edges: LineageViewEdge[]
  rowIndex: Map<number, { card: string; row: number }>
}

export const CARD = { WIDTH: 248, HEADER: 40, ROW: 22, FOOTER: 20 }
const CONTEXT_COLUMNS = 6

export const cardId = (ownerKey: string): string => `c:${ownerKey}`

export function buildLineageView(index: LineageIndex, trace: LineageTrace, label: (e: LineageEdge) => string): LineageViewModel {
  const tracedByOwner = new Map<string, number[]>()
  for (const id of trace.nodes) {
    const n = index.nodes.get(id)
    if (!n) continue
    const list = tracedByOwner.get(n.ownerKey)
    if (list) list.push(id)
    else tracedByOwner.set(n.ownerKey, [id])
  }
  const cards: LineageCard[] = []
  const rowIndex = new Map<number, { card: string; row: number }>()
  for (const [ownerKey, traced] of tracedByOwner) {
    const owner = index.owners.get(ownerKey)
    if (!owner) continue
    const tracedSet = new Set(traced)
    const context = owner.nodeIds.filter((id) => !tracedSet.has(id) && !isRows(index.nodes.get(id)!))
    const shown = context.slice(0, CONTEXT_COLUMNS)
    const order = (id: number): number => (isRows(index.nodes.get(id)!) ? 1 : 0)
    const ids = [...traced.sort((a, b) => order(a) - order(b) || a - b), ...shown]
    const id = cardId(ownerKey)
    const rows = ids.map((nodeId, i) => {
      const n = index.nodes.get(nodeId)!
      rowIndex.set(nodeId, { card: id, row: i })
      // A variable's card is titled with its name; its row is simply its value.
      const label = isRows(n) ? t('lineage.card.rows') : isVariableKind(owner.kind) ? t('lineage.card.value') : n.column
      return { nodeId, label, rows: isRows(n), traced: tracedSet.has(nodeId) }
    })
    const hidden = context.length - shown.length
    cards.push({
      id,
      ownerKey,
      kind: owner.kind,
      title: ownerTitle(owner),
      subtitle: ownerSubtitle(owner),
      rows,
      hidden,
      width: CARD.WIDTH,
      height: CARD.HEADER + rows.length * CARD.ROW + (hidden > 0 ? CARD.FOOTER : 6)
    })
  }
  const edges: LineageViewEdge[] = []
  // Traced edges, plus untraced edges between columns already on screen (shown receded).
  const candidates = new Set<number>(trace.edges)
  for (const nodeId of rowIndex.keys()) {
    for (const ei of index.outgoing.get(nodeId) ?? []) if (rowIndex.has(index.edges[ei]!.to)) candidates.add(ei)
  }
  for (const ei of candidates) {
    const e = index.edges[ei]!
    const a = rowIndex.get(e.from)
    const b = rowIndex.get(e.to)
    if (!a || !b) continue
    edges.push({ id: `e${ei}`, index: ei, edge: e, sourceCard: a.card, targetCard: b.card, traced: trace.edges.has(ei), label: label(e) })
  }
  return { cards, edges, rowIndex }
}

/** Lineage of one statement: the columns its edges touch (used during playback without a focus). */
export function statementTrace(index: LineageIndex, statementId: number): LineageTrace {
  const trace: LineageTrace = { nodes: new Set(), edges: new Set(), truncated: false }
  for (const ei of index.byStatement.get(statementId) ?? []) {
    const e = index.edges[ei]!
    trace.edges.add(ei)
    trace.nodes.add(e.from)
    trace.nodes.add(e.to)
  }
  return trace
}

/** Anchor point of a column row on a card side, for SVG export. */
export function rowAnchor(pos: { x: number; y: number }, row: number, side: 'left' | 'right'): { x: number; y: number } {
  return { x: pos.x + (side === 'right' ? CARD.WIDTH : 0), y: pos.y + CARD.HEADER + row * CARD.ROW + CARD.ROW / 2 }
}

/**
 * Viewport when the whole path does not fit at a readable zoom: keep the zoom readable and put
 * the focused card on the side the path flows toward, so as much of the path as possible stays
 * in view. Upstream paths grow to the left of the focus (focus at the right edge), downstream
 * paths to the right (focus at the left edge); both directions centre it.
 */
export function readableViewport(
  pane: { width: number; height: number },
  card: { x: number; y: number; width: number; height: number },
  zoom: number,
  direction: 'upstream' | 'downstream' | 'both' | string,
  margin = 24
): { x: number; y: number; zoom: number } {
  const y = pane.height / 2 - (card.y + card.height / 2) * zoom
  let x: number
  if (direction === 'upstream') x = pane.width - margin - (card.x + card.width) * zoom
  else if (direction === 'downstream') x = margin - card.x * zoom
  else x = pane.width / 2 - (card.x + card.width / 2) * zoom
  return { x, y, zoom }
}
