import type { Finding, Range, Rewrite, RewriteEdit, StatementPair } from '@shared/protocol'
import { t } from '../i18n'

/**
 * Rule-based quick fixes (`rewrite.suggest`): how they are worded, grouped, matched to findings
 * and applied. The engine proposes and checks every rewrite; nothing here judges SQL.
 */

export type SafetyTone = 'same' | 'metadata' | 'edge'

/** Plain wording for the safety level. Never "safe": a rewrite either keeps the result or it does not. */
export function safetyText(safety: string): { label: string; tone: SafetyTone; detail: string } {
  switch (safety) {
    case 'EQUIVALENT':
      return { label: t('fixes.safety.EQUIVALENT.label'), tone: 'same', detail: t('fixes.safety.EQUIVALENT.detail') }
    case 'EQUIVALENT_WITH_METADATA':
      return { label: t('fixes.safety.EQUIVALENT_WITH_METADATA.label'), tone: 'metadata', detail: t('fixes.safety.EQUIVALENT_WITH_METADATA.detail') }
    case 'CHANGES_BEHAVIOR_IN_EDGE_CASES':
      return { label: t('fixes.safety.CHANGES_BEHAVIOR_IN_EDGE_CASES.label'), tone: 'edge', detail: t('fixes.safety.CHANGES_BEHAVIOR_IN_EDGE_CASES.detail') }
    default:
      return { label: safety ? safety.charAt(0) + safety.slice(1).toLowerCase().replace(/_/g, ' ') : t('fixes.safety.notRated'), tone: 'edge', detail: '' }
  }
}

/** Rewrites of one statement, in the engine's order. */
export function groupByStatement(rewrites: Rewrite[]): { statement: number; items: Rewrite[] }[] {
  const groups: { statement: number; items: Rewrite[] }[] = []
  for (const r of rewrites) {
    const g = groups.find((x) => x.statement === r.statement)
    if (g) g.items.push(r)
    else groups.push({ statement: r.statement, items: [r] })
  }
  return groups.sort((a, b) => a.statement - b.statement)
}

/** The rewrites that fix a finding: same rule, same statement. */
export function rewritesFor(rewrites: Rewrite[], finding: Pick<Finding, 'rule' | 'statement'>): Rewrite[] {
  return rewrites.filter((r) => r.rule === finding.rule && r.statement === finding.statement)
}

/** Rewrites at a document offset: an edit or the finding they fix covers it, else the statement does. */
export function rewritesAt(rewrites: Rewrite[], offset: number, statementRanges: Map<number, Range>): Rewrite[] {
  const onEdit = rewrites.filter((r) => r.edits.some((e) => offset >= e.range.start.offset && offset <= e.range.end.offset))
  if (onEdit.length) return onEdit
  return rewrites.filter((r) => {
    const s = statementRanges.get(r.statement)
    return s ? offset >= s.start.offset && offset <= s.end.offset : false
  })
}

export function isBlocked(r: Rewrite): boolean {
  return Boolean(r.conflictsWith)
}

const overlaps = (a: RewriteEdit, b: RewriteEdit): boolean =>
  a.range.start.offset < b.range.end.offset && b.range.start.offset < a.range.end.offset ||
  // Two insertions at the same point would be ambiguous too.
  (a.range.start.offset === b.range.start.offset && a.range.start.offset === a.range.end.offset && b.range.start.offset === b.range.end.offset)

/**
 * The batch for "Apply all with 'Same result'": EQUIVALENT rewrites only, without ones the engine
 * marks as conflicting and without any whose edits overlap a rewrite already taken.
 */
export function sameResultBatch(rewrites: Rewrite[]): { apply: Rewrite[]; skipped: Rewrite[] } {
  const apply: Rewrite[] = []
  const skipped: Rewrite[] = []
  for (const r of rewrites) {
    if (r.safety !== 'EQUIVALENT') continue
    if (isBlocked(r) || apply.some((a) => a.edits.some((e) => r.edits.some((f) => overlaps(e, f))))) skipped.push(r)
    else apply.push(r)
  }
  return { apply, skipped }
}

export interface TextChange {
  from: number
  to: number
  insert: string
}

/** Edits as editor changes against the current text, sorted, overlaps rejected. */
export function toChanges(edits: RewriteEdit[]): TextChange[] {
  const changes = edits.map((e) => ({ from: e.range.start.offset, to: e.range.end.offset, insert: e.replacement })).sort((a, b) => a.from - b.from || a.to - b.to)
  for (let i = 1; i < changes.length; i++) {
    if (changes[i]!.from < changes[i - 1]!.to) throw new Error('Overlapping edits cannot be applied together.')
  }
  return changes
}

/** Applies edits to a text, last one first so earlier offsets stay valid. */
export function applyEdits(text: string, edits: RewriteEdit[]): string {
  let out = text
  for (const c of [...toChanges(edits)].reverse()) out = out.slice(0, c.from) + c.insert + out.slice(c.to)
  return out
}

export function position(text: string, offset: number): { offset: number; line: number; column: number } {
  let line = 1
  let last = -1
  for (let i = 0; i < offset && i < text.length; i++) {
    if (text.charCodeAt(i) === 10) {
      line++
      last = i
    }
  }
  return { offset, line, column: offset - last }
}

/**
 * The statement before and after a rewrite as a diff pair for the side-by-side panes: the old
 * side is the statement's source, the new side applies the edits, and each edit is one hunk.
 */
export function previewPair(source: string, statementRange: Range, rewrite: Rewrite): { before: string; after: string; pair: StatementPair } {
  const start = statementRange.start.offset
  const before = source.slice(start, statementRange.end.offset)
  const local = rewrite.edits.map((e) => ({
    range: {
      start: { ...e.range.start, offset: e.range.start.offset - start },
      end: { ...e.range.end, offset: e.range.end.offset - start }
    },
    replacement: e.replacement
  }))
  const after = applyEdits(before, local)
  let delta = 0
  const hunks = toChanges(local).map((c) => {
    const afterFrom = c.from + delta
    const afterTo = afterFrom + c.insert.length
    delta += c.insert.length - (c.to - c.from)
    return {
      before: { start: position(before, c.from), end: position(before, c.to) },
      after: { start: position(after, afterFrom), end: position(after, afterTo) },
      beforeText: before.slice(c.from, c.to),
      afterText: c.insert
    }
  })
  const whole = (t: string): Range => ({ start: position(t, 0), end: position(t, t.length) })
  return {
    before,
    after,
    pair: {
      status: 'MODIFIED',
      kind: 'REWRITE',
      target: null,
      beforeId: rewrite.statement,
      afterId: rewrite.statement,
      beforeRange: whole(before),
      afterRange: whole(after),
      beforeSummary: null,
      afterSummary: null,
      similarity: 1,
      reordered: false,
      hunks,
      children: []
    }
  }
}
