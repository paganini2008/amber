import { useVirtualizer } from '@tanstack/react-virtual'
import clsx from 'clsx'
import { ChevronDown, ChevronRight, ChevronsDownUp, ChevronsUpDown, Repeat } from 'lucide-react'
import { useEffect, useMemo, useRef } from 'react'
import { usePlaybackView, useTrace } from '@/hooks'
import { t } from '@/i18n'
import { KindIcon, confidenceText, isMuted, kindText } from '@/lib/kinds'
import { worstByStatement } from '@/lib/findings'
import { flattenOutline, pathTo, visibleRowFor } from '@/lib/outline'
import { SeverityIcon } from './StatusParts'
import { useStore } from '@/store'
import { IconButton } from './ui'

const ROW = 26

/** Left panel: the script's statements as a compact tree. */
export function OutlinePanel(): React.JSX.Element {
  const analysis = useStore((s) => s.analysis)
  const index = useStore((s) => s.index)
  const collapsed = useStore((s) => s.collapsed)
  const selection = useStore((s) => s.selection)
  const selectionSource = useStore((s) => s.selectionSource)
  const toggleCollapsed = useStore((s) => s.toggleCollapsed)
  const setCollapsedAll = useStore((s) => s.setCollapsedAll)
  const selectStatement = useStore((s) => s.selectStatement)
  const revealRange = useStore((s) => s.revealRange)
  const trace = useTrace()
  const playback = usePlaybackView()
  const parent = useRef<HTMLDivElement>(null)

  const rows = useMemo(() => flattenOutline(analysis?.statements ?? [], collapsed), [analysis, collapsed])
  const marks = useMemo(() => worstByStatement(analysis?.findings ?? []), [analysis])
  const selectedId = selection?.type === 'statement' ? selection.id : null
  const onPath = useMemo(() => (index && selectedId !== null ? pathTo(index, selectedId) : new Set<number>()), [index, selectedId])

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => parent.current,
    estimateSize: () => ROW,
    overscan: 20
  })

  useEffect(() => {
    if (!index || selectedId === null || selectionSource === 'view') return
    const row = visibleRowFor(index, rows, selectedId)
    if (row >= 0) virtualizer.scrollToIndex(row, { align: 'auto' })
  }, [index, rows, selectedId, selectionSource, virtualizer])

  const onKeyDown = (event: React.KeyboardEvent): void => {
    if (!rows.length) return
    const current = selectedId === null || !index ? -1 : visibleRowFor(index, rows, selectedId)
    if ((event.key === 'ArrowRight' || event.key === 'ArrowLeft') && current >= 0) {
      const row = rows[current]!
      if (row.hasChildren && (event.key === 'ArrowLeft') !== row.collapsed) toggleCollapsed(row.statement.id)
      event.preventDefault()
      return
    }
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
    const next = event.key === 'ArrowDown' ? Math.min(rows.length - 1, current + 1) : Math.max(0, current - 1)
    event.preventDefault()
    const row = rows[next]
    if (row) {
      selectStatement(row.statement.id, 'view')
      virtualizer.scrollToIndex(next, { align: 'auto' })
    }
  }

  return (
    <section className="chrome flex h-full min-h-0 flex-col" aria-label={t('app.outline.title')}>
      <header className="flex h-9 shrink-0 items-center justify-between gap-2 border-b border-line pr-1.5 pl-3">
        <h2 className="min-w-0 truncate text-sm font-medium">{t('app.outline.title')}</h2>
        <div className="flex items-center">
          {analysis ? <span className="mr-1 text-xs whitespace-nowrap text-ink-muted">{t('app.outline.steps', { count: analysis.stepCount })}</span> : null}
          <IconButton label={t('app.outline.expandAll')} onClick={() => setCollapsedAll(false)} disabled={!analysis}>
            <ChevronsUpDown size={14} />
          </IconButton>
          <IconButton label={t('app.outline.collapseAll')} onClick={() => setCollapsedAll(true)} disabled={!analysis}>
            <ChevronsDownUp size={14} />
          </IconButton>
        </div>
      </header>
      {rows.length === 0 ? (
        <p className="px-3 py-4 text-sm text-ink-muted">{analysis ? t('app.outline.noStatements') : t('app.outline.openScript')}</p>
      ) : (
        <div ref={parent} className="min-h-0 flex-1 overflow-auto outline-none" tabIndex={0} role="tree" onKeyDown={onKeyDown} aria-label={t('app.outline.statements')}>
          <div style={{ height: virtualizer.getTotalSize(), position: 'relative' }}>
            {virtualizer.getVirtualItems().map((item) => {
              const row = rows[item.index]!
              const s = row.statement
              if (row.part) {
                const part = row.part
                return (
                  <div
                    key={`${s.id}:${part.range.start.offset}`}
                    role="treeitem"
                    aria-selected={false}
                    aria-level={row.depth + 1}
                    onClick={() => {
                      selectStatement(s.id, 'view', false)
                      revealRange(part.range.start.offset, part.range.end.offset)
                    }}
                    title={`${t('app.outline.cte', { name: part.name })} · ${t('common.line', { line: part.range.start.line })}${part.sources.length ? `\n${t('app.outline.reads', { names: part.sources.join(', ') })}` : ''}`}
                    className="absolute left-0 flex w-full cursor-default items-center gap-1.5 pr-2 text-sm hover:bg-surface-hover"
                    style={{ top: item.start, height: ROW, paddingLeft: 6 + row.depth * 14 + 20 }}
                  >
                    <KindIcon kind="CTE" className="shrink-0 text-ink-muted" />
                    <span className="code min-w-0 truncate text-[12.5px] text-ink">{part.name}</span>
                    {part.sources.length ? <span className="min-w-0 flex-1 truncate text-xs text-ink-muted">{part.sources.join(', ')}</span> : <span className="flex-1" />}
                    <span className="code shrink-0 text-xs text-ink-faint tabular-nums">{part.range.start.line}</span>
                  </div>
                )
              }
              const selected = s.id === selectedId
              const lit = trace ? trace.statements.has(s.id) || onPath.has(s.id) : true
              const current = playback.current === s.id
              const future = playback.active && !playback.played.has(s.id)
              const muted = isMuted(s.confidence)
              return (
                <div
                  key={s.id}
                  role="treeitem"
                  aria-selected={selected}
                  aria-expanded={row.hasChildren ? !row.collapsed : undefined}
                  aria-level={row.depth + 1}
                  data-statement={s.id}
                  onClick={() => selectStatement(s.id, 'view')}
                  title={`${kindText(s.kind)} · ${t('common.line', { line: s.range.start.line })}\n${s.summary}`}
                  className={clsx(
                    'absolute left-0 flex w-full cursor-default items-center gap-1.5 pr-2 text-sm',
                    selected || current ? 'bg-amber-wash' : 'hover:bg-surface-hover',
                    (selected || current) && 'shadow-[inset_2px_0_0_var(--amber)]'
                  )}
                  style={{ top: item.start, height: ROW, paddingLeft: 6 + row.depth * 14 }}
                >
                  <button
                    type="button"
                    tabIndex={-1}
                    aria-label={row.collapsed ? t('app.outline.expand') : t('app.outline.collapse')}
                    className={clsx('flex h-4 w-4 shrink-0 items-center justify-center text-ink-muted', !row.hasChildren && 'invisible')}
                    onClick={(e) => {
                      e.stopPropagation()
                      toggleCollapsed(s.id)
                    }}
                  >
                    {row.collapsed ? <ChevronRight size={13} /> : <ChevronDown size={13} />}
                  </button>
                  <KindIcon kind={s.kind} className={clsx('shrink-0', selected ? 'text-amber-ink' : 'text-ink-muted')} />
                  <span
                    className={clsx(
                      'min-w-0 flex-1 truncate',
                      !lit || future ? 'text-ink-faint' : muted ? 'text-ink-muted italic' : 'text-ink',
                      selected && 'font-medium'
                    )}
                  >
                    {s.summary || kindText(s.kind)}
                  </span>
                  {s.kind === 'LOOP' ? <Repeat size={12} className="shrink-0 text-ink-muted" aria-label={kindText('LOOP')} /> : null}
                  {marks.has(s.id) ? (
                    <span className="shrink-0" title={t('app.outline.findings', { count: marks.get(s.id)!.count })}>
                      <SeverityIcon severity={marks.get(s.id)!.severity} size={12} />
                    </span>
                  ) : null}
                  {muted ? <span className={`conf-line conf-${s.confidence} shrink-0 text-ink-muted`} title={confidenceText(s.confidence)} /> : null}
                  <span className="code shrink-0 text-xs text-ink-faint tabular-nums">{s.range.start.line}</span>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </section>
  )
}
