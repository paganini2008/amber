import { useVirtualizer } from '@tanstack/react-virtual'
import clsx from 'clsx'
import { ArrowRight, ChevronDown, ChevronRight, Repeat } from 'lucide-react'
import { useEffect, useMemo, useRef } from 'react'
import type { Ref } from '@shared/protocol'
import { usePlaybackView, useTrace } from '@/hooks'
import { KindIcon, confidenceText, isMuted, kindText } from '@/lib/kinds'
import { WRITE_ACCESS } from '@/lib/model'
import { worstByStatement } from '@/lib/findings'
import { flattenOutline, pathTo, visibleRowFor } from '@/lib/outline'
import { SeverityIcon } from '@/components/StatusParts'
import { useStore } from '@/store'

const ROW = 54

/** Center Outline view: each statement as one line of reading, with what it reads and writes. */
export function OutlineView(): React.JSX.Element {
  const analysis = useStore((s) => s.analysis)!
  const index = useStore((s) => s.index)!
  const collapsed = useStore((s) => s.collapsed)
  const selection = useStore((s) => s.selection)
  const selectionSource = useStore((s) => s.selectionSource)
  const selectStatement = useStore((s) => s.selectStatement)
  const select = useStore((s) => s.select)
  const toggleCollapsed = useStore((s) => s.toggleCollapsed)
  const trace = useTrace()
  const playback = usePlaybackView()
  const parent = useRef<HTMLDivElement>(null)

  const rows = useMemo(() => flattenOutline(analysis.statements, collapsed), [analysis, collapsed])
  const marks = useMemo(() => worstByStatement(analysis.findings ?? []), [analysis])
  const selectedId = selection?.type === 'statement' ? selection.id : null
  const onPath = useMemo(() => (selectedId !== null ? pathTo(index, selectedId) : new Set<number>()), [index, selectedId])
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => parent.current,
    estimateSize: () => ROW,
    overscan: 12
  })

  useEffect(() => {
    if (selectedId === null || selectionSource === 'view') return
    const row = visibleRowFor(index, rows, selectedId)
    if (row >= 0) virtualizer.scrollToIndex(row, { align: 'center' })
  }, [index, rows, selectedId, selectionSource, virtualizer])

  const counts = useMemo(() => {
    const c: Record<string, number> = { SOURCE: 0, INTERMEDIATE: 0, TARGET: 0, STRUCTURE: 0 }
    for (const l of analysis.lifecycles) c[l.role] = (c[l.role] ?? 0) + 1
    return c
  }, [analysis])

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex shrink-0 flex-wrap items-baseline gap-x-6 gap-y-1 border-b border-line px-5 py-3">
        <h2 className="text-xl font-semibold tracking-[-0.01em]">{analysis.name}</h2>
        <dl className="flex flex-wrap gap-x-5 gap-y-1 text-sm text-ink-muted">
          <Stat label="lines" value={analysis.lineCount} />
          <Stat label="statements" value={analysis.stepCount} />
          <Stat label={counts.SOURCE === 1 ? 'source' : 'sources'} value={counts.SOURCE ?? 0} />
          <Stat label="intermediate" value={counts.INTERMEDIATE ?? 0} />
          <Stat label={counts.TARGET === 1 ? 'target' : 'targets'} value={counts.TARGET ?? 0} />
          <Stat label="ms to analyze" value={analysis.elapsedMs} />
        </dl>
      </div>
      <div ref={parent} className="min-h-0 flex-1 overflow-auto" role="list" aria-label="Statements in source order">
        <div style={{ height: virtualizer.getTotalSize(), position: 'relative' }}>
          {virtualizer.getVirtualItems().map((item) => {
            const row = rows[item.index]!
            const s = row.statement
            const selected = s.id === selectedId
            const current = playback.current === s.id
            const lit = trace ? trace.statements.has(s.id) || onPath.has(s.id) : true
            const future = playback.active && !playback.played.has(s.id)
            const reads = uniqueRefs(s.refs.filter((r) => r.access === 'READ' && r.kind !== 'CTE'))
            const writes = uniqueRefs(s.refs.filter((r) => WRITE_ACCESS.has(r.access) || r.access === 'EXECUTE'))
            return (
              <div
                key={s.id}
                role="listitem"
                onClick={() => selectStatement(s.id, 'view')}
                className={clsx(
                  'absolute left-0 flex w-full cursor-default items-stretch border-b border-line/60',
                  selected || current ? 'bg-amber-wash shadow-[inset_3px_0_0_var(--amber)]' : 'hover:bg-surface-hover',
                  (!lit || future) && !selected && 'recede'
                )}
                style={{ top: item.start, height: ROW }}
              >
                <div className="code w-14 shrink-0 pt-2 pr-3 text-right text-xs text-ink-faint tabular-nums">{s.range.start.line}</div>
                <div className="flex min-w-0 flex-1 flex-col justify-center gap-1 pr-5" style={{ paddingLeft: row.depth * 20 }}>
                  <div className="flex min-w-0 items-center gap-2">
                    {row.hasChildren ? (
                      <button
                        type="button"
                        aria-label={row.collapsed ? 'Expand' : 'Collapse'}
                        className="-ml-5 flex h-4 w-4 items-center justify-center text-ink-muted hover:text-ink"
                        onClick={(e) => {
                          e.stopPropagation()
                          toggleCollapsed(s.id)
                        }}
                      >
                        {row.collapsed ? <ChevronRight size={13} /> : <ChevronDown size={13} />}
                      </button>
                    ) : null}
                    <KindIcon kind={s.kind} className={selected ? 'text-amber-ink' : 'text-ink-muted'} />
                    <span className="shrink-0 text-xs text-ink-muted">{kindText(s.kind)}</span>
                    <span className={clsx('min-w-0 truncate text-base', isMuted(s.confidence) ? 'text-ink-muted italic' : 'text-ink')}>
                      {s.summary || kindText(s.kind)}
                    </span>
                    {marks.has(s.id) ? (
                      <span className="flex shrink-0 items-center gap-1 text-xs text-ink-muted">
                        <SeverityIcon severity={marks.get(s.id)!.severity} size={12} />
                        {marks.get(s.id)!.count === 1 ? '1 finding' : `${marks.get(s.id)!.count} findings`}
                      </span>
                    ) : null}
                    {s.kind === 'LOOP' ? (
                      <span className="flex shrink-0 items-center gap-1 text-xs text-ink-muted">
                        <Repeat size={12} /> loop
                      </span>
                    ) : null}
                    {isMuted(s.confidence) ? (
                      <span className="flex shrink-0 items-center gap-1 text-xs text-ink-muted">
                        <span className={`conf-line conf-${s.confidence}`} /> {confidenceText(s.confidence)}
                      </span>
                    ) : null}
                  </div>
                  {reads.length || writes.length ? (
                    <div className="flex min-w-0 items-center gap-1.5 overflow-hidden text-xs">
                      {reads.map((r) => (
                        <ObjectChip key={`r${r.key}`} refItem={r} onSelect={() => select({ type: 'object', key: r.key }, 'view')} />
                      ))}
                      {reads.length && writes.length ? <ArrowRight size={12} className="shrink-0 text-ink-faint" /> : null}
                      {writes.map((r) => (
                        <ObjectChip key={`w${r.key}`} refItem={r} write onSelect={() => select({ type: 'object', key: r.key }, 'view')} />
                      ))}
                    </div>
                  ) : null}
                </div>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

function Stat({ label, value }: { label: string; value: number }): React.JSX.Element {
  return (
    <div className="flex items-baseline gap-1">
      <dt className="sr-only">{label}</dt>
      <dd className="font-medium text-ink tabular-nums">{value.toLocaleString('en-US')}</dd>
      <span aria-hidden>{label}</span>
    </div>
  )
}

function uniqueRefs(refs: Ref[]): Ref[] {
  const seen = new Set<string>()
  return refs.filter((r) => (seen.has(r.key) ? false : (seen.add(r.key), true)))
}

function ObjectChip({ refItem, write, onSelect }: { refItem: Ref; write?: boolean; onSelect: () => void }): React.JSX.Element {
  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation()
        onSelect()
      }}
      title={`${refItem.access.toLowerCase()} ${refItem.name}`}
      className={clsx(
        'code max-w-56 shrink-0 truncate rounded-[3px] px-1.5 py-px text-[12px]',
        write ? 'border border-line-strong text-ink' : 'text-ink-muted hover:text-ink',
        refItem.confidence === 'UNRESOLVED' && 'border-dotted',
        refItem.confidence === 'PARTIAL' && 'border-dashed'
      )}
    >
      {refItem.name}
    </button>
  )
}
