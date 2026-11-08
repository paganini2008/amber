import clsx from 'clsx'
import { Check, ChevronDown, ChevronRight, ChevronsDownUp, ChevronsUpDown, ListOrdered, MousePointerClick, Pause, Play, RotateCcw, SkipBack, SkipForward } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { LogicalStep, QueryBlockSource, Ref, Statement } from '@shared/protocol'
import {
  ancestorIds,
  blockProgress,
  boxProgress,
  compactOrder,
  hasBlockIds,
  logicalOrderNote,
  moveCursor,
  nodesByBlockId,
  phaseBadges,
  WRITE_BLOCK,
  type BlockProgress,
  type PhaseBadge
} from '@/lib/logicalOrder'
import { KindIcon, kindText, objectKindText } from '@/lib/kinds'
import { lineLabel, type AnalysisIndex } from '@/lib/model'
import {
  blockTitle,
  buildStructureTree,
  collapsibleIds,
  countStructure,
  describeCounts,
  flattenStructure,
  kindColorKey,
  resolveSourceBlock,
  sourceLabel,
  statementTitle,
  type StructureNode
} from '@/lib/structure'
import { t } from '@/i18n'
import { useStore } from '@/store'
import { Button, IconButton } from '@/components/ui'
import { onReferenceContextMenu } from '@/components/References'

/** Time per step when playing the logical order. */
const STEP_MS = 1100

/** What the Structure view shows, used by SVG/PNG/Mermaid export. */
export const structureSnapshot: { current: { statementId: number; collapsed: ReadonlySet<string> } | null } = { current: null }

/** Outputs beyond this many are folded into "N columns". */
const MAX_OUTPUTS = 6

/** Center view: the selected statement's query structure as nested boxes. */
export function StructureView(): React.JSX.Element {
  const index = useStore((s) => s.index)!
  const selection = useStore((s) => s.selection)
  const selectedId = selection?.type === 'statement' ? selection.id : null
  // Selecting a table from a source chip selects the object; keep showing the statement meanwhile.
  const [shownId, setShownId] = useState<number | null>(selectedId)
  if (selectedId !== null && selectedId !== shownId) setShownId(selectedId)
  if (selection === null && shownId !== null) setShownId(null)
  const statement = shownId !== null ? index.byId.get(shownId) : undefined

  useEffect(() => {
    if (!statement) structureSnapshot.current = null
    return () => {
      structureSnapshot.current = null
    }
  }, [statement])

  if (!statement) return <Hint>{t('structure.empty.select')}</Hint>
  if (statement.container) {
    return (
      <Hint>
        {kindText(statement.kind)}
        {statement.name ? ` ${statement.name}` : ''} contains other statements. Select one of them to see its query structure.
      </Hint>
    )
  }
  if (!statement.structure) return <Hint>{t('structure.empty.engine')}</Hint>
  return <StructureBody key={statement.id} statement={statement} index={index} />
}

function Hint({ children }: { children: React.ReactNode }): React.JSX.Element {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-2 px-8 text-center text-sm text-ink-muted">
      <MousePointerClick size={20} />
      <p className="max-w-[420px]">{children}</p>
    </div>
  )
}

interface BoxContext {
  statement: Statement
  index: AnalysisIndex
  collapsed: ReadonlySet<string>
  toggle(id: string): void
  active: string | null
  activate(node: StructureNode): void
  openOutputs: ReadonlySet<string>
  toggleOutputs(id: string): void
  onSource(node: StructureNode, source: QueryBlockSource): void
  selectedKey: string | null
  /** Logical order: badges are shown while the panel is open; progress while stepping. */
  orderOpen: boolean
  badges: Map<number, PhaseBadge[]>
  progress: Map<number, BlockProgress>
  currentStep: number | null
  currentNode: string | null
}

function domId(statementId: number, blockId: string): string {
  return `qb-${statementId}-${blockId.replace(/\./g, '-')}`
}

/** The ref of a source table in this statement, preferring one inside the block's range. */
function refFor(statement: Statement, node: StructureNode, source: QueryBlockSource): Ref | undefined {
  const name = source.name.toLowerCase()
  const refs = statement.refs.filter((r) => r.name.toLowerCase() === name && r.kind !== 'CTE')
  const { start, end } = node.block.range
  return refs.find((r) => r.range.start.offset >= start.offset && r.range.end.offset <= end.offset) ?? refs[0]
}

function StructureBody({ statement, index }: { statement: Statement; index: AnalysisIndex }): React.JSX.Element {
  const select = useStore((s) => s.select)
  const selectStatement = useStore((s) => s.selectStatement)
  const markRange = useStore((s) => s.markRange)
  const selection = useStore((s) => s.selection)
  const tree = useMemo(() => buildStructureTree(statement.structure), [statement])
  const counts = useMemo(() => countStructure(statement.structure), [statement])
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(() => new Set())
  const [openOutputs, setOpenOutputs] = useState<ReadonlySet<string>>(() => new Set())
  const [active, setActive] = useState<string | null>(null)
  const [scrollTo, setScrollTo] = useState<string | null>(null)

  // Logical order (reading order) of the statement.
  const steps = useMemo<LogicalStep[]>(() => (hasBlockIds(statement.structure) ? (statement.executionOrder ?? []) : []), [statement])
  const panelWanted = useStore((s) => s.logicalOrder.open)
  const request = useStore((s) => s.logicalOrder.request)
  const setOrderOpen = useStore((s) => s.setLogicalOrderOpen)
  const clearStepInside = useStore((s) => s.clearStepInside)
  const orderOpen = panelWanted && steps.length > 0
  const byBlock = useMemo(() => nodesByBlockId(tree), [tree])
  const badges = useMemo(() => phaseBadges(steps), [steps])
  const [cursor, setCursor] = useState<number | null>(() => (request?.statementId === statement.id && steps.length ? 0 : null))
  const [playing, setPlaying] = useState(false)
  const progress = useMemo(() => boxProgress(tree, blockProgress(steps, cursor)), [tree, steps, cursor])
  const current = cursor !== null ? steps[cursor] : undefined
  const currentNode = current && current.block !== WRITE_BLOCK ? (byBlock.get(current.block)?.id ?? null) : null
  // Boxes on the way to the current step open by themselves, without changing what the user collapsed.
  const shownCollapsed = useMemo(() => {
    if (!currentNode) return collapsed
    const next = new Set(collapsed)
    for (const a of ancestorIds(tree, currentNode)) next.delete(a)
    return next
  }, [collapsed, currentNode, tree])
  const scrollRef = useRef<HTMLDivElement>(null)

  const goTo = (i: number | null): void => {
    setCursor(i)
    const st = i !== null ? steps[i] : undefined
    if (!st) return
    markRange({ from: st.range.start.offset, to: st.range.end.offset })
    const node = st.block === WRITE_BLOCK ? null : byBlock.get(st.block)
    const target = node ? domId(statement.id, node.id) : `qb-${statement.id}-root`
    setTimeout(() => document.getElementById(target)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }), 40)
  }

  // A "step inside" request from Execution playback or the Statement view starts at step 1.
  useEffect(() => {
    if (request?.statementId !== statement.id) return
    clearStepInside()
    const first = steps[0]
    if (first) markRange({ from: first.range.start.offset, to: first.range.end.offset })
  }, [request, statement, steps, clearStepInside, markRange])

  useEffect(() => {
    if (!playing || cursor === null) return
    const timer = setTimeout(() => {
      if (cursor >= steps.length - 1) setPlaying(false)
      else goTo(cursor + 1)
    }, STEP_MS)
    return () => clearTimeout(timer)
    // goTo is recreated each render; the step it moves to only depends on cursor.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [playing, cursor, steps])

  const play = (): void => {
    if (playing) {
      setPlaying(false)
      return
    }
    if (cursor === null || cursor >= steps.length - 1) goTo(0)
    setPlaying(true)
  }
  const step = (delta: number): void => {
    setPlaying(false)
    goTo(moveCursor(cursor, delta, steps.length))
  }
  const reset = (): void => {
    setPlaying(false)
    setCursor(null)
    markRange(null)
  }
  const onKeyDown = (e: React.KeyboardEvent): void => {
    if (!orderOpen || (e.target as HTMLElement).closest('input, textarea, select')) return
    if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
      e.preventDefault()
      step(e.key === 'ArrowRight' ? 1 : -1)
    }
  }

  useEffect(() => {
    structureSnapshot.current = { statementId: statement.id, collapsed }
  }, [statement, collapsed])

  useEffect(() => {
    if (!scrollTo) return
    document.getElementById(domId(statement.id, scrollTo))?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [scrollTo, statement])

  const toggleIn = (set: ReadonlySet<string>, id: string): Set<string> => {
    const next = new Set(set)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  }

  const activate = (node: StructureNode): void => {
    setActive(node.id)
    markRange({ from: node.block.range.start.offset, to: node.block.range.end.offset })
  }

  const onSource = (node: StructureNode, source: QueryBlockSource): void => {
    const target = resolveSourceBlock(tree, node, source)
    if (target) {
      // Expand the way to the CTE or derived table, then select and scroll to it.
      const byId = new Map(flattenStructure(tree).map((n) => [n.id, n]))
      const open = new Set(collapsed)
      for (let p = target.parentId; p !== null; p = byId.get(p)?.parentId ?? null) open.delete(p)
      setCollapsed(open)
      activate(target)
      setScrollTo(target.id)
      return
    }
    const ref = refFor(statement, node, source)
    const key = ref?.key ?? source.name.toUpperCase()
    if (index.lifecycleByKey.has(key)) select({ type: 'object', key }, 'view', false)
    if (ref) markRange({ from: ref.range.start.offset, to: ref.range.end.offset })
    else markRange({ from: node.block.range.start.offset, to: node.block.range.end.offset })
  }

  const ctx: BoxContext = {
    statement,
    index,
    collapsed: shownCollapsed,
    toggle: (id) => setCollapsed((c) => toggleIn(c, id)),
    active,
    activate,
    openOutputs,
    toggleOutputs: (id) => setOpenOutputs((o) => toggleIn(o, id)),
    onSource,
    selectedKey: selection?.type === 'object' ? selection.key : null,
    orderOpen,
    badges,
    progress,
    currentStep: current?.step ?? null,
    currentNode
  }
  const canCollapse = counts.maxDepth > 1

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex shrink-0 flex-wrap items-center gap-x-4 gap-y-1 border-b border-line px-5 py-2.5">
        <div className="flex min-w-0 items-center gap-2">
          <KindIcon kind={statement.kind} className="text-ink-muted" />
          <h2 className="code truncate text-[14px] font-semibold text-ink" title={statement.summary}>
            {statementTitle(statement)}
          </h2>
          <span className="shrink-0 text-xs text-ink-muted">{lineLabel(statement)}</span>
        </div>
        <span className="text-xs text-ink-muted">{describeCounts(counts)}</span>
        <div className="ml-auto flex items-center gap-1">
          <Button
            size="sm"
            variant="ghost"
            disabled={steps.length === 0}
            aria-pressed={orderOpen}
            title={steps.length ? t('structure.order.show') : t('structure.order.none')}
            className={clsx(orderOpen && 'bg-amber-wash text-amber-ink')}
            onClick={() => {
              setOrderOpen(!orderOpen)
              if (orderOpen) reset()
            }}
          >
            <ListOrdered size={13} /> {t('structure.order.title')}
          </Button>
          <Button size="sm" variant="ghost" disabled={!canCollapse} onClick={() => setCollapsed(new Set())}>
            <ChevronsUpDown size={13} /> {t('structure.expandAll')}
          </Button>
          <Button size="sm" variant="ghost" disabled={!canCollapse} onClick={() => setCollapsed(new Set(collapsibleIds(tree)))}>
            <ChevronsDownUp size={13} /> {t('structure.collapseAll')}
          </Button>
        </div>
      </div>
      <div className="flex min-h-0 flex-1" tabIndex={-1} onKeyDown={onKeyDown}>
      <div ref={scrollRef} className="min-h-0 min-w-0 flex-1 overflow-auto p-4" tabIndex={0} aria-label={t('structure.title')}>
        <div
          id={`qb-${statement.id}-root`}
          className={clsx('qb-box min-w-[320px] cursor-default', current?.block === WRITE_BLOCK && 'qb-active')}
          style={{ '--qb': 'var(--ink)' } as React.CSSProperties}
          onClick={() => {
            setActive(null)
            selectStatement(statement.id)
          }}
        >
          <div className="flex flex-wrap items-center gap-2 px-2.5 py-1.5">
            <span className="qb-badge">{statement.kind.replace(/_/g, ' ')}</span>
            <span className="min-w-0 truncate text-sm text-ink">{statement.summary || kindText(statement.kind)}</span>
            {orderOpen ? <StepChips badges={badges.get(WRITE_BLOCK)} current={current?.step ?? null} /> : null}
          </div>
          {tree.length ? (
            <div className="space-y-1.5 pr-1.5 pb-1.5 pl-2.5">
              {tree.map((n) => (
                <Box key={n.id} node={n} ctx={ctx} />
              ))}
            </div>
          ) : (
            <p className="px-2.5 pb-2 text-sm text-ink-muted">{t('structure.empty.none')}</p>
          )}
        </div>
      </div>
      {orderOpen ? (
        <LogicalOrderPanel
          steps={steps}
          cursor={cursor}
          playing={playing}
          byBlock={byBlock}
          onGo={(i) => {
            setPlaying(false)
            goTo(i)
          }}
          onPlay={play}
          onStep={step}
          onReset={reset}
        />
      ) : null}
      </div>
    </div>
  )
}

function StepChips({ badges, current }: { badges: PhaseBadge[] | undefined; current: number | null }): React.JSX.Element | null {
  if (!badges?.length) return null
  return (
    <span className="flex flex-wrap items-center gap-1" aria-label={t('structure.order.boxSteps')}>
      {badges.map((b) => (
        <span key={b.step} className={clsx('step-chip', b.step === current && 'step-chip-current')} title={t('structure.order.stepPhase', { step: b.step, phase: b.phase })}>
          <span className="step-num">{b.step}</span>
          {b.phase === 'CTE' ? t('structure.steps.ready') : b.phase}
        </span>
      ))}
    </span>
  )
}

function LogicalOrderPanel({
  steps,
  cursor,
  playing,
  byBlock,
  onGo,
  onPlay,
  onStep,
  onReset
}: {
  steps: LogicalStep[]
  cursor: number | null
  playing: boolean
  byBlock: Map<number, StructureNode>
  onGo(i: number): void
  onPlay(): void
  onStep(delta: number): void
  onReset(): void
}): React.JSX.Element {
  const listRef = useRef<HTMLOListElement>(null)
  useEffect(() => {
    if (cursor === null) return
    listRef.current?.querySelector(`[data-step="${cursor}"]`)?.scrollIntoView({ block: 'nearest' })
  }, [cursor])
  return (
    <aside className="flex w-[340px] shrink-0 flex-col border-l border-line bg-surface" aria-label={t('structure.order.title')}>
      <div className="border-b border-line px-3 pt-2.5 pb-2">
        <h3 className="text-sm font-semibold text-ink">{t('structure.order.title')}</h3>
        <p className="mt-0.5 text-xs leading-[17px] text-ink-muted">{logicalOrderNote()}</p>
        <div className="mt-2 flex items-center gap-1">
          <IconButton label={t('structure.order.previous')} onClick={() => onStep(-1)} disabled={cursor === 0}>
            <SkipBack size={15} />
          </IconButton>
          <button
            type="button"
            onClick={onPlay}
            aria-label={playing ? t('structure.order.pause') : t('structure.order.play')}
            className="no-drag flex h-7 w-7 items-center justify-center rounded-full bg-amber text-on-amber hover:brightness-105"
          >
            {playing ? <Pause size={14} fill="currentColor" /> : <Play size={14} fill="currentColor" className="translate-x-px" />}
          </button>
          <IconButton label={t('structure.order.next')} onClick={() => onStep(1)} disabled={cursor !== null && cursor >= steps.length - 1}>
            <SkipForward size={15} />
          </IconButton>
          <IconButton label={t('structure.order.reset')} onClick={onReset} disabled={cursor === null}>
            <RotateCcw size={13} />
          </IconButton>
          <span className="ml-auto text-xs text-ink-muted tabular-nums">
            {cursor === null ? t('structure.order.count', { count: steps.length }) : t('structure.order.stepOf', { step: cursor + 1, total: steps.length })}
          </span>
        </div>
      </div>
      <ol ref={listRef} className="min-h-0 flex-1 overflow-auto py-1">
        {steps.map((s, i) => {
          const state = cursor === null ? 'idle' : i < cursor ? 'done' : i === cursor ? 'current' : 'todo'
          const node = s.block === WRITE_BLOCK ? null : byBlock.get(s.block)
          return (
            <li key={s.step} data-step={i}>
              <button
                type="button"
                onClick={() => onGo(i)}
                className={clsx(
                  'flex w-full items-start gap-2 py-1 pr-3 text-left',
                  state === 'current' ? 'bg-amber-wash shadow-[inset_3px_0_0_var(--amber)]' : 'hover:bg-surface-hover'
                )}
                style={{ paddingLeft: 12 + Math.min(s.depth, 8) * 14 }}
              >
                <span className={clsx('step-dot', state === 'done' && 'step-dot-done', state === 'current' && 'step-dot-current')}>
                  {state === 'done' ? <Check size={11} strokeWidth={2.5} /> : s.step}
                </span>
                <span className="min-w-0 flex-1">
                  <span className={clsx('block text-sm leading-5', state === 'todo' ? 'text-ink-muted' : 'text-ink')}>
                    <span className="font-medium">{s.phase === 'WRITE' ? t('structure.order.write') : s.phase}</span>
                    {s.detail ? <span className="text-ink-muted"> {s.detail}</span> : null}
                  </span>
                  <span className="block truncate text-xs text-ink-faint">{node ? t('structure.order.inBlock', { block: blockTitle(node.block) }) : t('structure.order.theStatement')}</span>
                </span>
              </button>
            </li>
          )
        })}
      </ol>
    </aside>
  )
}

function Box({ node, ctx }: { node: StructureNode; ctx: BoxContext }): React.JSX.Element {
  const b = node.block
  const isCollapsed = ctx.collapsed.has(node.id)
  const hidden = isCollapsed ? flattenStructure(node.children).length : 0
  const showAll = ctx.openOutputs.has(node.id)
  const outputs = b.outputs.length > MAX_OUTPUTS && !showAll ? [] : b.outputs
  const progress = typeof b.id === 'number' ? ctx.progress.get(b.id) : undefined
  return (
    <div
      id={domId(ctx.statement.id, node.id)}
      className={clsx(
        'qb-box min-w-[240px]',
        `qb-${kindColorKey(b.kind)}`,
        node.depth % 2 === 1 && 'qb-odd',
        (ctx.currentNode === node.id || (ctx.currentNode === null && ctx.active === node.id)) && 'qb-active',
        progress === 'todo' && 'qb-todo'
      )}
      onClick={(e) => {
        e.stopPropagation()
        ctx.activate(node)
      }}
    >
      <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 px-2 py-1">
        {node.children.length ? (
          <button
            type="button"
            aria-label={isCollapsed ? t('structure.expand') : t('structure.collapse')}
            aria-expanded={!isCollapsed}
            className="-mr-0.5 flex h-4 w-4 items-center justify-center text-ink-muted hover:text-ink"
            onClick={(e) => {
              e.stopPropagation()
              ctx.toggle(node.id)
            }}
          >
            {isCollapsed ? <ChevronRight size={13} /> : <ChevronDown size={13} />}
          </button>
        ) : null}
        <span className="qb-badge code" title={b.kind}>
          {blockTitle(b)}
        </span>
        {b.role ? <span className="rounded-[3px] border border-line px-1 text-xs leading-4 text-ink-muted">{b.role}</span> : null}
        {b.sources.map((s, i) => (
          <SourceChip key={`${s.name}-${i}`} node={node} source={s} ctx={ctx} />
        ))}
        {b.clauses.map((c, i) => (
          <span key={`${c}-${i}`} className="rounded-[3px] bg-surface-2 px-1 text-xs leading-4 font-medium tracking-[0.02em] text-ink-muted">
            {c}
          </span>
        ))}
        {hidden ? <span className="text-xs text-ink-faint">{t('structure.box.hiddenNested', { count: hidden })}</span> : null}
        {ctx.orderOpen && typeof b.id === 'number' ? <StepChips badges={ctx.badges.get(b.id)} current={ctx.currentStep} /> : null}
        {progress === 'done' ? (
          <span className="ml-auto flex items-center gap-1 text-xs text-ink-muted" title={t('structure.order.allDone')}>
            <Check size={12} /> {t('structure.order.done')}
          </span>
        ) : null}
      </div>
      {b.outputs.length ? (
        <div className="flex flex-wrap items-center gap-1 px-2 pb-1 text-[12.5px]">
          <span className="text-ink-faint" aria-hidden>
            →
          </span>
          {outputs.map((o, i) => (
            <span key={`${o}-${i}`} className="code max-w-64 truncate text-[12.5px] text-ink-muted" title={o}>
              {o}
              {i < outputs.length - 1 ? ',' : ''}
            </span>
          ))}
          {b.outputs.length > MAX_OUTPUTS ? (
            <button
              type="button"
              className="text-xs text-ink-muted underline-offset-2 hover:text-ink hover:underline"
              onClick={(e) => {
                e.stopPropagation()
                ctx.toggleOutputs(node.id)
              }}
            >
              {showAll ? t('structure.box.fewer') : t('common.columns', { count: b.outputs.length })}
            </button>
          ) : null}
        </div>
      ) : null}
      {node.children.length && !isCollapsed ? (
        <div className="space-y-1.5 pr-1.5 pb-1.5 pl-2.5">
          {node.children.map((c) => (
            <Box key={c.id} node={c} ctx={ctx} />
          ))}
        </div>
      ) : null}
    </div>
  )
}

function SourceChip({ node, source, ctx }: { node: StructureNode; source: QueryBlockSource; ctx: BoxContext }): React.JSX.Element {
  const ref = source.kind === 'CTE' || source.kind === 'SUBQUERY' ? undefined : refFor(ctx.statement, node, source)
  const lit = ref !== undefined && ref.key === ctx.selectedKey
  const transient = source.kind === 'CTE' || source.kind === 'SUBQUERY'
  const what = source.kind === 'CTE' ? t('structure.source.goCte') : source.kind === 'SUBQUERY' ? t('structure.source.goDerived') : t('structure.source.select')
  return (
    <button
      type="button"
      title={t(source.alias ? 'structure.source.titleAlias' : 'structure.source.title', { kind: objectKindText(source.kind, true), name: source.name, alias: source.alias ?? '', action: what })}
      onClick={(e) => {
        e.stopPropagation()
        ctx.onSource(node, source)
      }}
      onContextMenu={source.kind === 'SUBQUERY' ? undefined : onReferenceContextMenu({ object: source.name })}
      className={clsx(
        'code max-w-72 truncate rounded-[3px] px-1.5 text-[12px] leading-[18px]',
        transient ? `qb-${source.kind === 'CTE' ? 'cte' : 'derived'} border border-dashed` : 'border bg-surface text-ink',
        transient && 'border-[color-mix(in_srgb,var(--qb)_70%,transparent)] bg-[color-mix(in_srgb,var(--qb)_8%,transparent)] text-ink italic',
        !transient && (lit ? 'border-amber bg-amber-wash text-amber-ink' : 'border-line-strong hover:border-ink-muted'),
        source.kind === 'UNKNOWN' && 'border-dotted text-ink-muted'
      )}
    >
      {sourceLabel(source)}
    </button>
  )
}

/** Compact "Query structure" section of the Statement view and details panel. */
export function StructureSummary({ statement, compact }: { statement: Statement; compact?: boolean }): React.JSX.Element | null {
  const setView = useStore((s) => s.setView)
  const stepInside = useStore((s) => s.stepInside)
  const order = useMemo(() => (hasBlockIds(statement.structure) ? compactOrder(statement.executionOrder) : []), [statement])
  const markRange = useStore((s) => s.markRange)
  const tree = useMemo(() => buildStructureTree(statement.structure), [statement])
  const counts = useMemo(() => countStructure(statement.structure), [statement])
  if (statement.container || !statement.structure || counts.total === 0) return null
  // The details panel shows only the counts; the Statement view lists the blocks too.
  const rows = compact ? [] : flattenStructure(tree).slice(0, 14)
  const more = compact ? 0 : counts.total - rows.length
  return (
    <section>
      <div className="mb-1.5 flex items-baseline gap-3">
        <h3 className={clsx('font-semibold', compact ? 'text-sm' : 'text-base')}>{t('structure.title')}</h3>
        <button type="button" className="text-xs text-amber-ink hover:underline" onClick={() => setView('structure')}>
          {t('structure.summary.open')}
        </button>
      </div>
      <p className="text-sm text-ink-muted">{describeCounts(counts)}</p>
      {rows.length ? (
        <ul className="mt-2 space-y-px">
          {rows.map((n) => (
            <li key={n.id} style={{ paddingLeft: Math.min(n.depth, 8) * 14 }}>
              <button
                type="button"
                className="flex w-full min-w-0 items-center gap-2 rounded-[4px] px-1.5 py-0.5 text-left text-sm hover:bg-surface-hover"
                onClick={() => markRange({ from: n.block.range.start.offset, to: n.block.range.end.offset })}
              >
                <span className={clsx('qb-badge code shrink-0', `qb-${kindColorKey(n.block.kind)}`)}>{blockTitle(n.block)}</span>
                {n.block.role ? <span className="shrink-0 text-xs text-ink-muted">{n.block.role}</span> : null}
                <span className="code min-w-0 truncate text-[12px] text-ink-muted">{n.block.sources.map(sourceLabel).join(', ')}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      {more > 0 ? <p className="mt-1 text-xs text-ink-muted">{t('structure.summary.more', { count: more })}</p> : null}
      {order.length ? (
        <div className="mt-3">
          <div className="mb-1 flex items-baseline gap-3">
            <h4 className="text-sm font-semibold">{t('structure.order.title')}</h4>
            <button type="button" className="text-xs text-amber-ink hover:underline" onClick={() => stepInside(statement.id)}>
              {t('structure.summary.stepThrough')}
            </button>
          </div>
          <ol className="flex flex-wrap items-center gap-x-1 gap-y-1.5 text-sm" aria-label={t('structure.summary.outline')}>
            {order.map((o, i) => (
              <li key={o.step} className="flex items-center gap-1">
                {i > 0 ? (
                  <span className="text-ink-faint" aria-hidden>
                    →
                  </span>
                ) : null}
                {o.nested ? (
                  <span className="text-xs text-ink-muted" title={t('structure.summary.nestedHint', { count: o.nested })}>
                    {t('structure.summary.nested', { count: o.nested })}
                  </span>
                ) : null}
                <span className="step-chip">
                  <span className="step-num">{o.step}</span>
                  {o.label}
                </span>
              </li>
            ))}
          </ol>
          {compact ? null : <p className="mt-1.5 text-xs text-ink-muted">{logicalOrderNote()}</p>}
        </div>
      ) : null}
    </section>
  )
}
