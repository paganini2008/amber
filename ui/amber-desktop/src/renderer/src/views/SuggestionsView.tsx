import clsx from 'clsx'
import { ChevronDown, ChevronRight, Columns2, Sparkles, Wand2 } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { AiPanel } from '@/components/DetailsPanel'
import { SafetyBadge, useFreshRewrites } from '@/components/Fixes'
import { RuleBasedBadge, useFreshOptimize } from '@/components/Optimized'
import { SeverityIcon } from '@/components/StatusParts'
import { Button, Dialog, Spinner } from '@/components/ui'
import { DiffPanes } from '@/editor/DiffPanes'
import { t } from '@/i18n'
import { checkLabelText, unmaskText } from '@/lib/ai'
import { applicableSql, useAiFixes, type AiFixState, type AiFixSuggestion } from '@/lib/aiFixes'
import { useRefactor } from '@/lib/refactor'
import { findingSuggestion, findingTitle, KindIcon, kindText } from '@/lib/kinds'
import { wholePair } from '@/lib/optimize'
import { buildSuggestions, filterSuggestions, type StatementSuggestions, type SuggestionEntry, type SuggestionFilter } from '@/lib/suggestions'
import { useStore } from '@/store'

/**
 * Suggestions: every finding of the script with what fixes it. Local fixes (rule-based, verified, no AI)
 * come first and apply in one click; what Amber cannot fix itself is offered to the AI, whose answer is
 * checked by the engine before it can be applied. Applying edits the editor as one undoable step.
 */
export function SuggestionsView(): React.JSX.Element {
  const findings = useStore((s) => s.analysis?.findings)
  const doc = useStore((s) => s.doc)
  const rewritesState = useStore((s) => s.rewrites.status)
  const applyRewrites = useStore((s) => s.applyRewrites)
  const rewrites = useFreshRewrites()
  const optimized = useFreshOptimize()
  const [filter, setFilter] = useState<SuggestionFilter>('all')
  const model = useMemo(() => buildSuggestions(findings ?? [], rewrites, optimized), [findings, rewrites, optimized])
  const visible = useMemo(() => filterSuggestions(model, filter), [model, filter])

  if (!findings || !findings.length) {
    return (
      <div className="h-full space-y-4 overflow-auto px-6 py-4">
        <p className="text-sm text-ink-muted">{findings ? t('suggestions.none') : t('suggestions.notAvailable')}</p>
        <AiPanel />
      </div>
    )
  }
  const unfixed = model.entryCount - model.fixableCount

  return (
    <div className="h-full overflow-auto">
      <header className="sticky top-0 z-[2] border-b border-line bg-canvas px-6 pt-4 pb-3">
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <h2 className="text-lg font-semibold text-ink">{t('suggestions.title', { count: model.findingCount })}</h2>
          <span className="text-sm text-ink-muted">{t('suggestions.inStatements', { count: model.statements.length })}</span>
          <span className="flex-1" />
          {rewritesState === 'loading' ? (
            <span className="flex items-center gap-1.5 text-sm text-ink-muted">
              <Spinner size={12} /> {t('suggestions.loadingFixes')}
            </span>
          ) : null}
          <RefactorButton />
          {model.safeFixes.length > 0 ? (
            <Button variant="primary" disabled={Boolean(doc?.edited)} title={t('fixes.applyAllTitle')} onClick={() => applyRewrites(model.safeFixes.map((r) => r.id))}>
              <Wand2 size={14} aria-hidden /> {t('suggestions.applySafe', { count: model.safeFixes.length })}
            </Button>
          ) : null}
        </div>
        <TriageBar fixable={model.fixableCount} unfixed={unfixed} />
        <div className="mt-3 flex flex-wrap gap-1.5" role="radiogroup" aria-label={t('suggestions.filter')}>
          {(
            [
              ['all', t('suggestions.filterAll', { count: model.entryCount })],
              ['fixable', t('suggestions.filterFixable', { count: model.fixableCount })],
              ['unfixed', t('suggestions.filterUnfixed', { count: unfixed })]
            ] as const
          ).map(([value, label]) => (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={filter === value}
              onClick={() => setFilter(value)}
              className={clsx(
                'rounded-full border px-3 py-0.5 text-sm transition-colors',
                filter === value ? 'border-amber bg-amber-wash text-ink' : 'border-line text-ink-muted hover:border-line-strong hover:text-ink'
              )}
            >
              {label}
            </button>
          ))}
        </div>
        {doc?.edited ? <p className="mt-2 text-sm text-ink-muted">{t('suggestions.stale')}</p> : null}
      </header>
      <div className="space-y-4 px-6 py-4">
        {visible.map((s) => (
          <StatementCard key={s.statement} group={s} />
        ))}
        {!visible.length ? <p className="text-sm text-ink-muted">{t('suggestions.filterEmpty')}</p> : null}
        {/* Free questions to the AI about the script or the selected statement, after the findings. */}
        <AiPanel />
      </div>
    </div>
  )
}

/** Where the findings stand: fixed here (amber) versus left to the AI or a person (muted). */
function TriageBar({ fixable, unfixed }: { fixable: number; unfixed: number }): React.JSX.Element | null {
  const total = fixable + unfixed
  if (!total) return null
  return (
    <div className="mt-3">
      <div className="flex h-2 gap-[2px] overflow-hidden rounded-full" aria-hidden>
        {fixable ? <div className="bg-amber" style={{ flexGrow: fixable }} /> : null}
        {unfixed ? <div className="bg-line-strong" style={{ flexGrow: unfixed }} /> : null}
      </div>
      <div className="mt-1.5 flex flex-wrap gap-x-5 text-sm">
        <span className="flex items-center gap-1.5 text-ink">
          <span className="inline-block h-2 w-2 rounded-full bg-amber" aria-hidden />
          {t('suggestions.triageFixable', { count: fixable })}
        </span>
        <span className="flex items-center gap-1.5 text-ink">
          <span className="inline-block h-2 w-2 rounded-full bg-line-strong" aria-hidden />
          {t('suggestions.triageUnfixed', { count: unfixed })}
        </span>
      </div>
    </div>
  )
}

function StatementCard({ group }: { group: StatementSuggestions }): React.JSX.Element {
  const index = useStore((s) => s.index)
  const selectStatement = useStore((s) => s.selectStatement)
  const preview = useStore((s) => s.previewOptimized)
  const applyOptimized = useStore((s) => s.applyOptimized)
  const ai = useAiFixes((s) => s.fixes[group.statement])
  const startAi = useAiFixes((s) => s.start)
  const statement = index?.byId.get(group.statement)
  const opt = group.optimized
  return (
    <section className="rounded-[8px] border border-line bg-surface" aria-label={statement?.summary || t('fixes.statement', { number: group.statement + 1 })}>
      <button
        type="button"
        onClick={() => statement && selectStatement(statement.id, 'view', true)}
        className="flex w-full items-center gap-2 border-b border-line px-4 py-2.5 text-left hover:bg-surface-hover"
      >
        {statement ? <KindIcon kind={statement.kind} size={14} className="shrink-0 text-ink-muted" /> : null}
        <span className="min-w-0 flex-1 truncate font-medium text-ink">{statement?.summary || (statement ? kindText(statement.kind) : `#${group.statement + 1}`)}</span>
        {statement ? <span className="shrink-0 text-sm text-ink-muted">{t('common.line', { line: statement.range.start.line })}</span> : null}
      </button>
      {!ai ? (
        <div className="flex justify-end border-b border-line px-4 py-1.5">
          <Button size="sm" variant="ghost" title={t('refactor.aiHint')} onClick={() => void startAi(group.statement, group.entries, 'refactor')}>
            <Sparkles size={13} aria-hidden /> {t('refactor.ai')}
          </Button>
        </div>
      ) : null}

      {opt ? (
        <div className="flex flex-wrap items-center gap-2 border-b border-line bg-amber-wash/50 px-4 py-2 text-sm">
          <Wand2 size={14} className="text-amber-ink" aria-hidden />
          <span className="text-ink">{t('suggestions.optimized', { count: opt.applied.length })}</span>
          <SafetyBadge safety={opt.safety} />
          <RuleBasedBadge />
          <span className="flex-1" />
          <Button size="sm" onClick={() => preview({ kind: 'statement', statement: opt.statement })}>
            <Columns2 size={13} aria-hidden /> {t('suggestions.compare')}
          </Button>
          <Button size="sm" variant="primary" disabled={opt.check?.label === 'INVALID_SQL'} onClick={() => applyOptimized([opt.statement])}>
            {t('suggestions.apply')}
          </Button>
        </div>
      ) : null}

      <ul className="divide-y divide-line">
        {group.entries.map((e) => (
          <EntryRow key={e.key} entry={e} />
        ))}
      </ul>

      {ai ? <AiFixBox statement={group.statement} state={ai} /> : null}

      {group.unfixed.length && !ai ? (
        <div className="flex flex-wrap items-center gap-2 border-t border-line px-4 py-2.5 text-sm">
          <Sparkles size={14} className="text-ink-muted" aria-hidden />
          <span className="text-ink-muted">{t('suggestions.aiHint', { count: group.unfixed.length })}</span>
          <span className="flex-1" />
          <Button size="sm" onClick={() => void startAi(group.statement, group.unfixed)}>
            <Sparkles size={13} aria-hidden /> {t('suggestions.aiFixAll', { count: group.unfixed.length })}
          </Button>
        </div>
      ) : null}
    </section>
  )
}

function EntryRow({ entry }: { entry: SuggestionEntry }): React.JSX.Element {
  const [open, setOpen] = useState(false)
  const selectFinding = useStore((s) => s.selectFinding)
  const selectedFinding = useStore((s) => s.selectedFinding)
  const previewRewrite = useStore((s) => s.previewRewrite)
  const previewOptimized = useStore((s) => s.previewOptimized)
  const applyRewrites = useStore((s) => s.applyRewrites)
  const applyFindingSql = useStore((s) => s.applyFindingSql)
  const startAi = useAiFixes((s) => s.start)
  const aiBusy = useAiFixes((s) => Boolean(s.fixes[entry.statement]))
  const first = entry.findings[0]!.finding
  const advice = findingSuggestion(first)
  const fix = entry.fixes[0]
  return (
    <li>
      <div className="flex items-start gap-3 px-4 py-2.5">
        <button type="button" onClick={() => setOpen(!open)} aria-expanded={open} className="mt-0.5 text-ink-muted hover:text-ink" aria-label={t('suggestions.details')}>
          {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        </button>
        <span className="mt-0.5">
          <SeverityIcon severity={entry.severity} size={14} />
        </span>
        <button type="button" className="min-w-0 flex-1 text-left" onClick={() => selectFinding(entry.findings[0]!.index)}>
          <span className="flex flex-wrap items-baseline gap-x-2">
            <span className="font-medium text-ink">{findingTitle(first)}</span>
            {entry.findings.length > 1 ? <span className="text-sm text-ink-muted">{t('suggestions.places', { count: entry.findings.length })}</span> : null}
            <span className="code text-xs text-ink-faint">{entry.rule}</span>
          </span>
          <span className="mt-0.5 block text-sm text-ink-muted">{first.message}</span>
        </button>
        <div className="flex shrink-0 flex-wrap items-center justify-end gap-1.5">
          {entry.fix === 'quickFix' && fix && fix.safety === 'CHANGES_BEHAVIOR_IN_EDGE_CASES' ? (
            <>
              {/* Can change the result in an edge case: never applied from the list, only after review. */}
              <SafetyBadge safety={fix.safety} />
              <Button size="sm" onClick={() => previewRewrite(fix.id)}>
                <Columns2 size={13} aria-hidden /> {t('suggestions.review')}
              </Button>
            </>
          ) : entry.fix === 'quickFix' && fix ? (
            <>
              <SafetyBadge safety={fix.safety} />
              <Button size="sm" onClick={() => previewRewrite(fix.id)}>
                <Columns2 size={13} aria-hidden /> {t('suggestions.compare')}
              </Button>
              <Button size="sm" variant="primary" onClick={() => applyRewrites([fix.id])}>
                {t('suggestions.apply')}
              </Button>
            </>
          ) : entry.fix === 'findingSql' && entry.findingSql !== null ? (
            <>
              <RuleBasedBadge />
              <Button size="sm" onClick={() => previewOptimized({ kind: 'finding', finding: entry.findingSql! })}>
                <Columns2 size={13} aria-hidden /> {t('suggestions.compare')}
              </Button>
              <Button size="sm" variant="primary" onClick={() => applyFindingSql(entry.findingSql!)}>
                {t('suggestions.apply')}
              </Button>
            </>
          ) : (
            <>
              <span className="text-xs text-ink-muted">{t('suggestions.noLocalFix')}</span>
              <Button size="sm" disabled={aiBusy} onClick={() => void startAi(entry.statement, [entry])}>
                <Sparkles size={13} aria-hidden /> {t('suggestions.aiFix')}
              </Button>
            </>
          )}
        </div>
      </div>
      {open ? (
        <div className="space-y-2 pr-4 pb-3 pl-[60px] text-sm">
          {advice ? (
            <p className="text-ink">
              <span className="text-ink-muted">{t('details.finding.suggestion')}</span>
              {advice}
            </p>
          ) : null}
          <ul className="space-y-0.5">
            {entry.findings.map(({ finding, index }) => (
              <li key={index}>
                <button
                  type="button"
                  onClick={() => selectFinding(index)}
                  className={clsx('flex w-full gap-3 rounded-[4px] px-2 py-1 text-left', selectedFinding === index ? 'bg-amber-wash' : 'hover:bg-surface-hover')}
                >
                  <span className="code w-16 shrink-0 text-xs text-ink-muted">
                    {finding.range.start.line}:{finding.range.start.column}
                  </span>
                  <span className="min-w-0 text-ink">{finding.message}</span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </li>
  )
}

/** The AI's work on one statement: what will be sent, the wait, then checked rewrites to compare and apply. */
function AiFixBox({ statement, state }: { statement: number; state: AiFixState }): React.JSX.Element {
  const settings = useStore((s) => s.settings)
  const send = useAiFixes((s) => s.send)
  const cancel = useAiFixes((s) => s.cancel)
  const provider = settings?.activeAiProvider ?? null
  const model = provider ? settings?.ai[provider].model : null
  const endpoint = provider === 'openai' ? settings?.ai.openai.baseUrl : settings?.ai.ollama.baseUrl
  const [compare, setCompare] = useState<AiFixSuggestion | null>(null)
  // Started from a row or the statement footer: bring the box into view, and again when the answer arrives.
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => {
    box.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [state.status])

  return (
    <div ref={box} className="space-y-2.5 border-t border-line bg-surface-2 px-4 py-3 text-sm" aria-live="polite">
      <div className="flex items-center gap-2">
        <Sparkles size={14} className="text-amber-ink" aria-hidden />
        <span className="font-medium text-ink">{'mode' in state && state.mode === 'refactor' ? t('refactor.aiTitle') : t('suggestions.ai.title')}</span>
        {model ? <span className="code text-xs text-ink-muted">{model}</span> : null}
        <span className="flex-1" />
        <Button size="sm" variant="ghost" onClick={() => cancel(statement)}>
          {state.status === 'done' || state.status === 'error' || state.status === 'unavailable' ? t('common.close') : t('common.cancel')}
        </Button>
      </div>

      {state.status === 'building' ? <Busy text={t('suggestions.ai.building')} /> : null}
      {state.status === 'waiting' ? <Busy text={t('suggestions.ai.waiting', { model: model ?? '' })} /> : null}
      {state.status === 'checking' ? <Busy text={t('suggestions.ai.checking')} /> : null}
      {state.status === 'unavailable' ? <p className="text-ink-muted">{t('ai.unavailableText')}</p> : null}
      {state.status === 'error' ? <p className="text-risk-high">{state.message}</p> : null}

      {state.status === 'confirm' ? (
        <div className="space-y-2">
          <p className="text-ink">
            {t('ai.preview.tokens', { tokens: state.context.tokenEstimate, endpoint: endpoint ?? '' })}{' '}
            {Object.keys(state.mask).length ? t('ai.preview.masked', { count: Object.keys(state.mask).length }) : t('ai.preview.notMasked')}
          </p>
          <details>
            <summary className="cursor-default text-ink-muted hover:text-ink">{t('suggestions.ai.showContext')}</summary>
            <pre className="code selectable mt-1 max-h-56 overflow-auto rounded-[5px] border border-line bg-canvas p-2 text-[12.5px] whitespace-pre-wrap">{state.context.user}</pre>
          </details>
          <Button size="sm" variant="primary" onClick={() => void send(statement)}>
            {t('ai.send')}
          </Button>
        </div>
      ) : null}

      {state.status === 'done' ? (
        state.suggestions.length ? (
          <div className="space-y-2">
            {state.suggestions.map((s, i) => (
              <AiSuggestionCard key={i} item={s} mask={state.mask} statement={statement} onCompare={() => setCompare(s)} />
            ))}
          </div>
        ) : (
          <div className="space-y-1">
            <p className="text-ink-muted">{t('suggestions.ai.noRewrite')}</p>
            {state.answer.trim() ? (
              <details>
                <summary className="cursor-default text-ink-muted hover:text-ink">{t('suggestions.ai.showAnswer')}</summary>
                <pre className="selectable mt-1 max-h-56 overflow-auto rounded-[5px] border border-line bg-canvas p-2 text-[12.5px] whitespace-pre-wrap">
                  {unmaskText(state.answer, state.mask)}
                </pre>
              </details>
            ) : null}
          </div>
        )
      ) : null}

      {compare && state.status === 'done' ? <AiCompareDialog statement={statement} item={compare} text={state.text} onClose={() => setCompare(null)} /> : null}
    </div>
  )
}

function Busy({ text }: { text: string }): React.JSX.Element {
  return (
    <p className="flex items-center gap-2 text-ink-muted">
      <Spinner size={12} /> {text}
    </p>
  )
}

function AiSuggestionCard({ item, mask, statement, onCompare }: { item: AiFixSuggestion; mask: Record<string, string>; statement: number; onCompare: () => void }): React.JSX.Element {
  const apply = useStore((s) => s.applyStatementSql)
  const edited = useStore((s) => Boolean(s.doc?.edited))
  const { suggestion, check } = item
  const text = (v: string | undefined): string | undefined => (v ? unmaskText(v, mask) : undefined)
  const sql = applicableSql(item)
  const label = check?.ok ? check.result.label : null
  return (
    <article className="space-y-1.5 rounded-[6px] border border-line bg-surface p-3">
      <div className="flex flex-wrap items-start gap-2">
        <h4 className="min-w-0 flex-1 font-medium text-ink">{text(suggestion.title) ?? t('ai.suggestion.fallbackTitle')}</h4>
        {label ? (
          <span
            className={clsx(
              'shrink-0 rounded-[3px] border px-1.5 text-xs leading-[18px]',
              label === 'STRUCTURE_PRESERVED' && 'border-line-strong text-ink',
              label === 'STRUCTURE_CHANGED' && 'border-risk-medium text-risk-medium',
              label === 'INVALID_SQL' && 'border-risk-high text-risk-high'
            )}
          >
            {checkLabelText(label)}
          </span>
        ) : null}
      </div>
      {text(suggestion.rationale) ? <p className="selectable text-ink">{text(suggestion.rationale)}</p> : null}
      {text(suggestion.risk) ? (
        <p className="selectable text-ink-muted">
          {t('ai.suggestion.risk')}
          {t('common.colon')}
          {text(suggestion.risk)}
        </p>
      ) : null}
      {check?.ok && check.result.label === 'STRUCTURE_CHANGED' ? <p className="text-risk-medium">{t('suggestions.ai.changed')}</p> : null}
      {check?.ok && check.result.label === 'STRUCTURE_CHANGED' && check.result.differences.length ? (
        <ul className="list-disc space-y-0.5 pl-4 text-risk-medium">
          {check.result.differences.map((d, i) => (
            <li key={i} className="selectable">
              {d}
            </li>
          ))}
        </ul>
      ) : null}
      {check && !check.ok ? <p className="text-ink-muted">{check.message}</p> : null}
      {check?.ok && check.result.label === 'INVALID_SQL' ? <p className="text-risk-high">{t('suggestions.ai.invalid')}</p> : null}
      {suggestion.rewrittenSql ? (
        <div className="flex flex-wrap gap-1.5 pt-1">
          <Button size="sm" disabled={!check?.ok || check.result.label === 'INVALID_SQL'} onClick={onCompare}>
            <Columns2 size={13} aria-hidden /> {t('suggestions.compare')}
          </Button>
          <Button size="sm" variant="primary" disabled={!sql || edited} title={t('suggestions.ai.applyTitle')} onClick={() => sql && apply(statement, sql)}>
            {t('suggestions.apply')}
          </Button>
        </div>
      ) : null}
    </article>
  )
}

function AiCompareDialog({ statement, item, text, onClose }: { statement: number; item: AiFixSuggestion; text: string; onClose: () => void }): React.JSX.Element {
  const index = useStore((s) => s.index)
  const dialect = useStore((s) => s.dialect)
  const apply = useStore((s) => s.applyStatementSql)
  const range = index?.byId.get(statement)?.range
  const sql = applicableSql(item)
  // A rewrite that changes the logic can still be compared, never applied.
  const shown = item.check?.ok && item.check.result.label !== 'INVALID_SQL' ? item.check.result.sql : null
  const before = range ? text.slice(range.start.offset, range.end.offset) : ''
  const pair = useMemo(() => (shown ? wholePair(before, shown, statement) : null), [before, shown, statement])
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={item.suggestion.title ?? t('ai.suggestion.fallbackTitle')}
      description={t('suggestions.ai.compareDescription')}
      width={1040}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('common.close')}
          </Button>
          <Button
            variant="primary"
            disabled={!sql}
            onClick={() => {
              if (sql) apply(statement, sql)
              onClose()
            }}
          >
            {t('suggestions.apply')}
          </Button>
        </>
      }
    >
      {shown && pair ? (
        <div className="flex flex-col">
          {!sql ? <p className="border-b border-line px-5 py-2 text-sm text-risk-medium">{t('suggestions.ai.changed')}</p> : null}
          <div className="grid shrink-0 grid-cols-2 divide-x divide-line border-b border-line bg-surface-2 text-xs text-ink-muted">
            <span className="px-3 py-0.5">{t('optimize.dialog.now')}</span>
            <span className="px-3 py-0.5">{t('suggestions.ai.after')}</span>
          </div>
          <div className="h-[56vh] min-h-[220px]">
            <DiffPanes before={before} after={shown} dialect={dialect} pair={pair} />
          </div>
        </div>
      ) : (
        <p className="p-5 text-sm text-ink-muted">{t('suggestions.ai.invalid')}</p>
      )}
    </Dialog>
  )
}

/** "Refactor script": same-result rewrites pass after pass, then the layout; the result opens in Change impact. */
function RefactorButton(): React.JSX.Element {
  const [open, setOpen] = useState(false)
  const [format, setFormat] = useState(true)
  const state = useRefactor((s) => s.state)
  const run = useRefactor((s) => s.run)
  const edited = useStore((s) => Boolean(s.doc?.edited))
  const toast = useStore((s) => s.toast)
  const running = state.status === 'running'
  return (
    <>
      <Button disabled={edited || running} onClick={() => setOpen(true)}>
        {running ? <Spinner size={12} /> : <Wand2 size={14} aria-hidden />} {t('refactor.button')}
      </Button>
      <Dialog
        open={open}
        onOpenChange={setOpen}
        title={t('refactor.title')}
        width={520}
        footer={
          <>
            <Button variant="ghost" onClick={() => setOpen(false)}>
              {t('common.cancel')}
            </Button>
            <Button
              variant="primary"
              disabled={running}
              onClick={() => {
                void run({ format }).then(() => {
                  const s = useRefactor.getState().state
                  if (s.status === 'ready' && s.after === s.before) toast(t('refactor.nothing'))
                  if (s.status === 'error') toast(s.message, 'error')
                  setOpen(false)
                })
              }}
            >
              {running ? <Spinner size={12} /> : null} {running ? t('refactor.running', { pass: state.status === 'running' ? state.pass : 1 }) : t('refactor.run')}
            </Button>
          </>
        }
      >
        <div className="space-y-3 px-5 py-4 text-sm">
          <p className="text-ink">{t('refactor.lead')}</p>
          <label className="flex items-center gap-2 text-ink">
            <input type="checkbox" checked={format} onChange={(e) => setFormat(e.target.checked)} className="accent-[var(--amber)]" />
            {t('refactor.format')}
          </label>
        </div>
      </Dialog>
    </>
  )
}
