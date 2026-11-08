import {
  Controls,
  Handle,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Edge,
  type Node,
  type NodeProps
} from '@xyflow/react'
import '@xyflow/react/dist/base.css'
import clsx from 'clsx'
import { Archive, Box, Database, Focus, Layers, Maximize2, Shrink } from 'lucide-react'
import { memo, useEffect, useMemo, useRef, useState } from 'react'
import { usePlaybackView, useReducedMotion, useTrace } from '@/hooks'
import { buildFlowView, type FlowNode, type FlowView } from '@/lib/dataflow'
import { KindIcon } from '@/lib/kinds'
import { layoutFlow, type LayoutResult } from '@/lib/layout'
import { t, useLanguage } from '@/i18n'
import { useStore } from '@/store'
import { Button, IconButton, Spinner } from '@/components/ui'
import { onReferenceContextMenu } from '@/components/References'

/** The last laid-out graph, used by SVG/PNG export so it matches what is on screen. */
export const flowSnapshot: { current: { view: FlowView; layout: LayoutResult } | null } = { current: null }

type NodeData = { node: FlowNode; state: 'lit' | 'selected' | 'rest' | 'recede' | 'current' | 'future' }

const ROLE_ICON: Record<string, typeof Database> = { SOURCE: Database, INTERMEDIATE: Layers, TARGET: Archive, STRUCTURE: Box }

const ObjectNode = memo(function ObjectNode({ data }: NodeProps<Node<NodeData>>) {
  const { node, state } = data
  const Icon = ROLE_ICON[node.role ?? ''] ?? Box
  return (
    <div
      className={clsx(
        'flex h-[46px] items-center gap-2 rounded-[6px] border bg-surface px-2.5',
        state === 'selected' && 'border-amber shadow-[0_0_0_2px_var(--amber-wash-strong)]',
        state === 'lit' && 'border-amber',
        (state === 'rest' || state === 'current' || state === 'future') && 'border-line-strong',
        (state === 'recede' || state === 'future') && 'recede'
      )}
      style={{ width: node.width }}
    >
      <Handle type="target" position={Position.Left} className="!h-1.5 !w-1.5 !min-w-0 !border-0 !bg-transparent" />
      <Icon size={15} strokeWidth={1.75} className={state === 'selected' || state === 'lit' ? 'text-amber-ink' : 'text-ink-muted'} />
      <div className="min-w-0 flex-1">
        <div className={clsx('code truncate text-[12.5px] leading-4', state === 'selected' || state === 'lit' ? 'text-amber-ink' : 'text-ink')}>{node.label}</div>
        <div className="truncate text-xs leading-4 text-ink-muted">{node.sublabel}</div>
      </div>
      <Handle type="source" position={Position.Right} className="!h-1.5 !w-1.5 !min-w-0 !border-0 !bg-transparent" />
    </div>
  )
})

const StatementNode = memo(function StatementNode({ data }: NodeProps<Node<NodeData>>) {
  const { node, state } = data
  const conf = node.confidence ?? 'INFERRED'
  return (
    <div
      className={clsx(
        'flex h-[46px] items-center gap-2 rounded-[3px] border-[1.5px] bg-surface-2 px-2.5',
        conf === 'PARTIAL' ? 'border-dashed' : conf === 'UNRESOLVED' ? 'border-dotted' : 'border-solid',
        state === 'selected' || state === 'current' ? 'border-amber bg-amber-wash' : state === 'lit' ? 'border-amber' : 'border-line',
        (state === 'recede' || state === 'future') && 'recede'
      )}
      style={{ width: node.width }}
    >
      <Handle type="target" position={Position.Left} className="!h-1.5 !w-1.5 !min-w-0 !border-0 !bg-transparent" />
      <KindIcon kind={node.kind ?? ''} className={state === 'rest' || state === 'recede' ? 'text-ink-muted' : 'text-amber-ink'} />
      <div className="min-w-0 flex-1">
        <div className="truncate text-[12.5px] leading-4 font-medium text-ink">{node.label}</div>
        <div className="truncate text-xs leading-4 text-ink-muted">{node.sublabel}</div>
      </div>
      <Handle type="source" position={Position.Right} className="!h-1.5 !w-1.5 !min-w-0 !border-0 !bg-transparent" />
    </div>
  )
})

const GroupNode = memo(function GroupNode({ data }: NodeProps<Node<NodeData>>) {
  const { node, state } = data
  useLanguage()
  return (
    <div className="relative" style={{ width: node.width }} title={t('dataflow.group.openHint')}>
      <div className="absolute inset-0 translate-x-[4px] translate-y-[4px] rounded-[3px] border border-line bg-surface-2" aria-hidden />
      <div
        className={clsx(
          'relative flex h-[46px] items-center gap-2 rounded-[3px] border-[1.5px] bg-surface-2 px-2.5',
          state === 'selected' || state === 'current' ? 'border-amber bg-amber-wash' : state === 'lit' ? 'border-amber' : 'border-line-strong',
          (state === 'recede' || state === 'future') && 'recede'
        )}
      >
        <Handle type="target" position={Position.Left} className="!h-1.5 !w-1.5 !min-w-0 !border-0 !bg-transparent" />
        <Layers size={14} className="text-ink-muted" />
        <div className="min-w-0 flex-1">
          <div className="truncate text-[12.5px] leading-4 font-medium text-ink">{node.label}</div>
          <div className="truncate text-xs leading-4 text-ink-muted">{node.sublabel}</div>
        </div>
        <Handle type="source" position={Position.Right} className="!h-1.5 !w-1.5 !min-w-0 !border-0 !bg-transparent" />
      </div>
    </div>
  )
})

const nodeTypes = { object: ObjectNode, statement: StatementNode, group: GroupNode }

export function DataFlowView(): React.JSX.Element {
  return (
    <ReactFlowProvider>
      <DataFlowCanvas />
    </ReactFlowProvider>
  )
}

function DataFlowCanvas(): React.JSX.Element {
  const index = useStore((s) => s.index)!
  const graph = useStore((s) => s.graph)!
  const selection = useStore((s) => s.selection)
  const selectionSource = useStore((s) => s.selectionSource)
  const select = useStore((s) => s.select)
  const selectStatement = useStore((s) => s.selectStatement)
  const trace = useTrace()
  const playback = usePlaybackView()
  const reducedMotion = useReducedMotion()
  const flow = useReactFlow()
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [layout, setLayout] = useState<{ view: FlowView; result: LayoutResult } | null>(null)
  const [error, setError] = useState<string | null>(null)

  // Node labels and widths depend on the UI language.
  const language = useLanguage()
  const view = useMemo(() => buildFlowView(index, graph, expanded), [index, graph, expanded, language]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    let cancelled = false
    setError(null)
    layoutFlow(view)
      .then((result) => {
        if (cancelled) return
        setLayout({ view, result })
        flowSnapshot.current = { view, layout: result }
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      })
    return () => {
      cancelled = true
    }
  }, [view])

  const laidOut = layout?.view === view ? layout : null

  // Map statement-level trace to visible nodes (groups light when any member is lit).
  const nodes: Node<NodeData>[] = useMemo(() => {
    if (!laidOut) return []
    return view.nodes.map((n) => {
      const pos = laidOut.result.positions.get(n.id) ?? { x: 0, y: 0 }
      let state: NodeData['state'] = 'rest'
      const isSelected =
        (selection?.type === 'object' && n.objectKey === selection.key) ||
        (selection?.type === 'statement' && n.type !== 'object' && n.statementIds.includes(selection.id))
      if (playback.active) {
        if (n.type === 'object') {
          const touched = n.statementIds.some((s) => playback.played.has(s)) || graph.readersOf.get(n.objectKey!)?.some((s) => playback.played.has(s))
          const now = playback.current !== null && (graph.readsOf.get(playback.current)?.has(n.objectKey!) || graph.writesOf.get(playback.current)?.has(n.objectKey!))
          state = now ? 'lit' : touched ? 'rest' : 'future'
        } else {
          state = playback.current !== null && n.statementIds.includes(playback.current) ? 'current' : n.statementIds.some((s) => playback.played.has(s)) ? 'rest' : 'future'
        }
      } else if (isSelected) {
        state = 'selected'
      } else if (trace) {
        const lit = n.type === 'object' ? trace.objects.has(n.objectKey!) : n.statementIds.some((s) => trace.statements.has(s))
        state = lit ? 'lit' : 'recede'
      }
      return { id: n.id, type: n.type, position: pos, data: { node: n, state }, draggable: false, connectable: false, selectable: true }
    })
  }, [laidOut, view, selection, trace, playback, graph])

  const edges: Edge[] = useMemo(() => {
    if (!laidOut) return []
    return view.edges.map((e) => {
      let stroke = 'var(--edge-rest)'
      let width = 1.25
      let opacity = 1
      let flowing = false
      if (playback.active) {
        const now = playback.current !== null && e.statementIds.includes(playback.current)
        const done = e.statementIds.some((s) => playback.played.has(s))
        if (now) {
          stroke = 'var(--amber)'
          width = 2.25
          flowing = !reducedMotion
        } else if (done) {
          stroke = 'var(--ink-muted)'
        } else {
          opacity = 0.18
        }
      } else if (trace) {
        const lit = e.members.some((m) => trace.edges.has(m))
        if (lit) {
          stroke = 'var(--amber)'
          width = 2.25
        } else {
          opacity = 0.2
        }
      }
      const dash = e.confidence === 'PARTIAL' ? '6 4' : e.confidence === 'UNRESOLVED' ? '2 4' : undefined
      return {
        id: e.id,
        source: e.source,
        target: e.target,
        type: 'default',
        className: flowing ? 'edge-flowing' : undefined,
        style: { stroke, strokeWidth: width, opacity, strokeDasharray: dash },
        // Reached only through a call: a small text label (dashes already mean partial confidence).
        ...(e.via
          ? {
              label: t('dataflow.edge.via', { routine: shortRoutine(e.via) }),
              labelStyle: { fill: 'var(--ink-muted)', fontSize: 11, fontFamily: 'var(--font-code)' },
              labelBgStyle: { fill: 'var(--surface)', fillOpacity: 0.92 },
              labelBgPadding: [4, 2] as [number, number],
              labelBgBorderRadius: 3
            }
          : {}),
        focusable: false,
        selectable: false
      }
    })
  }, [laidOut, view, trace, playback, reducedMotion])

  // Bring the selection into view when it came from elsewhere.
  const focusId = useMemo(() => {
    if (!laidOut) return null
    if (playback.active && playback.current !== null) return nodeFor(view, playback.current)
    if (selection?.type === 'object') return `o:${selection.key}`
    if (selection?.type === 'statement') return nodeFor(view, selection.id)
    return null
  }, [laidOut, view, selection, playback])

  // On a new layout: show the whole graph, or zoom to the selection when there is one.
  const fittedFor = useRef<unknown>(null)
  useEffect(() => {
    if (!laidOut) return
    const first = fittedFor.current !== laidOut
    fittedFor.current = laidOut
    const pos = focusId ? laidOut.result.positions.get(focusId) : undefined
    const n = focusId ? view.nodes.find((x) => x.id === focusId) : undefined
    const duration = reducedMotion || first ? 0 : 200
    const id = requestAnimationFrame(() => {
      if (pos && n && (first || selectionSource !== 'view')) {
        void flow.setCenter(pos.x + n.width / 2, pos.y + n.height / 2, { zoom: first ? 0.9 : Math.max(flow.getZoom(), 0.8), duration })
      } else if (first) {
        void flow.fitView({ padding: 0.12, duration, maxZoom: 1.2 })
      }
    })
    return () => cancelAnimationFrame(id)
  }, [focusId, laidOut, view, flow, selectionSource, reducedMotion])

  const groupCount = view.nodes.filter((n) => n.type === 'group').length

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex h-10 shrink-0 items-center gap-2 border-b border-line px-4 text-sm text-ink-muted">
        <span className="shrink-0 whitespace-nowrap">
          {t('dataflow.summary', { objects: graph.objects.length, statements: graph.statements.length })}
        </span>
        {view.grouped ? (
          <span className="ml-2 flex min-w-0 items-center gap-2">
            {/* One line at any width; the whole sentence is in the tooltip. */}
            <span className="min-w-0 truncate" title={t('dataflow.grouped.hint', { count: groupCount })}>
              {t('dataflow.grouped.label', { count: groupCount })}
            </span>
            {expanded.size ? (
              <Button size="sm" variant="ghost" onClick={() => setExpanded(new Set())}>
                <Shrink size={13} /> {t('dataflow.regroup')}
              </Button>
            ) : null}
          </span>
        ) : null}
        <div className="ml-auto flex items-center gap-0.5">
          <IconButton
            label={t('dataflow.focus')}
            disabled={!focusId}
            onClick={() => {
              const pos = focusId ? laidOut?.result.positions.get(focusId) : undefined
              if (pos) void flow.setCenter(pos.x + 100, pos.y + 23, { zoom: 1, duration: reducedMotion ? 0 : 200 })
            }}
          >
            <Focus size={15} />
          </IconButton>
          <IconButton label={t('dataflow.fit')} onClick={() => void flow.fitView({ padding: 0.12, duration: reducedMotion ? 0 : 200 })}>
            <Maximize2 size={15} />
          </IconButton>
        </div>
      </div>
      <div className="relative min-h-0 flex-1 overflow-hidden" aria-label={t('dataflow.graphLabel')}>
        {graph.statements.length === 0 ? (
          <p className="px-5 py-6 text-sm text-ink-muted">{t('dataflow.empty')}</p>
        ) : (
          <>
            {!laidOut && !error ? (
              <div className="absolute inset-0 z-10 flex items-center justify-center gap-2 text-sm text-ink-muted">
                <Spinner /> {t('dataflow.layingOut', { count: view.nodes.length })}
              </div>
            ) : null}
            {error ? <p className="px-5 py-6 text-sm text-risk-high">{t('dataflow.layoutFailed', { error })}</p> : null}
            <ReactFlow
              nodes={nodes}
              edges={edges}
              nodeTypes={nodeTypes}
              minZoom={0.05}
              maxZoom={2.5}
              nodesDraggable={false}
              nodesConnectable={false}
              onlyRenderVisibleElements={view.nodes.length > 150}
              attributionPosition="bottom-left"
              onNodeClick={(_e, node) => {
                const n = (node.data as NodeData).node
                if (n.type === 'object') select({ type: 'object', key: n.objectKey! }, 'view')
                else selectStatement(n.statementIds[0]!, 'view')
              }}
              onNodeContextMenu={(e, node) => {
                const n = (node.data as NodeData).node
                const l = n.type === 'object' && n.objectKey ? useStore.getState().index?.lifecycleByKey.get(n.objectKey) : undefined
                if (l) onReferenceContextMenu({ object: l.name })(e)
              }}
              onNodeDoubleClick={(_e, node) => {
                const n = (node.data as NodeData).node
                if (n.type === 'group') setExpanded(new Set([...expanded, node.id.slice(2)]))
              }}
              onPaneClick={() => select(null, 'view')}
            >
              <Controls showInteractive={false} position="bottom-right" />
            </ReactFlow>
          </>
        )}
      </div>
    </div>
  )
}

function nodeFor(view: FlowView, statementId: number): string | null {
  const g = view.groupOf.get(statementId)
  if (g) return `g:${g}`
  const id = `s:${statementId}`
  return view.nodes.some((n) => n.id === id) ? id : null
}


/** "dw.p_load" → "p_load": the edge label stays short; the full name is in the statement node. */
function shortRoutine(name: string): string {
  return name.split('.').pop() || name
}
