import type { ColumnChange, DiffReason, DiffResult, PairStatus, Range, StatementPair } from '@shared/protocol'
import { getLanguage, t, tOr } from '../i18n'
import { findingTitle } from './kinds'

/**
 * Change impact between two versions of a script (`diff`): ordering, grouping and the Markdown
 * summary for a ticket. The engine decides what changed and how risky it is.
 */

const LEVEL_RANK: Record<string, number> = { HIGH: 0, MEDIUM: 1, LOW: 2 }

export function levelRank(level: string): number {
  return LEVEL_RANK[level] ?? 3
}

/** Highest risk first; within a level, in script order (new version first, then old). */
export function sortReasons(reasons: DiffReason[]): DiffReason[] {
  const pos = (r: DiffReason): number => r.afterRange?.start.offset ?? r.beforeRange?.start.offset ?? Number.MAX_SAFE_INTEGER
  return reasons
    .map((r, i) => ({ r, i }))
    .sort((a, b) => levelRank(a.r.level) - levelRank(b.r.level) || pos(a.r) - pos(b.r) || a.i - b.i)
    .map((x) => x.r)
}

export function riskText(risk: string): string {
  if (!risk) return t('diff.risk.unknown')
  return tOr(`diff.risk.${risk}`, t('diff.risk.other', { level: `${risk.charAt(0)}${risk.slice(1).toLowerCase()}` }))
}

/** One written column with every change the engine reported for it (sources and transform). */
export interface ColumnRow {
  key: string
  target: string
  column: string
  kinds: string[]
  before: string[]
  after: string[]
  added: string[]
  removed: string[]
  beforeTransforms: string[]
  afterTransforms: string[]
  messages: string[]
  change: ColumnChange
}

/** Merges SOURCES_CHANGED and TRANSFORM_CHANGED of the same column into one row, keeping order. */
export function columnRows(columns: ColumnChange[]): ColumnRow[] {
  const rows = new Map<string, ColumnRow>()
  for (const c of columns) {
    const key = `${c.targetKey}.${c.column.toUpperCase()}`
    const row = rows.get(key)
    if (row) {
      if (!row.kinds.includes(c.kind)) row.kinds.push(c.kind)
      row.messages.push(c.message)
      continue
    }
    rows.set(key, {
      key,
      target: c.target,
      column: c.column,
      kinds: [c.kind],
      before: c.beforeSources ?? [],
      after: c.afterSources ?? [],
      added: c.addedSources ?? [],
      removed: c.removedSources ?? [],
      beforeTransforms: c.beforeTransforms ?? [],
      afterTransforms: c.afterTransforms ?? [],
      messages: [c.message],
      change: c
    })
  }
  return [...rows.values()]
}

export function sameList(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((x, i) => x === b[i])
}

export interface PairRow {
  key: string
  pair: StatementPair
  depth: number
}

export function pairKey(p: StatementPair): string {
  return `${p.beforeId ?? '-'}:${p.afterId ?? '-'}`
}

/** The aligned statements, nested, without UNCHANGED ones unless asked (a container with changes inside stays). */
export function pairRows(pairs: StatementPair[], showUnchanged: boolean): PairRow[] {
  const out: PairRow[] = []
  const hasChange = (p: StatementPair): boolean => p.status !== 'UNCHANGED' || (p.children ?? []).some(hasChange)
  const walk = (list: StatementPair[], depth: number): void => {
    for (const p of list) {
      if (!showUnchanged && !hasChange(p)) continue
      out.push({ key: pairKey(p), pair: p, depth })
      walk(p.children ?? [], depth + 1)
    }
  }
  walk(pairs, 0)
  return out
}

/** The pair a change belongs to, by statement ids (old or new side). */
export function pairFor(pairs: StatementPair[], before: number | null, after: number | null): StatementPair | null {
  let found: StatementPair | null = null
  const walk = (list: StatementPair[]): void => {
    for (const p of list) {
      if (found) return
      if ((after !== null && p.afterId === after) || (after === null && before !== null && p.beforeId === before)) {
        found = p
        return
      }
      walk(p.children ?? [])
    }
  }
  walk(pairs)
  return found
}

/** A statement pair's status ("Modified", "Formatting only"); unknown values as the engine wrote them. */
export function statusLabel(status: PairStatus): string {
  return tOr(`diff.status.${status}`, status)
}

/** "2 modified, 1 added, 1 formatting only" (unchanged left out unless it is all there is). */
export function countsText(c: DiffResult['summary']['statements']): string {
  const parts: string[] = []
  if (c.modified) parts.push(t('diff.counts.modified', { count: c.modified }))
  if (c.added) parts.push(t('diff.counts.added', { count: c.added }))
  if (c.removed) parts.push(t('diff.counts.removed', { count: c.removed }))
  if (c.moved) parts.push(t('diff.counts.moved', { count: c.moved }))
  if (c.formatOnly) parts.push(t('diff.counts.formatOnly', { count: c.formatOnly }))
  if (c.unchanged) parts.push(t('diff.counts.unchanged', { count: c.unchanged }))
  return parts.join(t('common.listSeparator')) || t('diff.counts.none')
}

export function lineText(before: Range | null, after: Range | null): string {
  if (before && after) {
    return before.start.line === after.start.line ? t('diff.line', { line: before.start.line }) : t('diff.lineMoved', { from: before.start.line, to: after.start.line })
  }
  if (after) return t('diff.newLine', { line: after.start.line })
  if (before) return t('diff.oldLine', { line: before.start.line })
  return ''
}

function cell(text: string): string {
  return text.replace(/\|/g, '\\|').replace(/[\r\n]+/g, ' ')
}

/** A Markdown summary for a ticket or pull request: risk, reasons, column and row changes. */
export function diffMarkdown(result: DiffResult): string {
  const s = result.summary
  const lines: string[] = []
  lines.push(t('diff.markdown.heading', { risk: riskText(s.risk).toLocaleLowerCase(getLanguage()) }), '')
  lines.push(t('diff.markdown.compared', { before: result.before.name, after: result.after.name, counts: countsText(s.statements) }), '')
  const reasons = sortReasons(s.reasons)
  if (reasons.length) {
    lines.push(t('diff.markdown.why'), '')
    for (const r of reasons) {
      const where = lineText(r.beforeRange, r.afterRange)
      lines.push(`- **${r.level}** ${r.message}${where ? ` (${where})` : ''}`)
    }
    lines.push('')
  }
  const cols = columnRows(result.lineage?.columns ?? [])
  if (cols.length) {
    lines.push(t('diff.markdown.columns'), '', t('diff.markdown.columnHeader'), '| --- | --- | --- |')
    for (const c of cols) {
      const tf = sameList(c.beforeTransforms, c.afterTransforms) ? '' : ` (${c.beforeTransforms.join(', ') || t('diff.markdown.none')} → ${c.afterTransforms.join(', ') || t('diff.markdown.none')})`
      lines.push(`| ${cell(`${c.target}.${c.column}`)} | ${cell(c.before.join(', ') || '—')} | ${cell((c.after.join(', ') || '—') + tf)} |`)
    }
    lines.push('')
  }
  const indirect = result.lineage?.indirect ?? []
  if (indirect.length) {
    lines.push(t('diff.markdown.indirect'), '')
    for (const i of indirect) lines.push(`- ${i.message}`)
    lines.push('')
  }
  const findings = result.findings ?? []
  if (findings.length) {
    lines.push(t('diff.markdown.findings'), '')
    for (const f of findings) lines.push(`- ${f.change === 'NEW' ? t('diff.markdown.findingNew') : t('diff.markdown.findingResolved')} ${f.severity} ${f.rule}: ${findingTitle(f)}`)
    lines.push('')
  }
  if (s.notes?.length) {
    for (const n of s.notes) lines.push(`> ${n}`)
    lines.push('')
  }
  lines.push(t('diff.markdown.footer'))
  return lines.join('\n') + '\n'
}

/** Both versions read the same: nothing but unchanged statements and no reasons. */
export function noChanges(summary: DiffResult['summary']): boolean {
  const c = summary.statements
  return (summary.reasons ?? []).length === 0 && c.modified + c.added + c.removed + c.moved + c.formatOnly === 0
}

/** How many reasons the list shows before "Show all". */
export const REASONS_SHOWN = 12
