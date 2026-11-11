import clsx from 'clsx'
import { ArrowLeftRight, ArrowRight, ChevronDown, ChevronRight, CircleCheck, ClipboardCopy, GitCompareArrows, RotateCw, X } from 'lucide-react'
import { useMemo, useState } from 'react'
import type { DiffLocated, DiffReason, DiffResult, Range, StatementPair } from '@shared/protocol'
import { compareWithFile, compareWithSaved, copyChangeSummary } from '@/actions'
import { SeverityIcon } from '@/components/StatusParts'
import { Button, IconButton, Spinner, Switch } from '@/components/ui'
import { DiffPanes } from '@/editor/DiffPanes'
import { fmt, t, tOr } from '@/i18n'
import { columnRows, countsText, lineText, noChanges, pairFor, pairKey, pairRows, REASONS_SHOWN, riskText, sameList, sortReasons, statusLabel, type ColumnRow } from '@/lib/diff'
import { findingTitle, KindIcon, kindText } from '@/lib/kinds'
import { useRefactor } from '@/lib/refactor'
import { useStore } from '@/store'

/** Center view: what a change to the script really changes, and how risky it is. */
export function DiffView(): React.JSX.Element {
  const diff = useStore((s) => s.diff)
  const doc = useStore((s) => s.doc)
  const dialect = useStore((s) => s.dialect)
  const close = useStore((s) => s.closeCompare)
  const swap = useStore((s) => s.swapCompare)
  const compare = useStore((s) => s.compare)

  if (diff.status === 'idle' || !diff.before || !diff.after) {
    return (
      <div className="flex h-full items-center justify-center p-8">
        <div className="max-w-[460px]">
          <GitCompareArrows size={22} className="text-ink-muted" />
          <h2 className="mt-3 text-lg font-semibold">{t('diff.empty.title')}</h2>
          <p className="mt-1 text-sm text-ink-muted">{t('diff.empty.text')}</p>
          <div className="mt-4 flex flex-wrap gap-2">
            <Button variant="primary" onClick={() => void compareWithSaved()} disabled={!doc?.path}>
              {t('diff.empty.compareSaved')}
            </Button>
            <Button onClick={() => void compareWithFile('older')}>{t('diff.empty.compareOlder')}</Button>
          </div>
          <p className="mt-3 text-xs text-ink-muted">{t('diff.empty.drop')}</p>
        </div>
      </div>
    )
  }

  const header = (
    <div className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-1 border-b border-line px-5 py-2">
      <h2 className="text-base font-semibold">{t('diff.title')}</h2>
      <span className="flex min-w-0 items-center gap-1.5 text-sm">
        <span className="text-xs text-ink-muted">{t('diff.old')}</span>
        <span className="code max-w-56 truncate text-[13px]" title={diff.before.path ?? diff.before.name}>
          {diff.before.name}
        </span>
        <ArrowRight size={13} className="shrink-0 text-ink-faint" />
        <span className="text-xs text-ink-muted">{t('diff.new')}</span>
        <span className="code max-w-56 truncate text-[13px]" title={diff.after.path ?? diff.after.name}>
          {diff.after.name}
        </span>
      </span>
      <div className="ml-auto flex items-center gap-1">
        <Button size="sm" variant="ghost" onClick={swap} disabled={diff.status === 'loading'} title={t('diff.swapTitle')}>
          <ArrowLeftRight size={13} /> {t('diff.swap')}
        </Button>
        <Button size="sm" variant="ghost" onClick={() => void compare(diff.before!, diff.after!)} disabled={diff.status === 'loading'}>
          <RotateCw size={13} /> {t('diff.compareAgain')}
        </Button>
        <Button size="sm" onClick={() => void copyChangeSummary()} disabled={!diff.result}>
          <ClipboardCopy size={13} /> {t('diff.copySummary')}
        </Button>
        <IconButton label={t('diff.close')} onClick={close}>
          <X size={15} />
        </IconButton>
      </div>
      <RefactorBanner after={diff.after.text} />
    </div>
  )

  if (diff.status === 'loading') {
    return (
      <div className="flex h-full flex-col">
        {header}
        <div className="flex flex-1 items-center justify-center gap-2 text-sm text-ink-muted">
          <Spinner /> {t('diff.comparing')}
        </div>
      </div>
    )
  }
  if (diff.status === 'error' || !diff.result) {
    return (
      <div className="flex h-full flex-col">
        {header}
        <div className="p-6 text-sm">
          <p className="text-risk-high">{diff.error}</p>
        </div>
      </div>
    )
  }
  return <DiffBody result={diff.result} header={header} dialect={dialect} />
}

function DiffBody({ result, header, dialect }: { result: DiffResult; header: React.ReactNode; dialect: string }): React.JSX.Element {
  const diff = useStore((s) => s.diff)
  const select = useStore((s) => s.selectDiffPair)
  const reasons = useMemo(() => sortReasons(result.summary.reasons ?? []), [result])
  const columns = useMemo(() => columnRows(result.lineage?.columns ?? []), [result])
  const allRows = useMemo(() => pairRows(result.statements ?? [], true), [result])
  const selectedPair = diff.selected ? (allRows.find((r) => r.key === diff.selected!.key)?.pair ?? null) : null

  /** Shows a change on both sides: its statement pair side by side, with its ranges marked. */
  const show = (c: DiffLocated): void => {
    const p = pairFor(result.statements ?? [], c.beforeStatement, c.afterStatement)
    if (p) select(pairKey(p), { before: c.beforeRange, after: c.afterRange })
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      {header}
      <div className={clsx('min-h-0 overflow-auto', selectedPair ? 'h-[48%] shrink-0 border-b border-line' : 'flex-1')}>
        <div className="mx-auto max-w-[980px] space-y-4 px-5 py-4">
          <RiskBanner result={result} />
          {reasons.length ? <ReasonList reasons={reasons} onShow={show} /> : null}
          {columns.length ? (
            <Section title={t('diff.columnLineage')} count={columns.length} hint={t('diff.columnLineageHint')}>
              <div className="space-y-2">
                {columns.map((c) => (
                  <ColumnCard key={c.key} row={c} onShow={() => show(c.change)} />
                ))}
              </div>
            </Section>
          ) : null}
          {result.lineage?.indirect?.length ? (
            <Section title={t('diff.indirect')} count={result.lineage.indirect.length} hint={t('diff.indirectHint')}>
              <ul className="divide-y divide-line">
                {result.lineage.indirect.map((i, n) => (
                  <ChangeRow key={n} onShow={() => show(i)} located={i}>
                    <span className={clsx('diff-sign', i.change === 'ADDED' ? 'diff-sign-add' : 'diff-sign-del')}>{i.change === 'ADDED' ? '+' : '−'}</span>
                    <span className="w-12 shrink-0 text-xs text-ink-muted">{tOr(`diff.role.${i.role}`, kindText(i.role))}</span>
                    <span className="min-w-0 flex-1">{i.message}</span>
                  </ChangeRow>
                ))}
              </ul>
            </Section>
          ) : null}
          <StatementsSection result={result} onSelect={(p) => select(pairKey(p))} selected={diff.selected?.key ?? null} />
          {result.objects?.length ? (
            <Section title={t('diff.objects')} count={result.objects.length}>
              <ul className="divide-y divide-line">
                {result.objects.map((o, n) => (
                  <ChangeRow key={n} onShow={() => show(o)} located={o}>
                    <span className="min-w-0 flex-1">{o.message}</span>
                  </ChangeRow>
                ))}
              </ul>
            </Section>
          ) : null}
          {result.shapes?.length ? (
            <Section title={t('diff.shapes')} count={result.shapes.length} defaultOpen={false}>
              <ul className="divide-y divide-line">
                {result.shapes.map((s, n) => (
                  <ChangeRow key={n} onShow={() => show(s)} located={s}>
                    <span className="min-w-0 flex-1">{s.message}</span>
                  </ChangeRow>
                ))}
              </ul>
            </Section>
          ) : null}
          {result.findings?.length ? (
            <Section title={t('diff.findings')} count={result.findings.length}>
              <ul className="divide-y divide-line">
                {result.findings.map((f, n) => (
                  <ChangeRow key={n} onShow={() => show(f)} located={f}>
                    <span className={clsx('diff-badge', f.change === 'NEW' ? 'diff-ADDED' : 'diff-REMOVED')}>{f.change === 'NEW' ? t('diff.findingNew') : t('diff.findingResolved')}</span>
                    <SeverityIcon severity={f.severity} size={13} />
                    <span className="min-w-0 flex-1">
                      {findingTitle(f)} <span className="code text-xs text-ink-faint">{f.rule}</span>
                    </span>
                  </ChangeRow>
                ))}
              </ul>
            </Section>
          ) : null}
          {result.controlFlow?.length ? (
            <Section title={t('diff.controlFlow')} count={result.controlFlow.length}>
              <ul className="divide-y divide-line">
                {result.controlFlow.map((c, n) => (
                  <ChangeRow key={n} onShow={() => show(c)} located={c}>
                    <span className="min-w-0 flex-1">
                      {c.message}
                      <span className="block text-xs text-ink-muted">
                        {(c.beforePath.length ? c.beforePath.join(' › ') : t('diff.topLevel')) + ' → ' + (c.afterPath.length ? c.afterPath.join(' › ') : t('diff.topLevel'))}
                      </span>
                    </span>
                  </ChangeRow>
                ))}
              </ul>
            </Section>
          ) : null}
          <p className="pb-2 text-xs text-ink-muted">{t('diff.footer', { duration: fmt.duration(result.elapsedMs) })}</p>
        </div>
      </div>
      {selectedPair ? <PairPanel pair={selectedPair} dialect={dialect} focus={diff.selected?.focus} onClose={() => select(null)} /> : null}
    </div>
  )
}

function RiskBanner({ result }: { result: DiffResult }): React.JSX.Element {
  const s = result.summary
  const sides = [result.before, result.after].filter((x) => x.fallback || x.unparsed > 0)
  if (noChanges(s)) {
    return (
      <section className="risk-banner risk-none" aria-label={t('diff.riskAria')}>
        <div className="flex items-start gap-3">
          <CircleCheck size={20} className="mt-0.5 shrink-0 text-ink-muted" aria-hidden />
          <div className="min-w-0">
            <div className="text-[17px] leading-6 font-semibold text-ink">{t('diff.noDifferences')}</div>
            <p className="mt-0.5 text-[14px] leading-[21px] text-ink">{t('diff.allUnchanged', { count: s.statements.unchanged })}</p>
          </div>
        </div>
      </section>
    )
  }
  return (
    <section className={clsx('risk-banner', `risk-${s.risk}`)} aria-label={t('diff.riskAria')}>
      <div className="flex items-start gap-3">
        <SeverityIcon severity={s.risk} size={20} />
        <div className="min-w-0">
          <div className="text-[17px] leading-6 font-semibold text-ink">{riskText(s.risk)}</div>
          <p className="mt-0.5 text-[14px] leading-[21px] text-ink">{s.headline}</p>
          <p className="mt-1.5 text-sm text-ink-muted">{t('diff.statementsLine', { counts: countsText(s.statements) })}</p>
          {s.notes?.map((n, i) => (
            <p key={i} className="mt-1 text-sm text-ink-muted">
              {n}
            </p>
          ))}
          {sides.map((x) => (
            <p key={x.name} className="mt-1 text-sm text-ink-muted">
              {x.unparsed ? t('diff.fallbackUnread', { name: x.name, count: x.unparsed }) : t('diff.fallback', { name: x.name })}
            </p>
          ))}
        </div>
      </div>
    </section>
  )
}

function ReasonList({ reasons, onShow }: { reasons: DiffReason[]; onShow: (r: DiffReason) => void }): React.JSX.Element {
  // Two unrelated files can give dozens of reasons; the most serious come first, the rest on request.
  const [all, setAll] = useState(false)
  const shown = all ? reasons : reasons.slice(0, REASONS_SHOWN)
  return (
    <section aria-label={t('diff.why')}>
      <h3 className="mb-1.5 text-base font-semibold">
        {t('diff.why')} <span className="text-sm font-normal text-ink-muted tabular-nums">{reasons.length}</span>
      </h3>
      <ol className="divide-y divide-line overflow-hidden rounded-[6px] border border-line bg-surface">
        {shown.map((r, i) => (
          <li key={i}>
            <button type="button" onClick={() => onShow(r)} className="flex w-full items-start gap-2.5 px-3 py-2 text-left hover:bg-surface-hover">
              <span className="mt-0.5">
                <SeverityIcon severity={r.level} size={14} />
              </span>
              <span className="sr-only">{riskText(r.level)}:</span>
              <span className="min-w-0 flex-1 text-[14px] leading-5 text-ink">{r.message}</span>
              <span className="shrink-0 pt-0.5 text-xs text-ink-muted tabular-nums">{lineText(r.beforeRange, r.afterRange)}</span>
            </button>
          </li>
        ))}
      </ol>
      {reasons.length > REASONS_SHOWN ? (
        <button type="button" className="mt-1.5 text-sm text-amber-ink hover:underline" onClick={() => setAll(!all)}>
          {all ? t('diff.showSerious') : t('diff.showAll', { count: reasons.length })}
        </button>
      ) : null}
    </section>
  )
}

function Section({ title, count, hint, defaultOpen = true, children }: { title: string; count: number; hint?: string; defaultOpen?: boolean; children: React.ReactNode }): React.JSX.Element {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <section>
      <button type="button" className="flex w-full items-baseline gap-2 py-1 text-left" onClick={() => setOpen(!open)} aria-expanded={open}>
        {open ? <ChevronDown size={14} className="self-center text-ink-muted" /> : <ChevronRight size={14} className="self-center text-ink-muted" />}
        <h3 className="text-base font-semibold">{title}</h3>
        <span className="text-sm text-ink-muted tabular-nums">{count}</span>
        {hint ? <span className="truncate text-xs text-ink-muted">{hint}</span> : null}
      </button>
      {open ? <div className="mt-1 pl-5">{children}</div> : null}
    </section>
  )
}

function ChangeRow({ children, onShow, located }: { children: React.ReactNode; onShow: () => void; located: DiffLocated }): React.JSX.Element {
  return (
    <li>
      <button type="button" onClick={onShow} className="flex w-full items-center gap-2.5 py-1.5 pr-2 text-left text-sm hover:bg-surface-hover">
        {children}
        <span className="shrink-0 text-xs text-ink-muted tabular-nums">{lineText(located.beforeRange, located.afterRange)}</span>
      </button>
    </li>
  )
}

function SourceList({ items, marked, sign }: { items: string[]; marked: string[]; sign: '+' | '−' }): React.JSX.Element {
  if (!items.length) return <span className="text-sm text-ink-muted">{t('diff.noSource')}</span>
  return (
    <span className="flex flex-wrap gap-1.5">
      {items.map((s) => {
        const hit = marked.includes(s)
        return (
          <span key={s} className={clsx('code rounded-[4px] border px-1.5 text-[13px] leading-[22px]', hit ? (sign === '+' ? 'diff-chip-add' : 'diff-chip-del') : 'border-line bg-surface text-ink-muted')}>
            {hit ? <span aria-hidden className="mr-1 font-semibold">{sign}</span> : null}
            {s}
          </span>
        )
      })}
    </span>
  )
}

function ColumnCard({ row, onShow }: { row: ColumnRow; onShow: () => void }): React.JSX.Element {
  const transformChanged = !sameList(row.beforeTransforms, row.afterTransforms)
  return (
    <article className="rounded-[7px] border border-line bg-surface px-3.5 py-2.5">
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <h4 className="code text-[14px] font-semibold text-ink">
          {row.target}.<span className="text-amber-ink">{row.column}</span>
        </h4>
        {row.kinds.map((k) => (
          <span key={k} className="rounded-[3px] bg-surface-2 px-1.5 text-xs leading-[18px] text-ink-muted">
            {tOr(`diff.columnKind.${k}`, kindText(k))}
          </span>
        ))}
        <button type="button" onClick={onShow} className="ml-auto text-xs text-amber-ink hover:underline">
          {t('diff.showBoth')}
        </button>
      </div>
      <dl className="mt-2 grid grid-cols-[64px_1fr] items-center gap-x-3 gap-y-1.5">
        <dt className="text-sm text-ink-muted">{t('diff.before')}</dt>
        <dd>
          <SourceList items={row.before} marked={row.removed} sign="−" />
        </dd>
        <dt className="text-sm text-ink-muted">{t('diff.after')}</dt>
        <dd>
          <SourceList items={row.after} marked={row.added} sign="+" />
        </dd>
        {transformChanged ? (
          <>
            <dt className="text-sm text-ink-muted">{t('diff.derived')}</dt>
            <dd className="text-sm text-ink">
              {row.beforeTransforms.map((x) => x.toLowerCase()).join(', ') || t('diff.directly')} <ArrowRight size={12} className="inline text-ink-faint" />{' '}
              {row.afterTransforms.map((x) => x.toLowerCase()).join(', ') || t('diff.directly')}
            </dd>
          </>
        ) : null}
      </dl>
    </article>
  )
}

function StatementsSection({ result, onSelect, selected }: { result: DiffResult; onSelect: (p: StatementPair) => void; selected: string | null }): React.JSX.Element {
  const showUnchanged = useStore((s) => s.diff.showUnchanged)
  const setShowUnchanged = useStore((s) => s.setShowUnchanged)
  const rows = useMemo(() => pairRows(result.statements ?? [], showUnchanged), [result, showUnchanged])
  const c = result.summary.statements
  const changed = c.modified + c.added + c.removed + c.moved + c.formatOnly
  return (
    <Section title={t('diff.statements')} count={changed}>
      <label className="mb-1 flex items-center gap-2 text-xs text-ink-muted">
        <Switch checked={showUnchanged} onChange={setShowUnchanged} label={t('diff.showUnchanged')} />
        {t('diff.showUnchangedCount', { count: c.unchanged })}
      </label>
      {rows.length === 0 ? <p className="py-1 text-sm text-ink-muted">{t('diff.noStatementChanged')}</p> : null}
      <ul className="divide-y divide-line">
        {rows.map((r) => {
          const p = r.pair
          const summary = p.afterSummary ?? p.beforeSummary ?? kindText(p.kind)
          return (
            <li key={r.key}>
              <button
                type="button"
                onClick={() => onSelect(p)}
                className={clsx(
                  'flex w-full items-center gap-2.5 py-1.5 pr-2 text-left text-sm',
                  selected === r.key ? 'bg-amber-wash shadow-[inset_3px_0_0_var(--amber)]' : 'hover:bg-surface-hover',
                  p.status === 'UNCHANGED' && 'text-ink-muted'
                )}
                style={{ paddingLeft: 4 + r.depth * 16 }}
              >
                <span className={clsx('diff-badge', `diff-${p.status}`)}>{statusLabel(p.status)}</span>
                <KindIcon kind={p.kind} className="shrink-0 text-ink-muted" />
                <span className="min-w-0 flex-1 truncate">{summary}</span>
                {p.status === 'MODIFIED' ? <span className="shrink-0 text-xs text-ink-muted tabular-nums">{t('diff.alike', { percent: fmt.percent(p.similarity) })}</span> : null}
                {p.reordered ? <span className="shrink-0 text-xs text-ink-muted">{t('diff.moved')}</span> : null}
                <span className="w-24 shrink-0 text-right text-xs text-ink-muted tabular-nums">{lineText(p.beforeRange, p.afterRange)}</span>
              </button>
            </li>
          )
        })}
      </ul>
    </Section>
  )
}

function PairPanel({ pair, dialect, focus, onClose }: { pair: StatementPair; dialect: string; focus?: { before: Range | null; after: Range | null }; onClose: () => void }): React.JSX.Element {
  const before = useStore((s) => s.diff.before)
  const after = useStore((s) => s.diff.after)
  return (
    <section className="flex min-h-0 flex-1 flex-col" aria-label={t('diff.sideBySide')}>
      <div className="flex shrink-0 items-center gap-2.5 border-b border-line bg-surface px-4 py-1.5 text-sm">
        <span className={clsx('diff-badge', `diff-${pair.status}`)}>{statusLabel(pair.status)}</span>
        <KindIcon kind={pair.kind} className="text-ink-muted" />
        <span className="min-w-0 truncate text-ink">{pair.afterSummary ?? pair.beforeSummary ?? kindText(pair.kind)}</span>
        {pair.status === 'FORMAT_ONLY' ? <span className="text-xs text-ink-muted">{t('diff.formatOnlyNote')}</span> : null}
        {pair.status === 'MODIFIED' && pair.hunks.length ? (
          <span className="text-xs text-ink-muted">{t('diff.changes', { count: pair.hunks.length })}</span>
        ) : null}
        <IconButton label={t('diff.closeSideBySide')} className="ml-auto h-6 w-6" onClick={onClose}>
          <X size={13} />
        </IconButton>
      </div>
      <div className="grid shrink-0 grid-cols-2 divide-x divide-line border-b border-line bg-surface-2 text-xs text-ink-muted">
        <span className="truncate px-3 py-0.5">{t('diff.oldName', { name: before?.name ?? '' })}</span>
        <span className="truncate px-3 py-0.5">{t('diff.newName', { name: after?.name ?? '' })}</span>
      </div>
      <DiffPanes before={before?.text ?? ''} after={after?.text ?? ''} dialect={dialect} pair={pair} focus={focus} />
    </section>
  )
}

/** When Change impact shows a prepared refactoring: what it did, and Apply / Discard. */
function RefactorBanner({ after }: { after: string }): React.JSX.Element | null {
  const state = useRefactor((s) => s.state)
  const apply = useRefactor((s) => s.apply)
  const reset = useRefactor((s) => s.reset)
  const close = useStore((s) => s.closeCompare)
  if (state.status !== 'ready' || state.after !== after) return null
  const count = state.applied.reduce((n, a) => n + a.count, 0)
  return (
    <div className="flex basis-full flex-wrap items-center gap-x-3 gap-y-1.5 rounded-[6px] border border-amber bg-amber-wash px-3 py-2 text-sm">
      <span className="font-medium text-ink">{t('refactor.banner.title')}</span>
      <span className="text-ink">
        {count ? t('refactor.banner.rewrites', { count, passes: state.passes }) : t('refactor.banner.noRewrites')}
        {state.formatted ? ` ${t('refactor.banner.formatted')}` : ''}
      </span>
      {state.check ? (
        <span className={clsx('basis-full', state.check.logicKept ? 'text-ink' : 'font-medium text-risk-high')} title={state.check.differences.join('\n')}>
          {state.check.logicKept ? t('refactor.banner.logicKept') : t('refactor.banner.logicChanged')}
        </span>
      ) : null}
      {state.applied.length ? (
        <span className="min-w-0 truncate text-ink-muted" title={state.applied.map((a) => `${a.title} ×${a.count}`).join('\n')}>
          {state.applied.map((a) => (a.count > 1 ? `${a.title} ×${a.count}` : a.title)).join(t('common.listSeparator'))}
        </span>
      ) : null}
      <span className="flex-1" />
      <Button
        size="sm"
        variant="ghost"
        onClick={() => {
          reset()
          close()
        }}
      >
        {t('refactor.banner.discard')}
      </Button>
      <Button size="sm" variant="primary" disabled={state.check !== null && !state.check.logicKept} onClick={apply}>
        {t('refactor.banner.apply')}
      </Button>
    </div>
  )
}
