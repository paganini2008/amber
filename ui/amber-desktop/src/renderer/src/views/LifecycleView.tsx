import { useVirtualizer } from '@tanstack/react-virtual'
import clsx from 'clsx'
import { Columns3, Search } from 'lucide-react'
import { ownerKeyForObject } from '@/lib/lineage'
import { useEffect, useMemo, useRef, useState } from 'react'
import { usePlaybackView, useTrace } from '@/hooks'
import { kindLabel, roleLabel } from '@/lib/dataflow'
import { kindText } from '@/lib/kinds'
import { ACCESS_MARK, buildMatrix, cellKey, markFor, type LifecycleSort } from '@/lib/lifecycle'
import { useStore } from '@/store'
import { Select, Switch, inputClass } from '@/components/ui'
import { onReferenceContextMenu } from '@/components/References'

export const MATRIX = { NAME_W: 260, HEAD_H: 34, ROW_H: 28, COL_W: 30 }

export interface MatrixViewOptions {
  sort: LifecycleSort
  filter: string
  allSteps: boolean
}

/** Shared so export can rebuild the same matrix the user sees. */
export const lifecycleOptions: { current: MatrixViewOptions } = {
  current: { sort: 'role', filter: '', allSteps: false }
}

export function LifecycleView(): React.JSX.Element {
  const index = useStore((s) => s.index)!
  const selection = useStore((s) => s.selection)
  const selectionSource = useStore((s) => s.selectionSource)
  const select = useStore((s) => s.select)
  const selectStatement = useStore((s) => s.selectStatement)
  const lineage = useStore((s) => s.lineage)
  const showOwnerColumns = useStore((s) => s.showOwnerColumns)
  const trace = useTrace()
  const playback = usePlaybackView()
  const [options, setOptions] = useState<MatrixViewOptions>(lifecycleOptions.current)
  const parent = useRef<HTMLDivElement>(null)

  useEffect(() => {
    lifecycleOptions.current = options
  }, [options])

  const matrix = useMemo(() => buildMatrix(index, options), [index, options])
  const rowV = useVirtualizer({
    count: matrix.rows.length,
    getScrollElement: () => parent.current,
    estimateSize: () => MATRIX.ROW_H,
    overscan: 10
  })
  const colV = useVirtualizer({
    horizontal: true,
    count: matrix.columns.length,
    getScrollElement: () => parent.current,
    estimateSize: () => MATRIX.COL_W,
    overscan: 8
  })

  const selectedStep = selection?.type === 'statement' ? selection.id : null
  const selectedKey = selection?.type === 'object' ? selection.key : null
  const currentStep = playback.current ?? selectedStep

  // Keep the selected step's column and the selected object's row in view.
  useEffect(() => {
    if (selectionSource === 'view') return
    if (currentStep !== null) {
      const col = matrix.columns.indexOf(currentStep)
      if (col >= 0) colV.scrollToIndex(col, { align: 'auto' })
    }
    if (selectedKey) {
      const row = matrix.rows.findIndex((r) => r.type === 'object' && r.key === selectedKey)
      if (row >= 0) rowV.scrollToIndex(row, { align: 'auto' })
    }
  }, [currentStep, selectedKey, matrix, colV, rowV, selectionSource])

  const totalW = MATRIX.NAME_W + colV.getTotalSize() + 24
  const totalH = MATRIX.HEAD_H + rowV.getTotalSize()
  const cols = colV.getVirtualItems()

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex shrink-0 flex-wrap items-center gap-3 border-b border-line px-4 py-2">
        <div className="relative">
          <Search size={13} className="pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-ink-faint" />
          <input
            className={clsx(inputClass, 'w-52 pl-7')}
            placeholder="Filter objects"
            aria-label="Filter objects"
            value={options.filter}
            onChange={(e) => setOptions({ ...options, filter: e.target.value })}
          />
        </div>
        <label className="flex items-center gap-2 text-sm text-ink-muted">
          Group by
          <Select
            label="Group or sort objects"
            value={options.sort}
            onChange={(v) => setOptions({ ...options, sort: v as LifecycleSort })}
            options={[
              { value: 'role', label: 'Role' },
              { value: 'kind', label: 'Kind' },
              { value: 'first-use', label: 'First use (no groups)' },
              { value: 'name', label: 'Name (no groups)' }
            ]}
            className="w-44"
          />
        </label>
        <label className="flex items-center gap-2 text-sm text-ink-muted">
          <Switch checked={options.allSteps} onChange={(v) => setOptions({ ...options, allSteps: v })} label="Show every step" />
          Every step
        </label>
        <Legend />
      </div>
      {matrix.rows.length === 0 ? (
        <p className="px-5 py-6 text-sm text-ink-muted">
          {options.filter ? 'No object matches the filter.' : 'This script does not touch any table or view.'}
        </p>
      ) : (
        <div ref={parent} className="relative min-h-0 flex-1 overflow-auto" role="grid" aria-label="Object lifecycle matrix" aria-rowcount={matrix.rows.length}>
          <div style={{ width: totalW, height: totalH, position: 'relative' }}>
            {/* Column headers */}
            <div className="sticky top-0 z-20 border-b border-line bg-surface" style={{ height: MATRIX.HEAD_H, width: totalW }}>
              <div
                className="sticky left-0 z-10 flex h-full items-end border-r border-line bg-surface px-3 pb-1.5 text-xs text-ink-muted"
                style={{ width: MATRIX.NAME_W }}
              >
                Objects
              </div>
              {cols.map((c) => {
                const step = matrix.columns[c.index]!
                const s = index.byId.get(step)
                const active = step === currentStep
                return (
                  <button
                    key={c.key}
                    type="button"
                    title={s ? `Step ${step + 1} · line ${s.range.start.line}\n${kindText(s.kind)}: ${s.summary}` : `Step ${step + 1}`}
                    onClick={() => selectStatement(step, 'view')}
                    className={clsx(
                      'code absolute top-0 flex h-full items-end justify-center pb-1.5 text-[11px] tabular-nums',
                      active ? 'bg-amber-wash font-semibold text-amber-ink' : 'text-ink-faint hover:text-ink'
                    )}
                    style={{ left: MATRIX.NAME_W + c.start, width: c.size }}
                  >
                    {step + 1}
                  </button>
                )
              })}
            </div>
            {/* Selected / current step column wash */}
            {currentStep !== null && matrix.columns.includes(currentStep)
              ? (() => {
                  const c = matrix.columns.indexOf(currentStep)
                  return (
                    <div
                      aria-hidden
                      className="pointer-events-none absolute z-0 bg-amber-wash"
                      style={{ left: MATRIX.NAME_W + c * MATRIX.COL_W, top: MATRIX.HEAD_H, width: MATRIX.COL_W, height: rowV.getTotalSize() }}
                    />
                  )
                })()
              : null}
            {rowV.getVirtualItems().map((r) => {
              const row = matrix.rows[r.index]!
              const top = MATRIX.HEAD_H + r.start
              if (row.type === 'header') {
                return (
                  <div key={row.key} className="absolute left-0 z-10 flex items-end border-b border-line" style={{ top, height: MATRIX.ROW_H, width: totalW }}>
                    <div className="sticky left-0 flex items-baseline gap-2 px-3 pb-1 text-sm font-semibold text-ink">
                      {row.label}
                      <span className="text-xs font-normal text-ink-muted">{row.count}</span>
                    </div>
                  </div>
                )
              }
              const l = row.lifecycle!
              const rowSelected = l.key === selectedKey
              const rowLit = trace ? trace.objects.has(l.key) : true
              return (
                <div
                  key={row.key}
                  role="row"
                  className={clsx('absolute left-0 border-b border-line/50', rowSelected && 'bg-amber-wash')}
                  style={{ top, height: MATRIX.ROW_H, width: totalW }}
                >
                  <div
                    role="rowheader"
                    className={clsx(
                      'sticky left-0 z-10 flex h-full items-center border-r border-line',
                      rowSelected ? 'bg-[color-mix(in_srgb,var(--surface)_80%,var(--amber))] shadow-[inset_3px_0_0_var(--amber)]' : 'bg-surface hover:bg-surface-hover',
                      !rowLit && 'text-ink-faint'
                    )}
                    style={{ width: MATRIX.NAME_W }}
                  >
                    <button
                      type="button"
                      onClick={() => select({ type: 'object', key: l.key }, 'view')}
                      onContextMenu={onReferenceContextMenu({ object: l.name })}
                      title={`${l.name}\n${kindLabel(l)} · ${roleLabel(l.role)}${l.createdInScript ? ' · created here' : ''}${l.droppedInScript ? ' · dropped here' : ''}`}
                      className="flex h-full min-w-0 flex-1 items-center gap-2 pl-3 text-left"
                    >
                      <span className="code min-w-0 flex-1 truncate text-[12px]">{l.name}</span>
                      <span className="shrink-0 text-[11px] text-ink-faint">{shortKind(l.kind)}</span>
                    </button>
                    {lineage && ownerKeyForObject(lineage, l.key) ? (
                      <button
                        type="button"
                        aria-label={`Column lineage of ${l.name}`}
                        title="Column lineage"
                        onClick={() => showOwnerColumns(ownerKeyForObject(lineage, l.key)!)}
                        className="flex h-full w-8 shrink-0 items-center justify-center text-ink-faint hover:text-amber-ink"
                      >
                        <Columns3 size={13} />
                      </button>
                    ) : (
                      <span className="w-3 shrink-0" />
                    )}
                  </div>
                  {cols.map((c) => {
                    const step = matrix.columns[c.index]!
                    const accesses = matrix.cells.get(cellKey(l.key, step))
                    if (!accesses) return null
                    const lit = trace ? trace.objects.has(l.key) && trace.statements.has(step) : false
                    const isCurrent = playback.active && step === playback.current
                    const future = playback.active && !playback.played.has(step)
                    const recede = (trace && !lit && !isCurrent) || future
                    return (
                      <button
                        key={c.key}
                        type="button"
                        role="gridcell"
                        onClick={() => selectStatement(step, 'view')}
                        title={`${l.name} · step ${step + 1}: ${accesses.map((a) => markFor(a).label.toLowerCase()).join(', ')}`}
                        className={clsx(
                          'absolute top-0 flex h-full items-center justify-center gap-px',
                          (lit || isCurrent) && 'lit',
                          recede && 'recede'
                        )}
                        style={{ left: MATRIX.NAME_W + c.start, width: c.size }}
                      >
                        {accesses.slice(0, 2).map((a) => {
                          const m = markFor(a)
                          return (
                            <span key={a} className={`mark mark-${m.cls}`} style={accesses.length > 1 ? { minWidth: 12, fontSize: 10 } : undefined}>
                              {m.letter}
                            </span>
                          )
                        })}
                      </button>
                    )
                  })}
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}

function shortKind(kind: string): string {
  switch (kind) {
    case 'TEMP_TABLE':
      return 'temp'
    case 'TABLE_VARIABLE':
      return 'var'
    case 'MATERIALIZED_VIEW':
      return 'mview'
    default:
      return kind.toLowerCase()
  }
}

function Legend(): React.JSX.Element {
  const order = ['CREATE', 'INSERT', 'UPDATE', 'DELETE', 'MERGE', 'READ', 'TRUNCATE', 'ALTER', 'DROP']
  return (
    <div className="ml-auto flex flex-wrap items-center gap-x-2.5 gap-y-1 text-xs text-ink-muted" aria-label="Legend">
      {order.map((a) => {
        const m = ACCESS_MARK[a]!
        return (
          <span key={a} className="flex items-center gap-1">
            <span className={`mark mark-${m.cls}`}>{m.letter}</span>
            {m.label.toLowerCase()}
          </span>
        )
      })}
    </div>
  )
}
