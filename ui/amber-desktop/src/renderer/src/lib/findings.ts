import type { Finding } from '@shared/protocol'
import { severityText as severityLabel } from './kinds'

export const SEVERITY_ORDER = ['HIGH', 'MEDIUM', 'LOW', 'INFO']

export function severityRank(severity: string): number {
  const i = SEVERITY_ORDER.indexOf(severity)
  return i < 0 ? SEVERITY_ORDER.length : i
}

export function countBySeverity(findings: Finding[]): Record<string, number> {
  const counts: Record<string, number> = { HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 }
  for (const f of findings) counts[f.severity] = (counts[f.severity] ?? 0) + 1
  return counts
}

/** Findings sorted most severe first, then in source order; keeps each one's original index. */
export function sortedFindings(findings: Finding[]): { finding: Finding; index: number }[] {
  return findings
    .map((finding, index) => ({ finding, index }))
    .sort((a, b) => severityRank(a.finding.severity) - severityRank(b.finding.severity) || a.finding.range.start.offset - b.finding.range.start.offset)
}

/** The most severe finding per statement (for outline markers). */
export function worstByStatement(findings: Finding[]): Map<number, { severity: string; count: number }> {
  const out = new Map<number, { severity: string; count: number }>()
  for (const f of findings) {
    const cur = out.get(f.statement)
    if (!cur) out.set(f.statement, { severity: f.severity, count: 1 })
    else {
      cur.count += 1
      if (severityRank(f.severity) < severityRank(cur.severity)) cur.severity = f.severity
    }
  }
  return out
}

/** Severity in the UI language; anything unknown reads as "Info". */
export function severityText(severity: string): string {
  return severityLabel(['HIGH', 'MEDIUM', 'LOW'].includes(severity) ? severity : 'INFO')
}

export function ignoreHint(rule: string): string {
  return `-- amber:ignore ${rule}`
}
