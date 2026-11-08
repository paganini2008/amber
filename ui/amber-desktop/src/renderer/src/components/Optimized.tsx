import clsx from 'clsx'
import { Columns2, Wand2 } from 'lucide-react'
import { useMemo } from 'react'
import type { Finding, OptimizedStatement } from '@shared/protocol'
import { DiffPanes } from '@/editor/DiffPanes'
import { t, tOr } from '@/i18n'
import { checkLabelText } from '@/lib/ai'
import { findingTitle, KindIcon, kindText } from '@/lib/kinds'
import { canApplyFindingSql, rewrittenStatements, safeOptimizations, skipReasonText, wholePair } from '@/lib/optimize'
import { safetyText } from '@/lib/rewrites'
import { useStore } from '@/store'
import { CodeBlock } from './CodeBlock'
import { SafetyBadge } from './Fixes'
import { Button, Dialog, Spinner } from './ui'

/**
 * Optimized SQL without AI. `rewrite.optimize` applies every non-conflicting verified rewrite of a
 * statement together; findings may carry SQL of their own. Cards look like AI suggestion cards but
 * always say "Rule-based · no AI". Additional DDL (index hints) is shown apart and never applied.
 */

/** Optimized statements that still match the editor text. */
export function useFreshOptimize(): OptimizedStatement[] {
  const optimize = useStore((s) => s.optimize)
  const doc = useStore((s) => s.doc)
  return useMemo(
    () => (optimize.status === 'ready' && doc && !doc.edited && optimize.forText === doc.text ? optimize.items : []),
    [optimize, doc]
  )
}

export function RuleBasedBadge({ className }: { className?: string }): React.JSX.Element {
  return (
    <span className={clsx('rule-badge', className)} title={t('optimize.badgeTitle')}>
      <Wand2 size={11} aria-hidden />
      {t('optimize.badge')}
    </span>
  )
}

function CheckPill({ label }: { label: string }): React.JSX.Element {
  return (
    <span
      className={clsx(
        'shrink-0 rounded-[3px] border px-1.5 text-xs leading-[18px]',
        label === 'STRUCTURE_PRESERVED' && 'border-line-strong text-ink',
        label === 'STRUCTURE_CHANGED' && 'border-risk-medium text-risk-medium',
        label === 'INVALID_SQL' && 'border-risk-high text-risk-high',
        label === 'NOT_CHECKED' && 'border-line text-ink-muted'
      )}
    >
      {label === 'NOT_CHECKED' ? t('optimize.verified.NOT_CHECKED') : checkLabelText(label)}
    </span>
  )
}

/** Suggested DDL: copy only, clearly not part of the script. */
export function DdlBlock({ ddl }: { ddl: string[] }): React.JSX.Element | null {
  if (!ddl.length) return null
  return (
    <section className="space-y-1.5" aria-label={t('optimize.ddl.title')}>
      <h5 className="text-xs font-semibold text-ink">{t('optimize.ddl.title')}</h5>
      <p className="text-xs text-ink-muted">{t('optimize.ddl.hint')}</p>
      <CodeBlock code={ddl.join(';\n') + ';'} copyLabel={t('optimize.ddl.copy')} />
    </section>
  )
}

/** One statement's optimized version, in the style of an AI suggestion card. */
export function OptimizedCard({ item, compact }: { item: OptimizedStatement; compact?: boolean }): React.JSX.Element {
  const index = useStore((s) => s.index)
  const markRange = useStore((s) => s.markRange)
  const preview = useStore((s) => s.previewOptimized)
  const apply = useStore((s) => s.applyOptimized)
  const statement = index?.byId.get(item.statement)
  const rewritten = item.optimized && item.optimized !== item.original
  const s = safetyText(item.safety)
  return (
    <article className="space-y-2 rounded-[6px] border border-line p-2.5 text-xs" aria-label={t('optimize.card.title')}>
      <div className="flex flex-wrap items-center gap-2">
        {/* In the details panel the section heading already says "Optimized SQL". */}
        {compact ? null : <h4 className="min-w-0 flex-1 text-sm font-medium text-ink">{t('optimize.card.title')}</h4>}
        <RuleBasedBadge />
      </div>
      {statement && !compact ? (
        <button
          type="button"
          className="text-ink-muted hover:text-ink hover:underline"
          onClick={() => markRange({ from: statement.range.start.offset, to: statement.range.end.offset })}
        >
          {t('optimize.card.statementLine', { number: statement.id + 1, line: statement.range.start.line })}
        </button>
      ) : null}
      {rewritten ? (
        <>
          <div className="flex flex-wrap items-center gap-1.5">
            <SafetyBadge safety={item.safety} />
            {item.check ? <CheckPill label={item.check.label} /> : null}
          </div>
          {s.detail ? <p className="text-ink-muted">{s.detail}</p> : null}
          <div>
            <div className="text-ink-muted">{t('optimize.card.whatChanged')}</div>
            <ul className="mt-0.5 space-y-0.5">
              {item.applied.map((a) => {
                const why = tOr(`findings.rules.${a.rule}.title`, '')
                return (
                  <li key={a.id} className="flex gap-1.5">
                    <span className="code shrink-0 text-ink-faint">{a.rule.replace(/^AMB-/, '')}</span>
                    <span className="selectable min-w-0 text-ink">
                      {a.title}
                      {why ? (
                        <span className="text-ink-muted">
                          {t('common.separator')}
                          {why}
                        </span>
                      ) : null}
                    </span>
                  </li>
                )
              })}
            </ul>
          </div>
          {item.skipped.length ? (
            <div>
              <div className="text-ink-muted">{t('optimize.card.notApplied')}</div>
              <ul className="mt-0.5 space-y-0.5">
                {item.skipped.map((k) => (
                  <li key={k.id} className="flex gap-1.5">
                    <span className="code shrink-0 text-ink-faint">{k.rule.replace(/^AMB-/, '')}</span>
                    <span className="min-w-0 text-ink-muted">
                      <span className="text-ink">{k.title}</span>
                      {t('common.separator')}
                      {skipReasonText(k.reason)}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
          {item.check?.label === 'STRUCTURE_CHANGED' && item.check.differences.length ? (
            <div>
              <div className="text-ink-muted">{t('optimize.card.differences')}</div>
              <ul className="mt-0.5 list-disc space-y-0.5 pl-4 text-ink">
                {item.check.differences.map((d, i) => (
                  <li key={i} className="selectable">
                    {d}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
          <CodeBlock code={item.optimized} />
          <div className="flex flex-wrap gap-1.5">
            <Button size="sm" onClick={() => preview({ kind: 'statement', statement: item.statement })} title={t('optimize.card.compareTitle')}>
              <Columns2 size={13} aria-hidden /> {t('optimize.card.compare')}
            </Button>
            <Button
              size="sm"
              variant="primary"
              disabled={item.check?.label === 'INVALID_SQL'}
              onClick={() => apply([item.statement])}
              title={t('optimize.card.applyTitle')}
            >
              {t('optimize.card.apply')}
            </Button>
          </div>
        </>
      ) : null}
      <DdlBlock ddl={item.additionalDdl} />
    </article>
  )
}

/** Details panel: the selected statement's optimized version, if it has one. */
export function OptimizedForStatement({ statementId }: { statementId: number }): React.JSX.Element | null {
  const items = useFreshOptimize()
  const item = items.find((o) => o.statement === statementId)
  if (!item) return null
  return (
    <section>
      <h3 className="mb-1.5 text-sm font-semibold">{t('optimize.details.title')}</h3>
      <OptimizedCard item={item} compact />
    </section>
  )
}

/** "Show optimized SQL" on a finding card. */
export function FindingSqlButton({ finding }: { finding: Finding }): React.JSX.Element | null {
  const findings = useStore((s) => s.analysis?.findings)
  const preview = useStore((s) => s.previewOptimized)
  // A finding whose SQL is a quick fix (rewriteId) shows it through the Fix button instead.
  if (!finding.suggestedSql || finding.rewriteId) return null
  const index = findings?.indexOf(finding) ?? -1
  if (index < 0) return null
  const ddl = finding.suggestedSqlKind === 'ADDITIONAL_DDL'
  return (
    <button
      type="button"
      className="fix-button"
      onClick={(e) => {
        e.stopPropagation()
        preview({ kind: 'finding', finding: index })
      }}
    >
      <Wand2 size={12} aria-hidden /> {ddl ? t('optimize.finding.showDdl') : t('optimize.finding.show')}
    </button>
  )
}

/** Bottom panel "Suggestions": optimized statements first, with "Apply all safe optimizations". */
export function OptimizedSection(): React.JSX.Element | null {
  const state = useStore((s) => s.optimize)
  const index = useStore((s) => s.index)
  const apply = useStore((s) => s.applyOptimized)
  const preview = useStore((s) => s.previewOptimized)
  const items = useFreshOptimize()
  const rewritten = useMemo(() => rewrittenStatements(items), [items])
  const safe = useMemo(() => safeOptimizations(items), [items])
  if (state.status === 'idle') return null
  if (state.status === 'loading') {
    return (
      <p className="flex items-center gap-2 px-3 py-2 text-sm text-ink-muted">
        <Spinner size={12} /> {t('optimize.panel.loading')}
      </p>
    )
  }
  if (state.status === 'unavailable') return <p className="px-3 py-2 text-sm text-ink-muted">{t('optimize.panel.unavailable')}</p>
  if (state.status === 'error') return <p className="px-3 py-2 text-sm text-risk-high">{state.error}</p>
  if (!items.length) return null
  return (
    <div className="border-b border-line pb-1 text-sm">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-line bg-surface px-3 py-1.5">
        <Wand2 size={13} className="text-amber-ink" aria-hidden />
        <span className="text-ink">{t('optimize.panel.summary', { count: rewritten.length })}</span>
        <RuleBasedBadge />
        <span className="flex-1" />
        <Button size="sm" variant="primary" disabled={safe.length === 0} onClick={() => apply(safe.map((o) => o.statement))} title={t('optimize.panel.applySafeTitle')}>
          {t('optimize.panel.applySafe', { count: safe.length })}
        </Button>
      </div>
      {items.map((o) => {
        const s = index?.byId.get(o.statement)
        const ddlOnly = !o.optimized || o.optimized === o.original
        return (
          <button
            key={o.statement}
            type="button"
            onClick={() => preview({ kind: 'statement', statement: o.statement })}
            className="flex w-full items-center gap-2.5 px-3 py-1 text-left hover:bg-surface-hover"
          >
            {s ? <KindIcon kind={s.kind} size={12} className="shrink-0 text-ink-muted" /> : null}
            <span className="min-w-0 flex-1 truncate text-ink">{s?.summary || (s ? kindText(s.kind) : `#${o.statement + 1}`)}</span>
            {s ? <span className="shrink-0 text-xs text-ink-muted">{t('common.line', { line: s.range.start.line })}</span> : null}
            {ddlOnly ? (
              <span className="shrink-0 text-xs text-ink-muted">{t('optimize.panel.ddlOnly')}</span>
            ) : (
              <>
                <span className="shrink-0 text-xs text-ink-muted">{t('optimize.details.rewrites', { count: o.applied.length })}</span>
                <SafetyBadge safety={o.safety} />
              </>
            )}
            <span className="shrink-0 text-xs text-amber-ink">{t('optimize.panel.open')}</span>
          </button>
        )
      })}
      <div className="px-3 pt-1.5 text-xs text-ink-muted">{t('optimize.panel.quickFixes')}</div>
    </div>
  )
}

/** Side-by-side view of an optimized statement or of a finding's SQL, with Apply. */
export function OptimizedDialog(): React.JSX.Element {
  const target = useStore((s) => s.optimizedPreview)
  const close = useStore((s) => s.previewOptimized)
  const applyStatement = useStore((s) => s.applyOptimized)
  const applyFinding = useStore((s) => s.applyFindingSql)
  const findings = useStore((s) => s.analysis?.findings)
  const index = useStore((s) => s.index)
  const doc = useStore((s) => s.doc)
  const dialect = useStore((s) => s.dialect)
  const items = useFreshOptimize()

  const item = target?.kind === 'statement' ? (items.find((o) => o.statement === target.statement) ?? null) : null
  const finding = target?.kind === 'finding' ? (findings?.[target.finding] ?? null) : null
  const statementId = item?.statement ?? finding?.statement ?? null
  const range = statementId !== null ? index?.byId.get(statementId)?.range : undefined
  const fresh = Boolean(doc && !doc.edited)
  const isDdl = finding?.suggestedSqlKind === 'ADDITIONAL_DDL'

  const view = useMemo(() => {
    if (!doc || !range) return null
    if (item && item.optimized && item.optimized !== item.original) return { before: item.original, after: item.optimized }
    if (finding?.suggestedSql && !isDdl) return { before: doc.text.slice(range.start.offset, range.end.offset), after: finding.suggestedSql }
    return null
  }, [doc, range, item, finding, isDdl])
  const pair = useMemo(() => (view && statementId !== null ? wholePair(view.before, view.after, statementId) : null), [view, statementId])

  const canApply = fresh && view !== null && (item ? item.check?.label !== 'INVALID_SQL' : finding ? canApplyFindingSql(finding) : false)
  const title = finding ? findingTitle(finding) : t('optimize.dialog.title')

  return (
    <Dialog
      open={target !== null}
      onOpenChange={(o) => !o && close(null)}
      title={title}
      description={t('optimize.dialog.description')}
      width={1040}
      footer={
        target !== null ? (
          <>
            {finding && !isDdl && !canApplyFindingSql(finding) ? <span className="mr-auto self-center text-sm text-ink-muted">{t('optimize.finding.copyOnly')}</span> : null}
            <Button variant="ghost" onClick={() => close(null)}>
              {t('common.close')}
            </Button>
            {view ? (
              <Button
                variant="primary"
                disabled={!canApply}
                title={t('optimize.card.applyTitle')}
                onClick={() => (item ? applyStatement([item.statement]) : target?.kind === 'finding' ? applyFinding(target.finding) : undefined)}
              >
                {t('optimize.card.apply')}
              </Button>
            ) : null}
          </>
        ) : null
      }
    >
      {target === null ? null : !fresh ? (
        <p className="p-5 text-sm text-ink-muted">{t('optimize.card.stale')}</p>
      ) : (
        <div className="flex max-h-[70vh] flex-col">
          <div className="shrink-0 space-y-2 border-b border-line px-5 py-3 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <RuleBasedBadge />
              {item ? <SafetyBadge safety={item.safety} className="text-[13px]" /> : null}
              {item?.check ? <CheckPill label={item.check.label} /> : finding?.verified ? <CheckPill label={finding.verified} /> : null}
              {item ? <span className="text-ink-muted">{safetyText(item.safety).detail}</span> : null}
            </div>
            {finding?.suggestedSqlNote ? (
              <div className="rounded-[6px] border border-line bg-surface-2 px-3 py-2 text-ink">
                <span className="font-semibold">{t('optimize.card.note')}</span>
                {t('common.colon')}
                {finding.suggestedSqlNote}
              </div>
            ) : null}
            {item?.applied.length ? (
              <p className="text-ink">
                <span className="text-ink-muted">{t('optimize.card.whatChanged')}</span>
                {t('common.colon')}
                {item.applied.map((a) => a.title).join(t('common.listSeparator'))}
              </p>
            ) : null}
            {item?.check?.label === 'STRUCTURE_CHANGED' && item.check.differences.length ? (
              <p className="text-ink-muted">
                {t('optimize.card.check')} <span className="text-ink">{item.check.differences.join('; ')}</span>
              </p>
            ) : null}
          </div>
          {view && pair ? (
            <>
              <div className="grid shrink-0 grid-cols-2 divide-x divide-line border-b border-line bg-surface-2 text-xs text-ink-muted">
                <span className="px-3 py-0.5">{t('optimize.dialog.now')}</span>
                <span className="px-3 py-0.5">{t('optimize.dialog.after')}</span>
              </div>
              <div className="h-[46vh] min-h-[220px]">
                <DiffPanes before={view.before} after={view.after} dialect={dialect} pair={pair} />
              </div>
            </>
          ) : null}
          <div className="space-y-3 overflow-auto px-5 py-3">
            {isDdl && finding?.suggestedSql ? <DdlBlock ddl={[finding.suggestedSql.replace(/;\s*$/, '')]} /> : null}
            {item ? <DdlBlock ddl={item.additionalDdl} /> : null}
          </div>
        </div>
      )}
    </Dialog>
  )
}
