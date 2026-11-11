import clsx from 'clsx'
import { Lightbulb, TriangleAlert } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import type { Finding, Rewrite } from '@shared/protocol'
import { DiffPanes } from '@/editor/DiffPanes'
import { t } from '@/i18n'
import { checkLabelText } from '@/lib/ai'
import { KindIcon, kindText } from '@/lib/kinds'
import { groupByStatement, isBlocked, previewPair, rewritesFor, safetyText, sameResultBatch } from '@/lib/rewrites'
import type { AnalysisIndex } from '@/lib/model'
import { useStore, type RewritesState } from '@/store'
import { OptimizedSection } from './Optimized'
import { Button, Dialog, Spinner } from './ui'

/** Rewrites that still match the editor text (offsets are only valid for the analyzed text). */
export function useFreshRewrites(): Rewrite[] {
  const rewrites = useStore((s) => s.rewrites)
  const doc = useStore((s) => s.doc)
  return useMemo(() => (rewrites.status === 'ready' && doc && !doc.edited && rewrites.forText === doc.text ? rewrites.items : []), [rewrites, doc])
}

export function SafetyBadge({ safety, className }: { safety: string; className?: string }): React.JSX.Element {
  const s = safetyText(safety)
  return (
    <span className={clsx('safety-badge', `safety-${s.tone}`, className)} title={s.detail}>
      {s.tone === 'edge' ? <TriangleAlert size={12} aria-hidden /> : null}
      {s.label}
    </span>
  )
}

/** "Fix…" for a finding that has a quick fix; opens the preview (or a menu when there are several). */
export function FixButton({ finding, compact }: { finding: Pick<Finding, 'rule' | 'statement'>; compact?: boolean }): React.JSX.Element | null {
  const all = useFreshRewrites()
  const preview = useStore((s) => s.previewRewrite)
  const openMenu = useStore((s) => s.openContextMenu)
  const fixes = rewritesFor(all, finding)
  if (!fixes.length) return null
  return (
    <button
      type="button"
      className={clsx('fix-button', compact && 'fix-button-compact')}
      title={fixes.length === 1 ? t('fixes.quickFix', { title: fixes[0]!.title }) : t('fixes.count', { count: fixes.length })}
      onClick={(e) => {
        e.stopPropagation()
        if (fixes.length === 1) preview(fixes[0]!.id)
        else openMenu(e.clientX, e.clientY, fixes.map((r) => ({ label: t('fixes.fixItem', { title: r.title }), onSelect: () => preview(r.id) })))
      }}
    >
      <Lightbulb size={12} aria-hidden /> {t('fixes.fixButton')}
    </button>
  )
}

/** Bottom panel tab: every suggested fix in the script, by statement, with a batch for "Same result" ones. */
export function FixesPanel(): React.JSX.Element {
  const state = useStore((s) => s.rewrites)
  const index = useStore((s) => s.index)
  const doc = useStore((s) => s.doc)
  const preview = useStore((s) => s.previewRewrite)
  const apply = useStore((s) => s.applyRewrites)
  const items = useFreshRewrites()
  const groups = useMemo(() => groupByStatement(items), [items])
  const batch = useMemo(() => sameResultBatch(items), [items])

  if (doc?.edited) return <p className="px-3 py-2 text-sm text-ink-muted">{t('fixes.stale')}</p>
  return (
    <div className="text-sm">
      {/* Optimized statements (all verified rewrites of a statement together) come first. */}
      <OptimizedSection />
      <QuickFixes state={state} items={items} groups={groups} batch={batch} index={index} preview={preview} apply={apply} />
    </div>
  )
}

function QuickFixes({
  state,
  items,
  groups,
  batch,
  index,
  preview,
  apply
}: {
  state: RewritesState
  items: Rewrite[]
  groups: { statement: number; items: Rewrite[] }[]
  batch: { apply: Rewrite[] }
  index: AnalysisIndex | null
  preview: (id: string | null) => void
  apply: (ids: string[]) => void
}): React.JSX.Element {
  if (state.status === 'loading') {
    return (
      <p className="flex items-center gap-2 px-3 py-2 text-sm text-ink-muted">
        <Spinner size={12} /> {t('fixes.loading')}
      </p>
    )
  }
  if (state.status === 'unavailable') return <p className="px-3 py-2 text-sm text-ink-muted">{t('fixes.unavailable')}</p>
  if (state.status === 'error') return <p className="px-3 py-2 text-sm text-risk-high">{state.error}</p>
  if (!items.length) return <p className="px-3 py-2 text-sm text-ink-muted">{t('fixes.none')}</p>

  return (
    <div>
      <div className="sticky top-0 z-[1] flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-line bg-surface px-3 py-1.5">
        <Lightbulb size={13} className="text-amber-ink" />
        <span className="text-ink">{t('fixes.summary', { count: items.length })}</span>
        <span className="text-xs text-ink-muted">{t('fixes.summaryHint')}</span>
        <span className="flex-1" />
        <Button size="sm" variant="primary" disabled={batch.apply.length === 0} onClick={() => apply(batch.apply.map((r) => r.id))} title={t('fixes.applyAllTitle')}>
          {t('fixes.applyAll', { count: batch.apply.length })}
        </Button>
      </div>
      {groups.map((g) => {
        const s = index?.byId.get(g.statement)
        return (
          <div key={g.statement} className="pb-1">
            <div className="flex items-center gap-2 px-3 pt-1.5 pb-0.5 text-xs text-ink-muted">
              {s ? <KindIcon kind={s.kind} size={12} /> : null}
              <span className="truncate font-medium text-ink">{s?.summary || (s ? kindText(s.kind) : t('fixes.statement', { number: g.statement + 1 }))}</span>
              {s ? <span className="shrink-0">{t('common.line', { line: s.range.start.line })}</span> : null}
            </div>
            {g.items.map((r) => (
              <button
                key={r.id}
                type="button"
                onClick={() => preview(r.id)}
                className="flex w-full items-center gap-2.5 py-1 pr-3 pl-6 text-left hover:bg-surface-hover"
              >
                <span className="code w-12 shrink-0 text-[12px] text-ink-faint">{r.rule.replace(/^AMB-/, '')}</span>
                <span className="min-w-0 flex-1 truncate text-ink">{r.title}</span>
                {isBlocked(r) ? <span className="shrink-0 text-xs text-ink-muted">{t('fixes.applyFirst', { id: r.conflictsWith ?? '' })}</span> : null}
                <SafetyBadge safety={r.safety} />
                <span className="shrink-0 text-xs text-amber-ink">{t('fixes.preview')}</span>
              </button>
            ))}
          </div>
        )
      })}
    </div>
  )
}

/** Before/after of one quick fix, its safety rating and the parser's check, then "Apply to editor". */
export function RewriteDialog(): React.JSX.Element {
  const id = useStore((s) => s.rewritePreview)
  const close = useStore((s) => s.previewRewrite)
  const apply = useStore((s) => s.applyRewrites)
  const index = useStore((s) => s.index)
  const doc = useStore((s) => s.doc)
  const dialect = useStore((s) => s.dialect)
  const items = useFreshRewrites()
  const rewrite = items.find((r) => r.id === id) ?? null
  const range = rewrite ? index?.byId.get(rewrite.statement)?.range : undefined
  const preview = useMemo(() => (rewrite && range && doc ? previewPair(doc.text, range, rewrite) : null), [rewrite, range, doc])
  const s = rewrite ? safetyText(rewrite.safety) : null
  const blocked = rewrite ? isBlocked(rewrite) : false
  // A rewrite that can change the result in an edge case is applied only when the developer confirms that the
  // edge case cannot happen in this script: Amber never changes the business logic on its own.
  const edge = rewrite?.safety === 'CHANGES_BEHAVIOR_IN_EDGE_CASES'
  const [confirmed, setConfirmed] = useState(false)
  useEffect(() => setConfirmed(false), [id])

  return (
    <Dialog
      open={id !== null}
      onOpenChange={(o) => !o && close(null)}
      title={rewrite ? rewrite.title : t('fixes.dialog.title')}
      description={rewrite ? t('fixes.dialog.description', { rule: rewrite.rule }) : undefined}
      width={1040}
      footer={
        rewrite ? (
          <>
            {blocked ? <span className="mr-auto self-center text-sm text-ink-muted">{t('fixes.dialog.blocked', { id: rewrite.conflictsWith ?? '' })}</span> : null}
            <Button variant="ghost" onClick={() => close(null)}>
              {t('common.cancel')}
            </Button>
            {edge ? (
              <label className="mr-auto flex items-center gap-2 self-center text-sm text-ink">
                <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} className="accent-[var(--amber)]" />
                {t('fixes.dialog.confirmEdge')}
              </label>
            ) : null}
            <Button variant="primary" disabled={blocked || (edge && !confirmed)} onClick={() => apply([rewrite.id])}>
              {t('fixes.dialog.apply')}
            </Button>
          </>
        ) : null
      }
    >
      {!rewrite || !preview || !s ? (
        <p className="p-5 text-sm text-ink-muted">{t('fixes.dialog.noLongerMatches')}</p>
      ) : (
        <div className="flex h-[66vh] flex-col">
          <div className="shrink-0 space-y-2.5 border-b border-line px-5 py-3">
            <div className="flex flex-wrap items-center gap-2">
              <SafetyBadge safety={rewrite.safety} className="text-[13px]" />
              <span className="text-sm text-ink-muted">{s.detail}</span>
            </div>
            {rewrite.safetyNote ? (
              <div className={clsx('rounded-[6px] border px-3 py-2 text-[14px] leading-[21px] text-ink', s.tone === 'edge' ? 'safety-note-edge' : 'border-line bg-surface-2')}>
                {s.tone === 'edge' ? <span className="font-semibold">{t('fixes.dialog.edgeCase')}</span> : null}
                {rewrite.safetyNote}
              </div>
            ) : null}
            <p className="text-[14px] leading-[21px] text-ink">{rewrite.explanation}</p>
            <p className="text-sm text-ink-muted">
              {t('fixes.dialog.parserCheck')} <span className="text-ink">{checkLabelText(rewrite.check)}</span>
              {rewrite.checkDifferences.length ? ` (${rewrite.checkDifferences.join('; ')})` : ''}
            </p>
          </div>
          <div className="grid shrink-0 grid-cols-2 divide-x divide-line border-b border-line bg-surface-2 text-xs text-ink-muted">
            <span className="px-3 py-0.5">{t('fixes.dialog.now')}</span>
            <span className="px-3 py-0.5">{t('fixes.dialog.after')}</span>
          </div>
          <DiffPanes before={preview.before} after={preview.after} dialect={dialect} pair={preview.pair} />
        </div>
      )}
    </Dialog>
  )
}
