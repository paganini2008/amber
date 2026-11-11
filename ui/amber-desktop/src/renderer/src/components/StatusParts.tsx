import clsx from 'clsx'
import { ChevronDown, ChevronUp, CircleDot, Database, Lightbulb, Info, OctagonExclamationPoint, RotateCw, TriangleExclamationPoint, X } from 'lucide-react'
import { useMemo, useState } from 'react'
import type { Diagnostic } from '@shared/protocol'
import { fmt, t } from '@/i18n'
import { api } from '@/lib/api'
import { countBySeverity, sortedFindings } from '@/lib/findings'
import { findingTitle, severityText } from '@/lib/kinds'
import { metadataStatusText } from '@/lib/metadata'
import { statementAtOffset } from '@/lib/model'
import { useStore } from '@/store'
import { MetadataPanel } from './Metadata'
import { FixButton, FixesPanel, useFreshRewrites } from './Fixes'
import { useFreshOptimize } from './Optimized'
import { ReferencesPanel } from './References'
import { Button, Spinner } from './ui'

/** Severity: high = red, medium = violet, low = blue, always with an icon; info is neutral. */
export function SeverityIcon({ severity, size = 13 }: { severity: string; size?: number }): React.JSX.Element {
  if (severity === 'ERROR' || severity === 'HIGH')
    return <OctagonExclamationPoint size={size} className="shrink-0 text-risk-high" aria-label={severity === 'HIGH' ? severityText('HIGH') : t('app.status.error')} />
  if (severity === 'WARNING' || severity === 'MEDIUM')
    return <TriangleExclamationPoint size={size} className="shrink-0 text-risk-medium" aria-label={severity === 'MEDIUM' ? severityText('MEDIUM') : t('app.status.warning')} />
  if (severity === 'LOW') return <Info size={size} className="shrink-0 text-risk-low" aria-label={severityText('LOW')} />
  return <CircleDot size={size} className="shrink-0 text-ink-muted" aria-label={severityText('INFO')} />
}

function goTo(d: Diagnostic): void {
  const st = useStore.getState()
  if (st.index) {
    const id = statementAtOffset(st.index, d.range.start.offset)
    if (id !== null) st.selectStatement(id, 'view', false)
  }
  st.revealRange(d.range.start.offset, d.range.end.offset)
}

export function ProblemsBar(): React.JSX.Element {
  const analysis = useStore((s) => s.analysis)
  const analyzing = useStore((s) => s.analyzing)
  const engine = useStore((s) => s.engine)
  const usedFallback = useStore((s) => s.usedFallback)
  const doc = useStore((s) => s.doc)
  const selectedFinding = useStore((s) => s.selectedFinding)
  const selectFinding = useStore((s) => s.selectFinding)
  const panel = useStore((s) => s.bottomPanel)
  const setPanel = useStore((s) => s.setBottomPanel)
  const online = useStore((s) => s.online)
  const metadataFiles = useStore((s) => s.metadataFiles)
  const metadataText = metadataStatusText(online, metadataFiles)
  const refs = useStore((s) => s.references)
  const open = panel !== null
  const tab = panel ?? 'findings'
  const diagnostics = useMemo(() => analysis?.diagnostics ?? [], [analysis])
  const findings = useMemo(() => analysis?.findings, [analysis])
  const sorted = useMemo(() => sortedFindings(findings ?? []), [findings])
  const counts = useMemo(() => countBySeverity(findings ?? []), [findings])
  const errors = diagnostics.filter((d) => d.severity === 'ERROR').length
  const warnings = diagnostics.length - errors

  const view = useStore((s) => s.view)
  const setView = useStore((s) => s.setView)
  const toggle = (next: 'findings' | 'fixes' | 'syntax' | 'metadata' | 'references' | 'format'): void => setPanel(panel === next ? null : next)
  const formatReport = useStore((s) => s.formatReport)
  const fixes = useFreshRewrites()
  const optimized = useFreshOptimize()

  return (
    <footer className="shrink-0 border-t border-line bg-surface">
      {open ? (
        <div
          className="max-h-56 overflow-auto border-b border-line"
          role={tab === 'metadata' || tab === 'references' || tab === 'fixes' ? 'region' : 'list'}
          aria-label={tab === 'findings' ? t('app.status.findings') : tab === 'metadata' ? t('app.status.metadata') : tab === 'references' ? t('app.status.references') : tab === 'fixes' ? t('app.status.suggestedFixes') : t('app.status.syntaxProblems')}
        >
          {tab === 'metadata' ? (
            <MetadataPanel />
          ) : tab === 'references' ? (
            <ReferencesPanel />
          ) : tab === 'fixes' ? (
            <FixesPanel />
          ) : tab === 'format' ? (
            <FormatReport />
          ) : tab === 'findings' ? (
            findings === undefined ? (
              <p className="px-3 py-2 text-sm text-ink-muted">{t('app.status.findingsUnavailable')}</p>
            ) : sorted.length === 0 ? (
              <p className="px-3 py-2 text-sm text-ink-muted">{t('app.status.noFindings')}</p>
            ) : (
              sorted.map(({ finding: f, index }) => (
                <div
                  key={index}
                  role="listitem"
                  className={clsx(
                    'flex items-center gap-1 pr-3',
                    selectedFinding === index ? 'bg-amber-wash shadow-[inset_2px_0_0_var(--amber)]' : 'hover:bg-surface-hover'
                  )}
                >
                <button type="button" onClick={() => selectFinding(index)} className="flex min-w-0 flex-1 items-center gap-2 py-1 pl-3 text-left text-sm">
                  <SeverityIcon severity={f.severity} />
                  <span className="shrink-0 text-ink">{findingTitle(f)}</span>
                  <span className="min-w-0 flex-1 truncate text-ink-muted">
                    {f.confidence === 'INFERRED' ? t('app.status.possible') : ''}
                    {f.message}
                  </span>
                  <span className="code shrink-0 text-xs text-ink-faint">{f.rule}</span>
                  <span className="code w-12 shrink-0 text-right text-xs text-ink-muted">{f.range.start.line}:{f.range.start.column}</span>
                </button>
                <span className="flex w-16 shrink-0 justify-end">
                  <FixButton finding={f} compact />
                </span>
                </div>
              ))
            )
          ) : diagnostics.length === 0 ? (
            <p className="px-3 py-2 text-sm text-ink-muted">{t('app.status.noSyntaxProblems')}</p>
          ) : (
            diagnostics.map((d, i) => (
              <button
                key={i}
                type="button"
                role="listitem"
                onClick={() => goTo(d)}
                className="flex w-full items-center gap-2 px-3 py-1 text-left text-sm hover:bg-surface-hover"
              >
                <SeverityIcon severity={d.severity} />
                <span className="min-w-0 flex-1 truncate">{d.message}</span>
                <span className="code shrink-0 text-xs text-ink-faint">{d.code}</span>
                <span className="code w-12 shrink-0 text-right text-xs text-ink-muted">
                  {d.range.start.line}:{d.range.start.column}
                </span>
              </button>
            ))
          )}
        </div>
      ) : null}
      {/* One line at any width: items never wrap, and the least important ones step aside on narrow windows. */}
      <div className="chrome flex h-7 items-center gap-4 overflow-hidden px-3 text-xs whitespace-nowrap text-ink-muted">
        <button
          type="button"
          className={clsx('flex items-center gap-3 hover:text-ink', open && tab === 'findings' && 'text-ink')}
          onClick={() => toggle('findings')}
          aria-expanded={open && tab === 'findings'}
          aria-label={t('app.status.findings')}
        >
          {open && tab === 'findings' ? <ChevronDown size={13} /> : <ChevronUp size={13} />}
          <span className="font-medium text-ink">{t('app.status.findings')}</span>
          {!analysis ? null : findings === undefined ? (
            <span>{t('app.status.notAvailable')}</span>
          ) : (
            (['HIGH', 'MEDIUM', 'LOW', 'INFO'] as const).map((sev) => (
              <span key={sev} className="flex items-center gap-1" title={`${severityText(sev)}: ${counts[sev] ?? 0}`}>
                <SeverityIcon severity={sev} size={12} /> {counts[sev] ?? 0}
                <span className="sr-only">{severityText(sev)}</span>
              </span>
            ))
          )}
        </button>
        {fixes.length + optimized.length || findings?.length ? (
          <button
            type="button"
            className={clsx('flex items-center gap-1.5 hover:text-ink', view === 'suggestions' && 'text-ink')}
            onClick={() => {
              // The Suggestions view holds every finding with its fix; the old bottom list is not needed for it.
              setPanel(null)
              setView('suggestions')
            }}
            aria-label={t('app.status.suggestedFixes')}
          >
            <Lightbulb size={12} className="text-amber-ink" />
            <span className="font-medium text-ink">{t('app.status.fixes')}</span>
            {fixes.length + optimized.length ? <span className="tabular-nums">{fixes.length + optimized.length}</span> : null}
          </button>
        ) : null}
        <button
          type="button"
          className={clsx('flex items-center gap-3 hover:text-ink', open && tab === 'syntax' && 'text-ink')}
          onClick={() => toggle('syntax')}
          aria-expanded={open && tab === 'syntax'}
          aria-label={t('app.status.syntaxProblems')}
        >
          <span className="font-medium text-ink">{t('app.status.syntax')}</span>
          <span className="flex items-center gap-1">
            <SeverityIcon severity="ERROR" size={12} /> {errors}
          </span>
          <span className="flex items-center gap-1">
            <SeverityIcon severity="WARNING" size={12} /> {warnings}
          </span>
        </button>
        {formatReport ? (
          <button
            type="button"
            className={clsx('flex items-center gap-1.5 hover:text-ink', open && tab === 'format' && 'text-ink')}
            onClick={() => toggle('format')}
            aria-expanded={open && tab === 'format'}
            aria-label={t('app.status.formatReport')}
          >
            <span className="font-medium text-ink">{t('app.status.keptAsWritten')}</span>
            <span className="tabular-nums">{formatReport.skipped.length || formatReport.warnings.length}</span>
          </button>
        ) : null}
        {refs.query ? (
          <button
            type="button"
            className={clsx('flex items-center gap-1.5 hover:text-ink', open && tab === 'references' && 'text-ink')}
            onClick={() => toggle('references')}
            aria-expanded={open && tab === 'references'}
            aria-label={t('app.status.references')}
          >
            <span className="font-medium text-ink">{t('app.status.references')}</span>
            {refs.status === 'loading' ? <Spinner size={11} /> : refs.result ? <span className="tabular-nums">{refs.result.occurrences.length || (refs.result.definition ? 1 : 0)}</span> : null}
          </button>
        ) : null}
        <div className="flex-1" />
        {metadataText ? (
          <button
            type="button"
            className={clsx('flex min-w-0 items-center gap-1.5 hover:text-ink', open && tab === 'metadata' && 'text-ink')}
            onClick={() => toggle('metadata')}
            aria-expanded={open && tab === 'metadata'}
            title={t('app.status.metadataTitle')}
          >
            {online.status === 'loading' ? <Spinner size={11} /> : <Database size={12} className={online.status === 'error' ? 'text-risk-high' : undefined} />}
            <span className="min-w-0 truncate">{metadataText}</span>
          </button>
        ) : null}
        {analyzing ? (
          <span className="flex items-center gap-1.5">
            <Spinner size={12} /> {t('app.analysis.analyzing')}
          </span>
        ) : analysis ? (
          <>
            <span className="whitespace-nowrap max-[700px]:hidden">
              {t('app.status.parse', { state: analysis.status === 'FAILED' ? 'failed' : analysis.status === 'PARTIAL' ? 'partial' : usedFallback ? 'complete' : 'strict' })}
            </span>
            <span className="max-[1000px]:hidden">{t('app.status.lines', { count: analysis.lineCount })}</span>
            <span className="max-[1280px]:hidden">{fmt.duration(analysis.elapsedMs)}</span>
          </>
        ) : null}
        {doc ? <span className="max-[1100px]:hidden">{doc.encoding}</span> : null}
        <EngineStatusLabel state={engine.state} message={engine.message} version={engine.info?.version} />
      </div>
    </footer>
  )
}

function EngineStatusLabel({ state, message, version }: { state: string; message?: string; version?: string }): React.JSX.Element {
  // The engine version is the least important item: it gives way first on a narrow window (or high zoom).
  if (state === 'ready') return <span className="whitespace-nowrap max-[760px]:hidden" title={t('app.status.engine', { version: version ?? '' })}>{t('app.status.engine', { version: version ?? '' })}</span>
  if (state === 'failed')
    return (
      <span className="flex items-center gap-1.5 text-risk-high" title={message}>
        <OctagonExclamationPoint size={12} /> {t('app.status.engineStopped')}
        <button type="button" className="flex items-center gap-1 text-ink underline" onClick={() => void api().engine.restart()}>
          <RotateCw size={11} /> {t('app.status.restart')}
        </button>
      </span>
    )
  return (
    <span className="flex items-center gap-1.5">
      <Spinner size={12} /> {state === 'restarting' ? t('app.status.engineRestarting') : t('app.status.engineStarting')}
    </span>
  )
}

/** Strict-mode failure: each syntax error with a code frame, and the fallback switch. */
export function ErrorScreen(): React.JSX.Element {
  const analysis = useStore((s) => s.analysis)!
  const doc = useStore((s) => s.doc)
  const analyze = useStore((s) => s.analyze)
  const analyzing = useStore((s) => s.analyzing)
  const lines = useMemo(() => (doc?.text ?? '').split('\n'), [doc?.text])
  const errors = analysis.diagnostics
  return (
    <div className="h-full overflow-auto">
      <div className="mx-auto max-w-[860px] px-8 py-8">
        <div className="flex items-start gap-3">
          <OctagonExclamationPoint size={22} className="mt-0.5 shrink-0 text-risk-high" />
          <div>
            <h2 className="text-xl font-semibold">
              {t('app.error.title', { name: analysis.name })}
            </h2>
            <p className="mt-1 max-w-[640px] text-base text-ink-muted">
              {t('app.error.lead', { count: errors.length })}
            </p>
            <div className="mt-4 flex gap-2">
              <Button variant="primary" disabled={analyzing} onClick={() => void analyze({ allowFallback: true })}>
                {analyzing ? <Spinner size={13} /> : null}
                {t('app.error.withFallback')}
              </Button>
              <Button onClick={() => void analyze()} disabled={analyzing}>
                {t('app.analysis.again')}
              </Button>
            </div>
          </div>
        </div>
        <ol className="mt-8 space-y-4">
          {errors.map((d, i) => (
            <li key={i} className="overflow-hidden rounded-[6px] border border-line bg-surface">
              <button type="button" onClick={() => goTo(d)} className="flex w-full items-start gap-2 px-3 py-2 text-left hover:bg-surface-hover">
                <SeverityIcon severity={d.severity} size={14} />
                <span className="min-w-0 flex-1 text-sm">
                  <span className="text-ink">{d.offending ? t('app.error.unexpected', { token: d.offending }) : d.message}</span>
                  <span className="ml-2 text-ink-muted">
                    {t('app.error.lineColumn', { line: d.range.start.line, column: d.range.start.column })}
                  </span>
                  {d.expected?.length ? (
                    <span className="mt-0.5 block truncate text-xs text-ink-muted">{t('app.error.expected', { tokens: d.expected.slice(0, 8).join(', ') })}</span>
                  ) : null}
                </span>
              </button>
              <CodeFrame lines={lines} line={d.range.start.line} column={d.range.start.column} length={Math.max(1, d.range.end.offset - d.range.start.offset)} />
            </li>
          ))}
        </ol>
      </div>
    </div>
  )
}

function CodeFrame({ lines, line, column, length }: { lines: string[]; line: number; column: number; length: number }): React.JSX.Element {
  const from = Math.max(1, line - 2)
  const to = Math.min(lines.length, line + 2)
  const rows: React.JSX.Element[] = []
  for (let n = from; n <= to; n++) {
    const text = (lines[n - 1] ?? '').replace(/\t/g, '    ').replace(/\r$/, '')
    rows.push(
      <div key={n} className={clsx('flex', n === line && 'bg-[color-mix(in_srgb,var(--risk-high)_8%,transparent)]')}>
        <span className="w-12 shrink-0 pr-3 text-right text-ink-faint select-none">{n}</span>
        <span className="whitespace-pre text-ink">{text.length > 240 ? `${text.slice(0, 240)}…` : text || ' '}</span>
      </div>
    )
    if (n === line) {
      rows.push(
        <div key={`${n}-caret`} className="flex">
          <span className="w-12 shrink-0" />
          <span className="whitespace-pre text-risk-high">
            {' '.repeat(Math.max(0, column - 1))}
            {'^'.repeat(Math.min(length, 60))}
          </span>
        </div>
      )
    }
  }
  return <pre className="code selectable overflow-x-auto border-t border-line bg-canvas py-2 text-[12px] leading-5">{rows}</pre>
}

export function PartialBanner(): React.JSX.Element | null {
  const analysis = useStore((s) => s.analysis)
  const index = useStore((s) => s.index)
  const [dismissed, setDismissed] = useState<unknown>(null)
  if (!analysis || analysis.status !== 'PARTIAL' || dismissed === analysis) return null
  const partial = index?.order.filter((s) => s.confidence === 'PARTIAL' || s.confidence === 'UNRESOLVED' || s.kind === 'UNPARSED').length ?? 0
  return (
    <div className="flex shrink-0 items-center gap-2 border-b border-line bg-[color-mix(in_srgb,var(--risk-medium)_9%,var(--surface))] px-4 py-1.5 text-sm" role="status">
      <TriangleExclamationPoint size={14} className="text-risk-medium" />
      <span>
        {t('app.partial.banner', { partial, errors: analysis.diagnostics.length })}
      </span>
      <button type="button" className="ml-auto text-ink-muted hover:text-ink" aria-label={t('app.toast.dismiss')} onClick={() => setDismissed(analysis)}>
        <X size={14} />
      </button>
    </div>
  )
}

/** Bottom panel tab: statements the formatter left as they were, and why. */
function FormatReport(): React.JSX.Element {
  const report = useStore((s) => s.formatReport)
  const markRange = useStore((s) => s.markRange)
  if (!report) return <p className="px-3 py-2 text-sm text-ink-muted">{t('app.format.nothing')}</p>
  const rows = Math.max(report.warnings.length, report.skipped.length)
  return (
    <div className="text-sm">
      <p className="px-3 py-1.5 text-xs text-ink-muted">
        {t('app.format.explain')}
      </p>
      {Array.from({ length: rows }, (_, i) => {
        const r = report.skipped[i]
        return (
          <button
            key={i}
            type="button"
            disabled={!r}
            onClick={() => r && markRange({ from: r.start.offset, to: r.end.offset })}
            className="flex w-full items-center gap-2 px-3 py-1 text-left hover:bg-surface-hover disabled:hover:bg-transparent"
          >
            <SeverityIcon severity="INFO" size={12} />
            <span className="min-w-0 flex-1 truncate">{report.warnings[i] ?? t('app.status.keptAsWritten')}</span>
            {r ? <span className="code w-12 shrink-0 text-right text-xs text-ink-muted">{r.start.line}:{r.start.column}</span> : null}
          </button>
        )
      })}
    </div>
  )
}
