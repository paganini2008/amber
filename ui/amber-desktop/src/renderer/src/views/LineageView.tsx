import { useVirtualizer } from '@tanstack/react-virtual'
import {
  BaseEdge,
  Controls,
  EdgeLabelRenderer,
  Handle,
  Position,
  ReactFlow,
  ReactFlowProvider,
  getBezierPath,
  useReactFlow,
  useStore as useFlowStore,
  type ReactFlowState,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps
} from '@xyflow/react'
import '@xyflow/react/dist/base.css'
import clsx from 'clsx'
import { Box, CircleQuestionMark, Layers, Maximize2, Rows3, ScanSearch, Search, Table2, X } from 'lucide-react'
import { memo, useEffect, useMemo, useRef, useState } from 'react'
import { usePlaybackView, useReducedMotion } from '@/hooks'
import { layoutFlow, type LayoutResult } from '@/lib/layout'
import {
  ownerKindLabel,
  ownerTitle,
  pickableColumns,
  traceLineage,
  transformLabel,
  type LineageDirection,
  type LineageIndex,
  type LineageTrace
} from '@/lib/lineage'
import { buildLineageView, readableViewport, statementTrace, type LineageCard, type LineageViewModel } from '@/lib/lineageView'
import { useStore } from '@/store'
import { IconButton, NotAvailable, Spinner, Switch, inputClass } from '@/components/ui'
import { FindReferencesButton, onReferenceContextMenu } from '@/components/References'

/** Owners whose columns the engine can look up with `references`. */
const TABLE_LIKE = new Set(['TABLE', 'TEMP_TABLE', 'TABLE_VARIABLE', 'VIEW', 'MATERIALIZED_VIEW'])

/** The last laid-out lineage graph and its trace, for SVG/PNG/Mermaid export. */
export const lineageSnapshot: { current: { model: LineageViewModel; layout: LayoutResult; trace: LineageTrace; title: string } | null } = {
  current: null
}

/** The trace the Lineage view shows, shared with export. */
export function useLineageTrace(): { trace: LineageTrace | null; mode: 'column' | 'statement' | 'none' } {
  const lineage = useStore((s) => s.lineage)
  const focus = useStore((s) => s.lineageFocus)
  const direction = useStore((s) => s.lineageDirection)
  const indirect = useStore((s) => s.lineageIndirect)
  const playback = usePlaybackView()
  return useMemo(() => {
    if (!lineage) return { trace: null, mode: 'none' }
    const column = focus !== null && lineage.nodes.has(focus) ? traceLineage(lineage, focus, { direction, indirect }) : null
    if (playback.active && playback.current !== null) {
      // Playback lights each step's edges: keep the column's path when the step is on it,
      // otherwise show what the step itself writes.
      const current = playback.current
      if (column && [...column.edges].some((e) => lineage.edges[e]!.statement === current)) return { trace: column, mode: 'column' }
      return { trace: statementTrace(lineage, current), mode: 'statement' }
    }
    if (column) return { trace: column, mode: 'column' }
    return { trace: null, mode: 'none' }
  }, [lineage, focus, direction, indirect, playback])
}

const KIND_ICON: Record<string, typeof Box> = {
  TABLE: Table2,
  TEMP_TABLE: Layers,
  TABLE_VARIABLE: Layers,
  VIEW: ScanSearch,
  MATERIALIZED_VIEW: ScanSearch,
  CTE: Box,
  SUBQUERY: Box,
  RESULT: Rows3,
  UNKNOWN: CircleQuestionMark
}

type CardData = { card: LineageCard; focus: number | null; dimAll: boolean }

const OwnerCard = memo(function OwnerCard({ data }: NodeProps<Node<CardData>>) {
  const { card, focus, dimAll } = data
  const Icon = KIND_ICON[card.kind] ?? Box
  const unknown = card.kind === 'UNKNOWN'
  const transient = card.kind === 'CTE' || card.kind === 'SUBQUERY' || card.kind === 'RESULT'
  return (
    <div
      className={clsx(
        'overflow-hidden rounded-[6px] border bg-surface',
        unknown ? 'border-dashed border-ink-muted' : transient ? 'border-line' : 'border-line-strong',
        transient && 'bg-surface-2',
        dimAll && 'recede'
      )}
      style={{ width: card.width, height: card.height }}
    >
      <div className="flex h-10 items-center gap-2 border-b border-line px-2.5">
        <Icon size={14} className="shrink-0 text-ink-muted" />
        <div className="min-w-0">
          <div className={clsx('truncate text-[12.5px] leading-4 text-ink', card.kind === 'RESULT' || unknown ? 'font-medium' : 'code')}>{card.title}</div>
          <div className={clsx('truncate text-xs leading-4', unknown ? 'text-ink' : 'text-ink-muted')}>{card.subtitle}</div>
        </div>
      </div>
      {card.rows.map((r) => (
        <div
          key={r.nodeId}
          className={clsx(
            'relative flex items-center px-2.5 text-[12px]',
            r.nodeId === focus ? 'bg-amber font-medium text-on-amber' : r.traced ? 'text-amber-ink' : 'text-ink-faint',
            r.rows && 'italic'
          )}
          style={{ height: 22 }}
        >
          <Handle id={`in-${r.nodeId}`} type="target" position={Position.Left} className="!left-0 !h-1 !w-1 !min-w-0 !border-0 !bg-transparent" />
          <span className={clsx('truncate', !r.rows && 'code')}>{r.label}</span>
          <Handle id={`out-${r.nodeId}`} type="source" position={Position.Right} className="!right-0 !h-1 !w-1 !min-w-0 !border-0 !bg-transparent" />
        </div>
      ))}
      {card.hidden > 0 ? <div className="px-2.5 text-xs leading-5 text-ink-faint">+{card.hidden} more columns</div> : null}
    </div>
  )
})

type EdgeData = { label: string; stroke: string; width: number; opacity: number; dashed: boolean; flowing: boolean; showLabel: boolean }

function LineageEdgeView(props: EdgeProps<Edge<EdgeData>>): React.JSX.Element {
  const { sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, data } = props
  const [path, labelX, labelY] = getBezierPath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition })
  const d = data!
  return (
    <>
      <BaseEdge
        path={path}
        className={d.flowing ? 'edge-flowing' : undefined}
        style={{ stroke: d.stroke, strokeWidth: d.width, opacity: d.opacity, strokeDasharray: d.dashed ? '5 4' : undefined }}
        interactionWidth={14}
      />
      {d.showLabel && d.label ? (
        <EdgeLabelRenderer>
          <div
            className="pointer-events-none absolute rounded-[3px] border border-line bg-surface px-1 text-xs leading-4 text-ink-muted"
            style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`, opacity: d.opacity }}
          >
            {d.label}
          </div>
        </EdgeLabelRenderer>
      ) : null}
    </>
  )
}

const MIN_READABLE_ZOOM = 0.8

/** True once React Flow has measured every card, so fitting uses real sizes. */
function allMeasured(s: ReactFlowState): boolean {
  if (s.nodeLookup.size === 0) return false
  for (const n of s.nodeLookup.values()) if (!n.measured?.width || !n.measured?.height) return false
  return true
}
const nodeTypes = { owner: OwnerCard }
const edgeTypes = { lineage: LineageEdgeView }

export function LineageView(): React.JSX.Element {
  const lineage = useStore((s) => s.lineage)
  if (!lineage) {
    return (
      <div className="p-8">
        <NotAvailable feature="Column lineage">Update the engine to see where each column's values come from.</NotAvailable>
      </div>
    )
  }
  return (
    <div className="flex h-full min-h-0">
      <ColumnPicker lineage={lineage} />
      <div className="flex min-w-0 flex-1 flex-col border-l border-line">
        <ReactFlowProvider>
          <LineageCanvas lineage={lineage} />
        </ReactFlowProvider>
      </div>
    </div>
  )
}

function ColumnPicker({ lineage }: { lineage: LineageIndex }): React.JSX.Element {
  const focus = useStore((s) => s.lineageFocus)
  const ownerFilter = useStore((s) => s.lineageOwnerFilter)
  const traceColumn = useStore((s) => s.traceColumn)
  const setLineageOptions = useStore((s) => s.setLineageOptions)
  const playbackActive = useStore((s) => s.playback.active)
  const [query, setQuery] = useState('')
  const parent = useRef<HTMLDivElement>(null)
  const all = useMemo(() => pickableColumns(lineage), [lineage])
  const list = useMemo(() => {
    const q = query.trim().toLowerCase()
    return all.filter((c) => (!ownerFilter || c.ownerKey === ownerFilter) && (!q || `${c.owner}.${c.column}`.toLowerCase().includes(q)))
  }, [all, query, ownerFilter])
  const virtualizer = useVirtualizer({ count: list.length, getScrollElement: () => parent.current, estimateSize: () => 40, overscan: 10 })

  // Start somewhere useful: the first computed column.
  useEffect(() => {
    if (focus === null && !playbackActive && !ownerFilter && all[0]) traceColumn(all[0].nodeId)
  }, [focus, all, traceColumn, playbackActive, ownerFilter])

  return (
    <div className="flex w-[220px] shrink-0 flex-col bg-surface">
      <div className="space-y-2 border-b border-line p-2.5">
        <div className="relative">
          <Search size={13} className="pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-ink-faint" />
          <input className={clsx(inputClass, 'pl-7')} placeholder="Find a column" aria-label="Find a column" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        {ownerFilter ? (
          <div className="flex items-center gap-1.5 text-xs whitespace-nowrap text-ink-muted">
            Columns of <span className="code truncate text-ink">{lineage.owners.get(ownerFilter)?.name ?? ownerFilter}</span>
            <button type="button" aria-label="Show all columns" className="ml-auto hover:text-ink" onClick={() => setLineageOptions({ ownerFilter: null })}>
              <X size={13} />
            </button>
          </div>
        ) : (
          <div className="text-xs text-ink-muted">{all.length.toLocaleString('en-US')} columns</div>
        )}
      </div>
      <div ref={parent} className="min-h-0 flex-1 overflow-auto" role="listbox" aria-label="Columns">
        {list.length === 0 ? <p className="p-3 text-sm text-ink-muted">No column matches.</p> : null}
        <div style={{ height: virtualizer.getTotalSize(), position: 'relative' }}>
          {virtualizer.getVirtualItems().map((item) => {
            const c = list[item.index]!
            const active = c.nodeId === focus
            return (
              <button
                key={c.nodeId}
                type="button"
                role="option"
                aria-selected={active}
                onClick={() => traceColumn(c.nodeId)}
                onContextMenu={TABLE_LIKE.has(c.ownerKind) ? onReferenceContextMenu({ table: c.owner, column: c.column }) : undefined}
                className={clsx(
                  'absolute left-0 flex w-full flex-col justify-center px-3 text-left',
                  active ? 'bg-amber-wash shadow-[inset_2px_0_0_var(--amber)]' : 'hover:bg-surface-hover'
                )}
                style={{ top: item.start, height: 40 }}
              >
                <span className="code truncate text-[12.5px] text-ink">{c.column}</span>
                <span className="truncate text-xs text-ink-muted">
                  {c.ownerKind === 'RESULT' || c.ownerKind === 'UNKNOWN' ? ownerTitle(lineage.owners.get(c.ownerKey)!) : `${c.owner} · ${ownerKindLabel(c.ownerKind)}`}
                </span>
              </button>
            )
          })}
        </div>
      </div>
    </div>
  )
}

const DIRECTIONS: { id: LineageDirection; label: string; hint: string }[] = [
  { id: 'upstream', label: 'Where it comes from', hint: 'Upstream: follow the value back to source tables' },
  { id: 'downstream', label: 'What it affects', hint: 'Downstream: what breaks if I change this?' },
  { id: 'both', label: 'Both', hint: 'Upstream and downstream' }
]

function LineageCanvas({ lineage }: { lineage: LineageIndex }): React.JSX.Element {
  const focus = useStore((s) => s.lineageFocus)
  const direction = useStore((s) => s.lineageDirection)
  const indirect = useStore((s) => s.lineageIndirect)
  const setLineageOptions = useStore((s) => s.setLineageOptions)
  const selectStatement = useStore((s) => s.selectStatement)
  const markRange = useStore((s) => s.markRange)
  const playback = usePlaybackView()
  const reduced = useReducedMotion()
  const flow = useReactFlow()
  const { trace, mode } = useLineageTrace()
  const [layout, setLayout] = useState<{ model: LineageViewModel; result: LayoutResult } | null>(null)

  const model = useMemo(() => (trace ? buildLineageView(lineage, trace, transformLabel) : null), [lineage, trace])
  const focusNode = focus !== null ? lineage.nodes.get(focus) : undefined
  const title = mode === 'statement' && playback.current !== null ? `Columns written by step ${playback.current + 1}` : focusNode ? `${focusNode.owner}.${focusNode.column}` : ''

  useEffect(() => {
    if (!model || model.cards.length === 0) return
    let cancelled = false
    const ownerEdges = new Map<string, { id: string; source: string; target: string }>()
    for (const e of model.edges) {
      if (e.sourceCard === e.targetCard) continue
      const id = `${e.sourceCard}->${e.targetCard}`
      ownerEdges.set(id, { id, source: e.sourceCard, target: e.targetCard })
    }
    void layoutFlow({ nodes: model.cards, edges: [...ownerEdges.values()] }).then((result) => {
      if (cancelled) return
      setLayout({ model, result })
      lineageSnapshot.current = { model, layout: result, trace: trace!, title }
    })
    return () => {
      cancelled = true
    }
    // The title follows the model.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [model])

  const laid = layout && layout.model === model ? layout : null

  // Fit the path; if that would make text unreadable, centre on the focused column instead.
  // Fitting waits until React Flow has measured the cards (fitting earlier used stale sizes and
  // left the path partly off-screen) and runs again when the pane gets its real size.
  const measured = useFlowStore(allMeasured)
  const [paneEl, setPaneEl] = useState<HTMLDivElement | null>(null)
  /** The path is wider than the pane at a readable zoom, so part of it is off-screen. */
  const [clipped, setClipped] = useState(false)
  const [paneSize, setPaneSize] = useState('')
  useEffect(() => {
    const el = paneEl
    if (!el) return
    const ro = new ResizeObserver(([entry]) => {
      const r = entry?.contentRect
      if (r && r.width > 0 && r.height > 0) setPaneSize(`${Math.round(r.width / 40)}x${Math.round(r.height / 40)}`)
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [paneEl])
  useEffect(() => {
    if (!laid || !measured || !paneSize) return
    const id = requestAnimationFrame(() => {
      void flow.fitView({ padding: 0.1, maxZoom: 1.1, duration: 0 }).then(() => {
        if (flow.getZoom() >= MIN_READABLE_ZOOM) {
          setClipped(false)
          return
        }
        const row = focus !== null ? laid.model.rowIndex.get(focus) : undefined
        const card = row ? laid.model.cards.find((c) => c.id === row.card) : laid.model.cards[0]
        const pos = card ? laid.result.positions.get(card.id) : undefined
        if (!card || !pos || !paneEl) return
        const pane = { width: paneEl.clientWidth, height: paneEl.clientHeight }
        void flow.setViewport(readableViewport(pane, { ...pos, width: card.width, height: card.height }, MIN_READABLE_ZOOM, mode === 'column' ? direction : 'both', 64), { duration: 0 })
        setClipped(true)
      })
    })
    return () => cancelAnimationFrame(id)
  }, [laid, flow, focus, measured, paneSize, paneEl, direction, mode])

  const nodes: Node<CardData>[] = useMemo(() => {
    if (!laid) return []
    return laid.model.cards.map((card) => ({
      id: card.id,
      type: 'owner',
      position: laid.result.positions.get(card.id) ?? { x: 0, y: 0 },
      data: { card, focus, dimAll: false },
      draggable: false,
      connectable: false
    }))
  }, [laid, focus])

  const edges: Edge<EdgeData>[] = useMemo(() => {
    if (!laid) return []
    return laid.model.edges.map((e) => {
      let stroke = 'var(--edge-rest)'
      let width = 1.2
      let opacity = 0.35
      let flowing = false
      if (e.traced) {
        stroke = 'var(--amber)'
        width = 2
        opacity = 1
      }
      if (playback.active) {
        if (e.edge.statement === playback.current) {
          stroke = 'var(--amber)'
          width = 2.25
          opacity = 1
          flowing = !reduced
        } else if (playback.played.has(e.edge.statement)) {
          stroke = e.traced ? 'var(--ink-muted)' : stroke
          width = 1.4
        } else {
          opacity = 0.15
        }
      }
      return {
        id: e.id,
        source: e.sourceCard,
        target: e.targetCard,
        sourceHandle: `out-${e.edge.from}`,
        targetHandle: `in-${e.edge.to}`,
        type: 'lineage',
        data: { label: e.label, stroke, width, opacity, dashed: e.edge.kind !== 'DIRECT', flowing, showLabel: (e.traced || flowing) && e.sourceCard !== e.targetCard }
      }
    })
  }, [laid, playback, reduced])

  const counts = model ? { columns: trace!.nodes.size, owners: model.cards.length } : null

  return (
    <>
      <div className="flex shrink-0 flex-wrap items-center gap-x-4 gap-y-2 border-b border-line px-4 py-2">
        <div className="min-w-0 basis-full">
          <div className="flex min-w-0 items-center gap-2">
            <div className="code truncate text-base font-medium text-ink">{title || 'Column lineage'}</div>
            {focusNode && TABLE_LIKE.has(focusNode.ownerKind) && mode !== 'statement' ? (
              <FindReferencesButton query={{ table: focusNode.owner, column: focusNode.column }} />
            ) : null}
          </div>
          {counts ? (
            <div className="text-xs text-ink-muted">
              {counts.columns} columns across {counts.owners} {counts.owners === 1 ? 'object' : 'objects'}
              {trace?.truncated ? ' · showing the first 600 columns of the path' : ''}
            </div>
          ) : null}
        </div>
        <div className="flex flex-wrap items-center gap-3 whitespace-nowrap">
          <div className="flex rounded-[6px] border border-line p-0.5" role="radiogroup" aria-label="Direction">
            {DIRECTIONS.map((d) => (
              <button
                key={d.id}
                type="button"
                role="radio"
                aria-checked={direction === d.id}
                title={d.hint}
                onClick={() => setLineageOptions({ direction: d.id })}
                className={clsx(
                  'h-6 rounded-[4px] px-2 text-xs',
                  direction === d.id ? 'bg-amber-wash font-medium text-ink' : 'text-ink-muted hover:text-ink'
                )}
              >
                {d.label}
              </button>
            ))}
          </div>
          <label className="flex items-center gap-2 text-xs text-ink-muted" title="Columns used in WHERE, JOIN and GROUP BY decide which rows arrive">
            <Switch checked={indirect} onChange={(v) => setLineageOptions({ indirect: v })} label="Include filters, joins and groups" />
            Filters, joins, groups
          </label>
          <IconButton
            label="Fit the whole path"
            onClick={() => {
              setClipped(false)
              void flow.fitView({ padding: 0.1, duration: reduced ? 0 : 200 })
            }}
          >
            <Maximize2 size={15} />
          </IconButton>
        </div>
      </div>
      <div ref={setPaneEl} className="relative min-h-0 flex-1">
        {mode === 'none' ? (
          <p className="p-6 text-sm text-ink-muted">What breaks if you change a column? Pick one on the left to see where its values come from and what they feed.</p>
        ) : model && model.cards.length === 0 ? (
          <p className="p-6 text-sm text-ink-muted">{mode === 'statement' ? 'This step moves no columns.' : 'No lineage for this column.'}</p>
        ) : !laid ? (
          <div className="flex h-full items-center justify-center gap-2 text-sm text-ink-muted">
            <Spinner /> Laying out
          </div>
        ) : null}
        {laid && clipped ? (
          <div className="pointer-events-none absolute top-2 left-1/2 z-10 -translate-x-1/2 rounded-[5px] border border-line bg-surface px-2.5 py-1 text-xs text-ink-muted shadow-[0_2px_10px_rgba(0,0,0,0.08)]">
            Part of the path is outside the view. Drag to pan, or use Fit for the whole path.
          </div>
        ) : null}
        {laid ? (
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            minZoom={0.05}
            maxZoom={2.5}
            nodesDraggable={false}
            nodesConnectable={false}
            onlyRenderVisibleElements={nodes.length > 120}
            proOptions={{ hideAttribution: true }}
            onEdgeClick={(_ev, edge) => {
              const e = laid.model.edges.find((x) => x.id === edge.id)
              if (!e) return
              selectStatement(e.edge.statement, 'view', false)
              markRange({ from: e.edge.range.start.offset, to: e.edge.range.end.offset })
            }}
          >
            <Controls showInteractive={false} position="bottom-right" />
          </ReactFlow>
        ) : null}
        {laid ? (
          <div className="pointer-events-none absolute bottom-3 left-3 flex items-center gap-3 rounded-[5px] border border-line bg-surface px-2 py-1 text-xs text-ink-muted">
            <span className="flex items-center gap-1">
              <svg width="22" height="6" aria-hidden>
                <line x1="0" y1="3" x2="22" y2="3" stroke="var(--amber)" strokeWidth="2" />
              </svg>
              value flows
            </span>
            <span className="flex items-center gap-1">
              <svg width="22" height="6" aria-hidden>
                <line x1="0" y1="3" x2="22" y2="3" stroke="var(--amber)" strokeWidth="2" strokeDasharray="5 4" />
              </svg>
              decides rows (filter, join, group)
            </span>
            <span>Click an edge to see its SQL</span>
          </div>
        ) : null}
      </div>
    </>
  )
}
