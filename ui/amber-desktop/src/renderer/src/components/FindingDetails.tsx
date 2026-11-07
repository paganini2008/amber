import clsx from 'clsx'
import { Copy } from 'lucide-react'
import type { Finding } from '@shared/protocol'
import { ignoreHint, severityText, sortedFindings } from '@/lib/findings'
import { useStore } from '@/store'
import { FixButton } from './Fixes'
import { SeverityIcon } from './StatusParts'

/** One finding: what, why, what to do, and how to silence it. */
export function FindingCard({ finding }: { finding: Finding }): React.JSX.Element {
  const toast = useStore((s) => s.toast)
  const hint = ignoreHint(finding.rule)
  return (
    <section className="selectable rounded-[6px] border border-line bg-surface p-3" aria-label="Finding">
      <div className="flex items-start gap-2">
        <SeverityIcon severity={finding.severity} size={15} />
        <div className="min-w-0">
          <h3 className="text-base leading-5 font-semibold">{finding.title}</h3>
          <div className="mt-0.5 flex flex-wrap gap-x-2 text-xs text-ink-muted">
            <span>{severityText(finding.severity)}</span>
            <span className="code">{finding.rule}</span>
            {finding.confidence === 'INFERRED' ? <span title="No metadata was available to prove it">possible</span> : null}
            <span>
              line {finding.range.start.line}, column {finding.range.start.column}
            </span>
          </div>
        </div>
      </div>
      <p className="mt-2 text-sm text-ink">{finding.message}</p>
      {finding.suggestion ? (
        <p className="mt-2 text-sm text-ink">
          <span className="text-ink-muted">Suggestion: </span>
          {finding.suggestion}
        </p>
      ) : null}
      <div className="mt-2 empty:hidden">
        <FixButton finding={finding} />
      </div>
      <div className="mt-3 flex items-center gap-2 text-xs text-ink-muted">
        <span>To ignore it, add</span>
        <code className="code rounded-[3px] border border-line bg-canvas px-1.5 py-px text-[12.5px] text-ink">{hint}</code>
        <button
          type="button"
          aria-label="Copy the ignore comment"
          className="hover:text-ink"
          onClick={() => void navigator.clipboard.writeText(hint).then(() => toast('Copied the ignore comment'))}
        >
          <Copy size={12} />
        </button>
      </div>
      <p className="mt-1 text-xs text-ink-muted">in the statement or on the line above it.</p>
    </section>
  )
}

/** Findings that point at one statement, as a compact list. */
export function StatementFindings({ statementId }: { statementId: number }): React.JSX.Element | null {
  const findings = useStore((s) => s.analysis?.findings)
  const selectedFinding = useStore((s) => s.selectedFinding)
  const selectFinding = useStore((s) => s.selectFinding)
  if (!findings) return null
  const list = sortedFindings(findings).filter((f) => f.finding.statement === statementId)
  if (!list.length) return null
  return (
    <section>
      <h3 className="mb-1.5 text-sm font-semibold">Findings</h3>
      <ul className="space-y-0.5">
        {list.map(({ finding: f, index }) => (
          <li key={index} className={clsx('flex items-center gap-1 rounded-[4px] pr-1', selectedFinding === index ? 'bg-amber-wash' : 'hover:bg-surface-hover')}>
            <button type="button" onClick={() => selectFinding(index)} className="flex min-w-0 flex-1 items-center gap-2 px-1.5 py-1 text-left text-sm">
              <SeverityIcon severity={f.severity} />
              <span className="min-w-0 flex-1 truncate">{f.title}</span>
              <span className="code text-xs text-ink-faint">{f.rule}</span>
            </button>
            <FixButton finding={f} compact />
          </li>
        ))}
      </ul>
    </section>
  )
}
