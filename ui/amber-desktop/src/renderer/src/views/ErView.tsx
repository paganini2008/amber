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
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps
} from '@xyflow/react'
import '@xyflow/react/dist/base.css'
import clsx from 'clsx'
import {
  ChevronDown,
  ChevronRight,
  Copy,
  Crosshair,
  KeyRound,
  Link2,
  Maximize2,
  Search,
  SquarePlus,
  Table2,
  Layers,
  ScanSearch,
  X
} from 'lucide-react'
import { memo, useEffect, useMemo, useRef, useState } from 'react'
import type { ErEntity, ErModel, ErRelationship } from '@shared/protocol'
import { useReducedMotion } from '@/hooks'
import { t, tOr, useLanguage } from '@/i18n'
import {
  bySchema,
  cardModel,
  CARD,
  DEFAULT_FILTERS,
  dashFor,
  ends,
  EVIDENCE,
  filterEr,
  relationshipsOf,
  searchEntities,
  type Confidence,
  type ErCardModel,
  type ErFilters
} from '@/lib/er'
import { objectKindText } from '@/lib/kinds'
import { layoutFlow, type LayoutResult } from '@/lib/layout'
import { useStore } from '@/store'
import { FindReferencesButton } from '@/components/References'
import { IconButton, NotAvailable, Select, Spinner, Switch, inputClass } from '@/components/ui'

/** The last laid-out diagram, for SVG/PNG export. */
export const erSnapshot: {
  current: {
    cards: ErCardModel[]
    relationships: ErRelationship[]
    layout: LayoutResult
  } | null
} = { current: null }

const KIND_ICON: Record<string, typeof Table2> = {
  TABLE: Table2,
  TEMP_TABLE: Layers,
  TABLE_VARIABLE: Layers,
  VIEW: ScanSearch,
  MATERIALIZED_VIEW: ScanSearch
}

type CardData = {
  card: ErCardModel
  selected: boolean
  related: boolean
  dim: boolean
  match: boolean
  onToggle: (key: string) => void
  expanded: boolean
}

const EntityCard = memo(function EntityCard({ data }: NodeProps<Node<CardData>>) {
  useLanguage()
  const { card, selected, related, dim, match, expanded, onToggle } = data
  const e = card.entity
  const Icon = KIND_ICON[e.kind] ?? Table2
  const hasRest = e.columns.some((c) => !(c.pk || c.fk || c.joined))
  return (
    <div
      className={clsx(
        'er-card overflow-hidden rounded-[6px] border bg-surface',
        selected ? 'er-card-selected' : related ? 'border-amber' : 'border-line-strong',
        match && !selected && 'er-card-match',
        dim && 'recede'
      )}
      style={{ width: card.width, height: card.height }}
    >
      <Handle type="target" position={Position.Left} className="!left-0 !h-1 !w-1 !min-w-0 !border-0 !bg-transparent" />
      <Handle type="source" position={Position.Right} className="!right-0 !h-1 !w-1 !min-w-0 !border-0 !bg-transparent" />
      <div className="er-card-head flex items-center gap-2 border-b border-line px-2.5" style={{ height: CARD.HEADER }}>
        <Icon size={14} className="shrink-0 text-ink-muted" aria-hidden />
        <div className="min-w-0 flex-1">
          <div className="code truncate text-[12.5px] leading-4 text-ink" title={e.name}>
            {e.name}
          </div>
          <div className="flex items-center gap-1 truncate text-xs leading-4 text-ink-muted">
            {objectKindText(e.kind, true)}
            {e.createdInScript ? (
              <span className="flex items-center gap-0.5 text-amber-ink" title={t('er.card.inScript')}>
                <SquarePlus size={11} aria-hidden />
                <span className="sr-only">{t('er.card.inScript')}</span>
              </span>
            ) : null}
          </div>
        </div>
      </div>
      {e.columns.length === 0 ? <div className="px-2.5 text-xs leading-[22px] text-ink-faint italic">{t('er.card.noColumns')}</div> : null}
      {card.rows.map((c) => (
        <div
          key={c.name}
          className="flex items-center gap-1.5 px-2.5 text-[12px]"
          style={{ height: CARD.ROW }}
          title={tOr(`er.card.source.${c.source}`, '')}
        >
          {/* Fixed glyph gutter: the key role never rests on colour. */}
          <span className="flex w-7 shrink-0 items-center gap-0.5 text-ink-muted">
            {c.pk ? <KeyRound size={11} className="text-amber-ink" aria-label={t('er.card.pk')} /> : null}
            {c.fk ? <Link2 size={11} aria-label={t('er.card.fk')} /> : null}
            {!c.pk && !c.fk && c.joined ? <span className="er-joined-dot" aria-label={t('er.card.joined')} /> : null}
          </span>
          <span className={clsx('code min-w-0 flex-1 truncate', c.pk || c.fk || c.joined ? 'text-ink' : 'text-ink-faint')}>{c.name}</span>
          {c.type ? <span className="code shrink-0 truncate text-[11px] text-ink-faint">{c.type.toLowerCase()}</span> : null}
        </div>
      ))}
      {card.hidden > 0 || (expanded && hasRest) ? (
        <button
          type="button"
          className="nodrag flex w-full items-center gap-1 px-2.5 text-left text-xs text-ink-muted hover:text-ink"
          style={{ height: CARD.FOOTER }}
          onClick={(ev) => {
            ev.stopPropagation()
            onToggle(e.key)
          }}
        >
          {expanded ? <ChevronDown size={11} aria-hidden /> : <ChevronRight size={11} aria-hidden />}
          {expanded ? t('er.card.less') : t('er.card.more', { count: card.hidden })}
        </button>
      ) : null}
    </div>
  )
})

type EdgeData = {
  rel: ErRelationship
  state: 'rest' | 'active' | 'dim'
  showLabel: boolean
}

const MARK = 11

/** Crow's foot ("many"), a bar ("one") or an arrow at one end of a horizontal line. */
function endMark(kind: 'many' | 'one' | 'arrow' | 'none', x: number, y: number, side: 'left' | 'right'): string {
  const dir = side === 'right' ? 1 : -1
  // (x, y) is where the line meets the card; marks sit just outside it.
  if (kind === 'many') return `M${x + dir * MARK},${y} L${x},${y - 6} M${x + dir * MARK},${y} L${x},${y} M${x + dir * MARK},${y} L${x},${y + 6}`
  if (kind === 'one') return `M${x + dir * 7},${y - 6} L${x + dir * 7},${y + 6}`
  if (kind === 'arrow') return `M${x + dir * 9},${y - 5} L${x},${y} L${x + dir * 9},${y + 5}`
  return ''
}

function RelationshipEdge(props: EdgeProps<Edge<EdgeData>>): React.JSX.Element {
  const { sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, data } = props
  const d = data!
  const [path, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition
  })
  const e = ends(d.rel)
  const stroke = d.state === 'active' ? 'var(--amber)' : 'var(--er-line)'
  const width = d.state === 'active' ? 2 : 1.4
  const opacity = d.state === 'dim' ? 0.18 : 1
  const marks = [endMark(e.from, sourceX, sourceY, 'right'), endMark(e.to, targetX, targetY, 'left')].filter(Boolean).join(' ')
  const tag = d.rel.evidence === 'NAME_MATCH' || d.rel.evidence === 'DATA_FLOW' ? t(`er.evidence.${d.rel.evidence}`) : null
  return (
    <>
      <BaseEdge
        path={path}
        interactionWidth={14}
        style={{
          stroke,
          strokeWidth: width,
          opacity,
          strokeDasharray: dashFor(d.rel.confidence),
          strokeLinecap: d.rel.confidence === 'POSSIBLE' ? 'round' : undefined
        }}
      />
      {marks ? (
        <path d={marks} fill="none" stroke={stroke} strokeWidth={width} opacity={opacity} strokeLinecap="round" strokeLinejoin="round" />
      ) : null}
      {tag && d.showLabel ? (
        <EdgeLabelRenderer>
          <div
            className="pointer-events-none absolute rounded-[3px] border border-line bg-surface px-1 text-[11px] leading-4 text-ink-muted"
            style={{
              transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
              opacity
            }}
          >
            {tag}
          </div>
        </EdgeLabelRenderer>
      ) : null}
    </>
  )
}

const nodeTypes = { entity: EntityCard }
const edgeTypes = { relationship: RelationshipEdge }

export function ErView(): React.JSX.Element {
  const analysis = useStore((s) => s.analysis)
  const er = useStore((s) => s.er)
  const load = useStore((s) => s.loadEr)
  useEffect(() => {
    if (analysis && analysis.status !== 'FAILED' && er.status === 'idle') void load()
  }, [analysis, er.status, load])

  if (!analysis || analysis.status === 'FAILED') return <p className="p-6 text-sm text-ink-muted">{t('er.analyzeFirst')}</p>
  if (er.status === 'unavailable') {
    return (
      <div className="p-8">
        <NotAvailable feature={t('er.title')}>{t('er.unavailable')}</NotAvailable>
      </div>
    )
  }
  if (er.status === 'error') return <p className="p-6 text-sm text-risk-high">{t('er.error', { message: er.error ?? '' })}</p>
  if (!er.result) {
    return (
      <div className="flex h-full items-center justify-center gap-2 text-sm text-ink-muted">
        <Spinner /> {t('er.loading')}
      </div>
    )
  }
  return (
    <ReactFlowProvider>
      <ErCanvas model={er.result} refreshing={er.status === 'loading'} />
    </ReactFlowProvider>
  )
}

type Selection = { type: 'entity'; key: string } | { type: 'relationship'; id: string } | null

function ErCanvas({ model, refreshing }: { model: ErModel; refreshing: boolean }): React.JSX.Element {
  useLanguage()
  const flow = useReactFlow()
  const reduced = useReducedMotion()
  const includeDataFlow = useStore((s) => s.er.includeDataFlow)
  const upgraded = useStore((s) => s.er.upgraded)
  const setErOptions = useStore((s) => s.setErOptions)
  const [filters, setFilters] = useState<ErFilters>(DEFAULT_FILTERS)
  const [query, setQuery] = useState('')
  const [selection, setSelection] = useState<Selection>(null)
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set())
  const [showIsolated, setShowIsolated] = useState(false)
  const [laid, setLaid] = useState<{
    cards: ErCardModel[]
    relationships: ErRelationship[]
    layout: LayoutResult
  } | null>(null)

  const view = useMemo(() => filterEr(model, filters), [model, filters])
  const shown = useMemo(() => bySchema(showIsolated ? [...view.entities, ...view.isolated] : view.entities), [view, showIsolated])
  const cards = useMemo(() => shown.map((e) => cardModel(e, expanded.has(e.key))), [shown, expanded])
  const matches = useMemo(() => new Set(searchEntities(model.entities, query).map((e) => e.key)), [model, query])

  // Drop a selection the filters removed.
  useEffect(() => {
    if (selection?.type === 'relationship' && !view.relationships.some((r) => r.id === selection.id)) setSelection(null)
    if (selection?.type === 'entity' && !model.entities.some((e) => e.key === selection.key)) setSelection(null)
  }, [view, model, selection])

  useEffect(() => {
    let cancelled = false
    if (cards.length === 0) {
      setLaid({
        cards,
        relationships: view.relationships,
        layout: { positions: new Map(), width: 0, height: 0 }
      })
      return
    }
    const ids = new Set(cards.map((c) => c.entity.key))
    const relationships = view.relationships.filter((r) => ids.has(r.from.entity) && ids.has(r.to.entity))
    void layoutFlow(
      {
        nodes: cards.map((c) => ({
          id: c.entity.key,
          width: c.width,
          height: c.height
        })),
        edges: relationships.map((r) => ({
          id: r.id,
          source: r.from.entity,
          target: r.to.entity
        }))
      },
      // Keep each schema's tables together (cards come sorted by schema) and leave room for the crow's feet.
      {
        'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
        'elk.layered.spacing.nodeNodeBetweenLayers': '96',
        'elk.spacing.nodeNode': '24'
      }
    ).then((layout) => {
      if (cancelled) return
      setLaid({ cards, relationships, layout })
      erSnapshot.current = { cards, relationships, layout }
    })
    return () => {
      cancelled = true
    }
  }, [cards, view.relationships])

  // Fit per new layout and whenever the canvas changes size (banner, strips, panels). Up to 30 tables a
  // diagram that is too wide opens at a readable 80 % from the left edge (where the "many" side is), but
  // only when it fits in height at that zoom; otherwise the whole diagram is fitted, so no table is cut.
  const paneRef = useRef<HTMLDivElement>(null)
  const selectedRef = useRef(false)
  selectedRef.current = selection !== null
  useEffect(() => {
    if (!laid || laid.cards.length === 0) return
    const fitDiagram = (): void => {
      const pane = paneRef.current?.getBoundingClientRect()
      const w = laid.layout.width
      const h = laid.layout.height
      const fit = pane && w && h ? Math.min((pane.width - 48) / w, (pane.height - 48) / h, 1.1) : 1
      if (pane && laid.cards.length <= 30 && fit < 0.8 && h * 0.8 <= pane.height - 48) {
        void flow.setViewport({ x: 24, y: Math.max(24, (pane.height - h * 0.8) / 2), zoom: 0.8 }, { duration: reduced ? 0 : 200 })
      } else {
        void flow.fitView({ padding: 0.12, duration: reduced ? 0 : 200, maxZoom: 1.1 })
      }
    }
    let timer = setTimeout(fitDiagram, 30)
    let last = paneRef.current?.getBoundingClientRect()
    const ro = new ResizeObserver(() => {
      const now = paneRef.current?.getBoundingClientRect()
      if (!now || (last && Math.abs(now.width - last.width) < 2 && Math.abs(now.height - last.height) < 2)) return
      last = now
      if (selectedRef.current) return // a selection keeps its own framing
      clearTimeout(timer)
      timer = setTimeout(fitDiagram, 120)
    })
    if (paneRef.current) ro.observe(paneRef.current)
    return () => {
      clearTimeout(timer)
      ro.disconnect()
    }
  }, [laid, flow, reduced])

  const activeEntity = selection?.type === 'entity' ? selection.key : null
  const activeRel = selection?.type === 'relationship' ? (laid?.relationships.find((r) => r.id === selection.id) ?? null) : null
  const relatedRels = useMemo(
    () => new Set(activeEntity && laid ? relationshipsOf(laid.relationships, activeEntity).map((r) => r.id) : []),
    [activeEntity, laid]
  )
  const relatedEntities = useMemo(() => {
    const s = new Set<string>()
    if (!laid) return s
    for (const r of laid.relationships) {
      if (relatedRels.has(r.id) || r.id === activeRel?.id) {
        s.add(r.from.entity)
        s.add(r.to.entity)
      }
    }
    return s
  }, [laid, relatedRels, activeRel])
  const focusing = selection !== null

  // Bring the selection and its neighbours into view (the side panel narrows the canvas).
  useEffect(() => {
    if (!selection || !laid) return
    const keys = new Set(relatedEntities)
    if (selection.type === 'entity') keys.add(selection.key)
    const nodesInView = [...keys].map((k) => flow.getNode(k)).filter((n): n is NonNullable<typeof n> => Boolean(n))
    if (!nodesInView.length) return
    const timer = setTimeout(
      () =>
        void flow.fitView({
          nodes: nodesInView,
          padding: 0.25,
          duration: reduced ? 0 : 250,
          maxZoom: 1.1
        }),
      60
    )
    return () => clearTimeout(timer)
  }, [selection, laid, relatedEntities, flow, reduced])

  const toggle = (key: string): void =>
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })

  const nodes: Node<CardData>[] = useMemo(() => {
    if (!laid) return []
    return laid.cards.map((card) => {
      const key = card.entity.key
      const p = laid.layout.positions.get(key) ?? { x: 0, y: 0 }
      return {
        id: key,
        type: 'entity',
        position: p,
        data: {
          card,
          selected: key === activeEntity,
          related: relatedEntities.has(key),
          dim: focusing && key !== activeEntity && !relatedEntities.has(key),
          match: matches.has(key),
          expanded: expanded.has(key),
          onToggle: toggle
        },
        draggable: false
      }
    })
  }, [laid, activeEntity, relatedEntities, focusing, matches, expanded])

  const edges: Edge<EdgeData>[] = useMemo(() => {
    if (!laid) return []
    return laid.relationships.map((rel) => {
      const active = rel.id === activeRel?.id || relatedRels.has(rel.id)
      return {
        id: rel.id,
        source: rel.from.entity,
        target: rel.to.entity,
        type: 'relationship',
        data: {
          rel,
          state: active ? 'active' : focusing ? 'dim' : 'rest',
          showLabel: !focusing || active
        }
      }
    })
  }, [laid, activeRel, relatedRels, focusing])

  const focusMatch = (): void => {
    const first = [...matches][0]
    if (!first) return
    const n = flow.getNode(first)
    if (n)
      void flow.fitView({
        nodes: [n],
        padding: 0.6,
        duration: reduced ? 0 : 250,
        maxZoom: 1.2
      })
    setSelection({ type: 'entity', key: first })
  }

  const stats = model.stats
  const empty =
    model.entities.length === 0
      ? t('er.empty.noTables')
      : model.relationships.length === 0
        ? t('er.empty.noRelationships')
        : view.relationships.length === 0
          ? t('er.empty.filtered')
          : null
  const aroundEntity = filters.around ? model.entities.find((e) => e.key === filters.around!.entity) : undefined

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-2 border-b border-line px-4 py-2" role="toolbar" aria-label={t('er.title')}>
        <label className="relative w-52 min-w-[10rem]">
          <Search size={13} className="pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-ink-muted" aria-hidden />
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && focusMatch()}
            placeholder={t('er.search')}
            aria-label={t('er.search')}
            className={clsx(inputClass, 'w-full pl-7')}
          />
        </label>
        {query ? <span className="text-xs whitespace-nowrap text-ink-muted">{t('er.searchMatches', { count: matches.size })}</span> : null}
        <div className="flex flex-wrap items-center gap-1" role="group" aria-label={t('er.evidenceLabel')}>
          {EVIDENCE.filter((ev) => ev !== 'DATA_FLOW' || includeDataFlow).map((ev) => {
            const on = filters.evidence.has(ev)
            return (
              <button
                key={ev}
                type="button"
                aria-pressed={on}
                title={t(`er.evidenceLong.${ev}`)}
                onClick={() =>
                  setFilters((f) => {
                    const next = new Set(f.evidence)
                    if (on) next.delete(ev)
                    else next.add(ev)
                    return { ...f, evidence: next }
                  })
                }
                className={clsx(
                  'h-6 rounded-[4px] border px-2 text-xs whitespace-nowrap',
                  on ? 'border-line-strong bg-amber-wash text-ink' : 'border-line text-ink-faint line-through decoration-ink-faint/60'
                )}
              >
                {t(`er.evidence.${ev}`)}
              </button>
            )
          })}
        </div>
        <Select
          label={t('er.minConfidence')}
          value={filters.minConfidence}
          onChange={(v) => setFilters((f) => ({ ...f, minConfidence: v as Confidence }))}
          options={(['POSSIBLE', 'INFERRED', 'CERTAIN'] as const).map((c) => ({
            value: c,
            label: t(`er.confidenceAtLeast.${c}`)
          }))}
          className="w-44"
        />
        <label className="flex items-center gap-2 text-xs whitespace-nowrap text-ink-muted" title={t('er.dataFlowHint')}>
          <Switch checked={includeDataFlow} onChange={(v) => setErOptions({ includeDataFlow: v })} label={t('er.dataFlow')} />
          {t('er.dataFlow')}
        </label>
        <span className="flex-1" />
        {refreshing ? <Spinner size={12} /> : null}
        <IconButton
          label={t('er.copyMermaid')}
          onClick={() =>
            void navigator.clipboard.writeText(model.mermaid).then(
              () => useStore.getState().toast(t('er.mermaidCopied')),
              () => useStore.getState().toast(t('ai.suggestion.copyFailed'), 'error')
            )
          }
        >
          <Copy size={15} />
        </IconButton>
        <IconButton label={t('er.fit')} onClick={() => void flow.fitView({ padding: 0.12, duration: reduced ? 0 : 200 })}>
          <Maximize2 size={15} />
        </IconButton>
      </div>
      {aroundEntity ? (
        <div className="flex shrink-0 items-center gap-2 border-b border-line bg-amber-wash px-4 py-1 text-xs text-ink">
          <Crosshair size={13} aria-hidden />
          <span className="code">{t('er.around.showing', { name: aroundEntity.name })}</span>
          <Select
            label={t('er.around.label')}
            value={String(filters.around!.hops)}
            onChange={(v) =>
              setFilters((f) => ({
                ...f,
                around: f.around ? { ...f.around, hops: Number(v) } : null
              }))
            }
            options={[1, 2, 3].map((n) => ({
              value: String(n),
              label: t('er.around.hops', { count: n })
            }))}
            className="h-6 w-24"
          />
          <button type="button" className="ml-auto underline hover:text-ink" onClick={() => setFilters((f) => ({ ...f, around: null }))}>
            {t('er.around.clear')}
          </button>
        </div>
      ) : null}
      {upgraded > 0 ? (
        <div className="flex shrink-0 items-center gap-2 border-b border-line bg-surface-2 px-4 py-1 text-xs text-ink" role="status">
          <KeyRound size={13} className="text-amber-ink" aria-hidden />
          {t('er.upgraded', { count: upgraded })}
          <button type="button" className="ml-auto underline" onClick={() => setErOptions({ dismissUpgrade: true })}>
            {t('er.upgradedDismiss')}
          </button>
        </div>
      ) : null}
      {/* overflow-hidden: when the view is very short (bottom panel open), the strips are clipped instead of
          spilling onto the stats bar below. */}
      <div className="flex min-h-0 flex-1 overflow-hidden">
        {/* Strips above and below the canvas, never on top of it: fitting the view then cannot hide a table. */}
        <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
          {view.isolated.length > 0 ? (
            <div className="flex shrink-0 items-center gap-2 border-b border-line bg-surface px-3 py-1 text-xs text-ink-muted">
              <span className="shrink-0">{t('er.isolated.title', { count: view.isolated.length })}</span>
              {!showIsolated ? (
                <span className="code min-w-0 truncate text-[11.5px] text-ink-faint">{view.isolated.map((e) => e.name).join(', ')}</span>
              ) : null}
              <button type="button" className="ml-auto shrink-0 underline hover:text-ink" onClick={() => setShowIsolated((v) => !v)}>
                {showIsolated ? t('er.isolated.hide') : t('er.isolated.show')}
              </button>
            </div>
          ) : null}
          <div ref={paneRef} className="relative min-h-0 min-w-0 flex-1 overflow-hidden" aria-label={t('er.graphLabel')}>
            {empty && (!laid || laid.cards.length === 0) ? <p className="max-w-md p-6 text-sm text-ink-muted">{empty}</p> : null}
            {!laid ? (
              <div className="flex h-full items-center justify-center gap-2 text-sm text-ink-muted">
                <Spinner /> {t('er.layingOut')}
              </div>
            ) : laid.cards.length > 0 ? (
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
                attributionPosition="bottom-left"
                onNodeClick={(_e, node) => setSelection((s) => (s?.type === 'entity' && s.key === node.id ? null : { type: 'entity', key: node.id }))}
                onEdgeClick={(_e, edge) => setSelection({ type: 'relationship', id: edge.id })}
                onPaneClick={() => setSelection(null)}
              >
                <Controls showInteractive={false} position="bottom-right" />
              </ReactFlow>
            ) : null}
          </div>
          {laid && laid.cards.length > 0 ? <Legend dataFlow={includeDataFlow} /> : null}
        </div>
        {selection ? (
          <aside
            className="w-72 shrink-0 overflow-y-auto border-l border-line bg-surface px-3 py-3 text-sm"
            aria-label={activeRel ? t('er.panel.relationship') : t('er.panel.entity')}
          >
            {activeRel ? (
              <RelationshipPanel rel={activeRel} model={model} onClose={() => setSelection(null)} />
            ) : activeEntity ? (
              <EntityPanel
                entity={model.entities.find((e) => e.key === activeEntity)!}
                relationships={laid ? relationshipsOf(laid.relationships, activeEntity) : []}
                model={model}
                onClose={() => setSelection(null)}
                onAround={() =>
                  setFilters((f) => ({
                    ...f,
                    around: { entity: activeEntity, hops: f.around?.hops ?? 1 }
                  }))
                }
                onPick={(id) => setSelection({ type: 'relationship', id })}
              />
            ) : null}
          </aside>
        ) : null}
      </div>
      <div className="flex shrink-0 items-center gap-3 overflow-hidden border-t border-line px-4 py-1 text-xs whitespace-nowrap text-ink-muted">
        <span className="truncate">
          {t('er.stats', {
            entities: model.entities.length,
            relationships: model.relationships.length,
            joins: stats.joinEqualities,
            unresolved: stats.unresolved
          })}
        </span>
      </div>
    </div>
  )
}

function Legend({ dataFlow }: { dataFlow: boolean }): React.JSX.Element {
  const line = (dash: string | undefined, round = false): React.JSX.Element => (
    <svg width="26" height="8" aria-hidden>
      <line
        x1="1"
        y1="4"
        x2="25"
        y2="4"
        stroke="var(--er-line)"
        strokeWidth="1.6"
        strokeDasharray={dash}
        strokeLinecap={round ? 'round' : undefined}
      />
    </svg>
  )
  return (
    <div className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-0.5 border-t border-line bg-surface px-3 py-1 text-xs text-ink-muted">
      <span className="flex items-center gap-1">
        {line(undefined)}
        {t('er.legend.certain')}
      </span>
      <span className="flex items-center gap-1">
        {line(dashFor('INFERRED'))}
        {t('er.legend.inferred')}
      </span>
      <span className="flex items-center gap-1">
        {line(dashFor('POSSIBLE'), true)}
        {t('er.legend.possible')}
      </span>
      <span className="flex items-center gap-1">
        <svg width="34" height="14" aria-hidden>
          <path d="M1,7 L33,7 M12,7 L1,1 M12,7 L1,13 M26,1 L26,13" fill="none" stroke="var(--er-line)" strokeWidth="1.4" strokeLinecap="round" />
        </svg>
        {t('er.legend.many')} → {t('er.legend.one')}
      </span>
      {dataFlow ? (
        <span className="flex items-center gap-1">
          <svg width="26" height="12" aria-hidden>
            <path d="M1,6 L25,6 M17,1 L25,6 L17,11" fill="none" stroke="var(--er-line)" strokeWidth="1.4" strokeDasharray="6 4" />
          </svg>
          {t('er.legend.flow')}
        </span>
      ) : null}
      <span>{t('er.legend.hint')}</span>
    </div>
  )
}

function PanelHead({ title, kicker, onClose }: { title: string; kicker: string; onClose: () => void }): React.JSX.Element {
  return (
    <div className="mb-2 flex items-start gap-2">
      <div className="min-w-0 flex-1">
        <div className="text-xs text-ink-muted">{kicker}</div>
        <div className="code text-[13px] break-all text-ink">{title}</div>
      </div>
      <IconButton label={t('er.panel.close')} onClick={onClose}>
        <X size={14} />
      </IconButton>
    </div>
  )
}

function entityName(model: ErModel, key: string): string {
  return model.entities.find((e) => e.key === key)?.name ?? key.toLowerCase()
}

function RelationshipPanel({ rel, model, onClose }: { rel: ErRelationship; model: ErModel; onClose: () => void }): React.JSX.Element {
  const index = useStore((s) => s.index)
  const selectStatement = useStore((s) => s.selectStatement)
  const markRange = useStore((s) => s.markRange)
  const from = `${entityName(model, rel.from.entity)} (${rel.from.columns.join(', ')})`
  const to = `${entityName(model, rel.to.entity)} (${rel.to.columns.join(', ')})`
  return (
    <div className="space-y-3">
      <PanelHead kicker={t('er.panel.relationship')} title={`${from} → ${to}`} onClose={onClose} />
      <dl className="grid grid-cols-[96px_1fr] gap-x-2 gap-y-1 text-xs">
        <dt className="text-ink-muted">{t('er.panel.cardinality')}</dt>
        <dd className="text-ink">{tOr(`er.cardinality.${rel.cardinality}`, rel.cardinality)}</dd>
        <dt className="text-ink-muted">{t('er.panel.evidence')}</dt>
        <dd className="text-ink">{tOr(`er.evidenceLong.${rel.evidence}`, rel.evidence)}</dd>
        <dt className="text-ink-muted">{t('er.panel.confidence')}</dt>
        <dd className="text-ink">{tOr(`er.confidence.${rel.confidence}`, rel.confidence)}</dd>
        {rel.constraint ? (
          <>
            <dt className="text-ink-muted">{t('er.panel.constraint')}</dt>
            <dd className="code text-ink">{rel.constraint}</dd>
          </>
        ) : null}
      </dl>
      {rel.note ? (
        <p className="selectable text-xs text-ink-muted" title={t('er.panel.note')}>
          {rel.note}
        </p>
      ) : null}
      <div>
        <div className="mb-1 text-xs font-medium text-ink">{t('er.panel.occurrences', { count: rel.count })}</div>
        <ul className="space-y-0.5">
          {rel.occurrences.map((o, i) => {
            const s = index?.byId.get(o.statement)
            return (
              <li key={i}>
                <button
                  type="button"
                  title={t('er.panel.jump')}
                  className="flex w-full items-baseline gap-2 rounded-[4px] px-1.5 py-1 text-left text-xs hover:bg-surface-hover"
                  onClick={() => {
                    selectStatement(o.statement, 'view', true)
                    markRange({
                      from: o.range.start.offset,
                      to: o.range.end.offset
                    })
                  }}
                >
                  <span className="shrink-0 text-ink-muted">{t('er.panel.occurrenceLine', { line: o.range.start.line })}</span>
                  <span className="min-w-0 truncate text-ink">{s?.summary ?? ''}</span>
                </button>
              </li>
            )
          })}
        </ul>
        {rel.count > rel.occurrences.length ? (
          <p className="mt-1 text-xs text-ink-muted">
            {t('er.panel.occurrencesShown', {
              shown: rel.occurrences.length,
              count: rel.count
            })}
          </p>
        ) : null}
      </div>
    </div>
  )
}

function EntityPanel({
  entity,
  relationships,
  model,
  onClose,
  onAround,
  onPick
}: {
  entity: ErEntity
  relationships: ErRelationship[]
  model: ErModel
  onClose: () => void
  onAround: () => void
  onPick: (id: string) => void
}): React.JSX.Element {
  return (
    <div className="space-y-3">
      <PanelHead kicker={objectKindText(entity.kind)} title={entity.name} onClose={onClose} />
      <div className="flex flex-wrap gap-1.5">
        <button type="button" className="fix-button" onClick={onAround}>
          <Crosshair size={12} aria-hidden /> {t('er.panel.focus')}
        </button>
        <FindReferencesButton query={{ object: entity.name }} />
      </div>
      <p className="text-xs text-ink-muted">
        {t('er.panel.columns', { count: entity.columns.length })}
        {entity.createdInScript ? ` · ${t('er.card.inScript')}` : ''}
      </p>
      <div>
        <div className="mb-1 text-xs font-medium text-ink">{t('er.panel.relationships', { count: relationships.length })}</div>
        <ul className="space-y-0.5">
          {relationships.map((r) => {
            const outgoing = r.from.entity === entity.key
            const other = entityName(model, outgoing ? r.to.entity : r.from.entity)
            return (
              <li key={r.id}>
                <button
                  type="button"
                  className="flex w-full items-baseline gap-2 rounded-[4px] px-1.5 py-1 text-left text-xs hover:bg-surface-hover"
                  onClick={() => onPick(r.id)}
                >
                  <span className="code min-w-0 flex-1 truncate text-ink">
                    {outgoing ? `${r.from.columns.join(', ')} → ${other}` : `${other} (${r.from.columns.join(', ')}) →`}
                  </span>
                  <span className="shrink-0 text-ink-muted">{tOr(`er.confidence.${r.confidence}`, r.confidence)}</span>
                </button>
              </li>
            )
          })}
        </ul>
      </div>
    </div>
  )
}
