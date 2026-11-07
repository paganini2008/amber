import { useVirtualizer } from '@tanstack/react-virtual'
import clsx from 'clsx'
import { ArrowRight, GitBranch, ListOrdered, Pause, Play, Repeat, SkipBack, SkipForward, Square } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { useReducedMotion } from '@/hooks'
import { KindIcon, kindText } from '@/lib/kinds'
import { markFor } from '@/lib/lifecycle'
import { BASE_INTERVAL_MS, SPEEDS, branchOptions } from '@/lib/playback'
import { useStore } from '@/store'
import { Button, IconButton, Select, Switch } from '@/components/ui'
import { ColumnMapping } from './ColumnMapping'
import { groupRefs } from './StatementView'

/** Drives the playback clock. Mounted once at app level. */
export function PlaybackClock(): null {
  const playing = useStore((s) => s.playback.playing)
  const speed = useStore((s) => s.playback.speed)
  useEffect(() => {
    if (!playing) return
    const timer = setInterval(() => useStore.getState().stepPlayback(1), BASE_INTERVAL_MS / speed)
    return () => clearInterval(timer)
  }, [playing, speed])
  return null
}

/** Transport controls, shown above every view while playback is active. */
export function PlaybackBar(): React.JSX.Element | null {
  const pb = useStore((s) => s.playback)
  const index = useStore((s) => s.index)
  const setPlaying = useStore((s) => s.setPlaying)
  const stepPlayback = useStore((s) => s.stepPlayback)
  const stopPlayback = useStore((s) => s.stopPlayback)
  const seekPlayback = useStore((s) => s.seekPlayback)
  const setSpeed = useStore((s) => s.setSpeed)
  const decide = useStore((s) => s.decide)
  const stepInside = useStore((s) => s.stepInside)
  if (!pb.active || !index) return null
  const step = pb.plan[pb.cursor]
  const s = step ? index.byId.get(step.id) : undefined
  const pending = pb.pending !== null ? index.byId.get(pb.pending) : undefined

  return (
    <div className="shrink-0 border-b border-line bg-surface" role="region" aria-label="Execution playback">
      <div className="flex h-10 items-center gap-1 px-3">
        <IconButton label="Step back" onClick={() => stepPlayback(-1)} disabled={pb.cursor === 0}>
          <SkipBack size={15} />
        </IconButton>
        <button
          type="button"
          onClick={() => setPlaying(!pb.playing)}
          aria-label={pb.playing ? 'Pause' : 'Play'}
          className="no-drag flex h-7 w-7 items-center justify-center rounded-full bg-amber text-on-amber hover:brightness-105"
        >
          {pb.playing ? <Pause size={14} fill="currentColor" /> : <Play size={14} fill="currentColor" className="translate-x-px" />}
        </button>
        <IconButton label="Step forward" onClick={() => stepPlayback(1)} disabled={pb.cursor >= pb.plan.length - 1}>
          <SkipForward size={15} />
        </IconButton>
        <IconButton label="Stop playback" onClick={stopPlayback}>
          <Square size={13} />
        </IconButton>
        <input
          type="range"
          min={0}
          max={Math.max(0, pb.plan.length - 1)}
          value={pb.cursor}
          onChange={(e) => seekPlayback(Number(e.target.value))}
          aria-label="Playback position"
          className="mx-3 h-1 min-w-24 flex-1 cursor-pointer accent-[var(--amber)]"
        />
        <span className="w-28 shrink-0 text-right text-xs text-ink-muted tabular-nums">
          Step {pb.cursor + 1} of {pb.plan.length}
        </span>
        <Select
          label="Playback speed"
          value={String(pb.speed)}
          onChange={(v) => setSpeed(Number(v))}
          options={SPEEDS.map((x) => ({ value: String(x), label: `${x}×` }))}
          className="ml-2 w-[72px]"
        />
        {s?.executionOrder?.length ? (
          <Button size="sm" className="ml-2" onClick={() => stepInside(s.id)} title="Walk through this statement's logical order in the Structure view">
            <ListOrdered size={13} /> Step inside
          </Button>
        ) : null}
      </div>
      {pending ? (
        <div className="flex flex-wrap items-center gap-2 border-t border-line bg-amber-wash px-4 py-2 text-sm">
          <GitBranch size={14} className="text-amber-ink" />
          <span>
            {kindText(pending.kind)} at line {pending.range.start.line}. Amber has no runtime values, so choose what plays.
          </span>
          <DecisionButtons statementId={pending.id} onDecide={(d) => decide(pending.id, d)} />
        </div>
      ) : s && step?.inLoop ? (
        <div className="flex items-center gap-2 border-t border-line px-4 py-1.5 text-xs text-ink-muted">
          <Repeat size={12} /> Inside a loop: shown as one representative iteration.
        </div>
      ) : null}
    </div>
  )
}

function DecisionButtons({ statementId, onDecide }: { statementId: number; onDecide: (decision: string) => void }): React.JSX.Element {
  const index = useStore((s) => s.index)!
  const current = useStore((s) => s.playback.decisions.get(statementId))
  const s = index.byId.get(statementId)!
  const branches = branchOptions(s)
  const option = (value: string, label: string): React.JSX.Element => (
    <Button key={value} size="sm" variant={current === value ? 'primary' : 'default'} onClick={() => onDecide(value)}>
      {label}
    </Button>
  )
  return (
    <span className="flex flex-wrap gap-1.5">
      {option('all', 'Play children in order')}
      {branches.map((b) => option(b, `Play ${b.toLowerCase()} only`))}
      {option('skip', 'Skip')}
    </span>
  )
}

const ROW = 30

/** Center Playback view: the current step up close, and the full run order. */
export function PlaybackView(): React.JSX.Element {
  const index = useStore((s) => s.index)!
  const pb = useStore((s) => s.playback)
  const startPlayback = useStore((s) => s.startPlayback)
  const setPlaying = useStore((s) => s.setPlaying)
  const seekPlayback = useStore((s) => s.seekPlayback)
  const decide = useStore((s) => s.decide)
  const setAskAtBranches = useStore((s) => s.setAskAtBranches)
  const select = useStore((s) => s.select)
  const reduced = useReducedMotion()
  const parent = useRef<HTMLDivElement>(null)
  const virtualizer = useVirtualizer({
    count: pb.plan.length,
    getScrollElement: () => parent.current,
    estimateSize: () => ROW,
    overscan: 12
  })

  useEffect(() => {
    if (pb.active) virtualizer.scrollToIndex(pb.cursor, { align: 'center', behavior: reduced ? 'auto' : 'smooth' })
  }, [pb.active, pb.cursor, virtualizer, reduced])

  if (!pb.active) {
    return (
      <div className="flex h-full items-center justify-center p-8">
        <div className="max-w-[460px]">
          <h2 className="text-xl font-semibold">Execution playback</h2>
          <p className="mt-2 text-base text-ink-muted">
            Replays the script in logical order, one statement at a time, so you can watch tables fill up and empty. Nothing is
            executed. The outline, editor, lifecycle matrix and data-flow graph follow along.
          </p>
          <label className="mt-4 flex items-center gap-2 text-sm">
            <Switch checked={pb.askAtBranches} onChange={setAskAtBranches} label="Ask at each IF and CASE" />
            Pause and ask at each IF and CASE
          </label>
          <div className="mt-5 flex gap-2">
            <Button
              variant="primary"
              onClick={() => {
                startPlayback()
                setPlaying(true)
              }}
            >
              <Play size={14} fill="currentColor" /> Play from the start
            </Button>
            <Button onClick={startPlayback}>Step through manually</Button>
          </div>
        </div>
      </div>
    )
  }

  const step = pb.plan[pb.cursor]
  const s = step ? index.byId.get(step.id) : undefined
  const groups = s ? groupRefs(s.refs) : []
  const reads = groups.filter(([a]) => a === 'READ').flatMap(([, r]) => r)
  const writes = groups.filter(([a]) => a !== 'READ')

  return (
    <div className="flex h-full min-h-0">
      <div className="flex min-w-0 flex-[3] flex-col overflow-auto border-r border-line px-8 py-6">
        {s ? (
          <>
            <div className="flex items-center gap-2 text-sm text-ink-muted">
              <KindIcon kind={s.kind} size={16} className="text-amber-ink" />
              {kindText(s.kind)} · line {s.range.start.line}
              {step?.inLoop ? (
                <span className="ml-2 flex items-center gap-1">
                  <Repeat size={12} /> one representative iteration
                </span>
              ) : null}
            </div>
            <h2 className="mt-1 text-2xl font-semibold tracking-[-0.01em]">{s.summary || kindText(s.kind)}</h2>
            {step?.branch ? (
              <div className="mt-4 rounded-[6px] border border-line p-3 text-sm">
                <div className="mb-2 text-ink-muted">
                  The engine does not know which branch runs. Choose what playback does with the {s.children.length} statements inside.
                </div>
                <DecisionButtons statementId={s.id} onDecide={(d) => decide(s.id, d)} />
              </div>
            ) : null}
            <div className="mt-8 grid grid-cols-[1fr_auto_1fr] items-start gap-4">
              <div>
                <h3 className="mb-2 text-sm text-ink-muted">Reads</h3>
                {reads.length ? (
                  <ul className="space-y-1">
                    {reads.map((r) => (
                      <li key={r.key}>
                        <button type="button" className="code text-[13px] hover:underline" onClick={() => select({ type: 'object', key: r.key })}>
                          {r.name}
                        </button>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="text-sm text-ink-faint">Nothing</p>
                )}
              </div>
              <ArrowRight size={18} className="mt-7 text-amber" />
              <div>
                <h3 className="mb-2 text-sm text-ink-muted">Changes</h3>
                {writes.length ? (
                  <ul className="space-y-1">
                    {writes.flatMap(([access, refs]) =>
                      refs.map((r) => (
                        <li key={`${access}-${r.key}`} className="flex items-center gap-2">
                          <span className={`mark mark-${markFor(access).cls} lit`}>{markFor(access).letter}</span>
                          <button type="button" className="code text-[13px] hover:underline" onClick={() => select({ type: 'object', key: r.key })}>
                            {r.name}
                          </button>
                        </li>
                      ))
                    )}
                  </ul>
                ) : (
                  <p className="text-sm text-ink-faint">Nothing</p>
                )}
              </div>
            </div>
            <div className="mt-8">
              <ColumnMapping statementId={s.id} compact lit />
            </div>
          </>
        ) : null}
      </div>
      <div className="flex min-w-[280px] flex-[2] flex-col">
        <div className="flex h-9 shrink-0 items-center justify-between border-b border-line px-3 text-sm">
          <span className="font-medium">Run order</span>
          <label className="flex items-center gap-2 text-xs text-ink-muted">
            <Switch checked={pb.askAtBranches} onChange={setAskAtBranches} label="Ask at each IF and CASE" />
            Ask at IF and CASE
          </label>
        </div>
        <div ref={parent} className="min-h-0 flex-1 overflow-auto" role="list">
          <div style={{ height: virtualizer.getTotalSize(), position: 'relative' }}>
            {virtualizer.getVirtualItems().map((item) => {
              const p = pb.plan[item.index]!
              const st = index.byId.get(p.id)!
              const current = item.index === pb.cursor
              const played = item.index < pb.cursor
              const decision = pb.decisions.get(p.id)
              return (
                <button
                  key={p.id}
                  type="button"
                  role="listitem"
                  onClick={() => seekPlayback(item.index)}
                  className={clsx(
                    'absolute left-0 flex w-full items-center gap-1.5 pr-3 text-left text-sm',
                    current ? 'bg-amber-wash shadow-[inset_3px_0_0_var(--amber)]' : 'hover:bg-surface-hover',
                    !current && !played && 'text-ink-faint'
                  )}
                  style={{ top: item.start, height: ROW, paddingLeft: 12 + p.depth * 14 }}
                >
                  <KindIcon kind={st.kind} className={current ? 'text-amber-ink' : 'text-ink-muted'} />
                  <span className="min-w-0 flex-1 truncate">{st.summary || kindText(st.kind)}</span>
                  {p.loop ? <Repeat size={12} aria-label="Loop" /> : null}
                  {p.branch ? (
                    <span className="text-xs text-ink-muted">{decision === 'skip' ? 'skipped' : decision && decision !== 'all' ? decision.toLowerCase() : decision === 'all' ? 'all' : 'ask'}</span>
                  ) : null}
                  <span className="code w-8 text-right text-xs text-ink-faint">{st.range.start.line}</span>
                </button>
              )
            })}
          </div>
        </div>
      </div>
    </div>
  )
}
