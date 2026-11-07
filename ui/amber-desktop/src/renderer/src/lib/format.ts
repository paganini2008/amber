import type { Range, Statement } from '@shared/protocol'
import type { TextChange } from './rewrites'

/**
 * Formatting ("make a legacy script readable again"): the engine re-lays out the text and keeps
 * every token; here the result becomes a few small editor changes so the cursor, scroll and undo
 * history stay sensible.
 */

/** Lines with their line breaks, so joining them gives the text back. */
function splitLines(text: string): string[] {
  const out = text.match(/[^\n]*\n|[^\n]+$/g) ?? []
  return out
}

/** Above this many line pairs the diff falls back to one change between common prefix and suffix. */
const MAX_CELLS = 4_000_000

/**
 * The edits that turn `before` into `after`: unchanged lines are kept, each run of changed lines
 * becomes one change. Offsets refer to `before`.
 */
export function minimalChanges(before: string, after: string): TextChange[] {
  if (before === after) return []
  const a = splitLines(before)
  const b = splitLines(after)
  // Common prefix and suffix lines first; most formatting leaves some lines alone.
  let pre = 0
  while (pre < a.length && pre < b.length && a[pre] === b[pre]) pre++
  let suf = 0
  while (suf < a.length - pre && suf < b.length - pre && a[a.length - 1 - suf] === b[b.length - 1 - suf]) suf++
  const am = a.slice(pre, a.length - suf)
  const bm = b.slice(pre, b.length - suf)
  const offsetOf = (lines: string[], upTo: number): number => {
    let n = 0
    for (let i = 0; i < upTo; i++) n += lines[i]!.length
    return n
  }
  const base = offsetOf(a, pre)
  if (am.length * bm.length > MAX_CELLS) {
    return [{ from: base, to: base + am.join('').length, insert: bm.join('') }]
  }
  // LCS over the middle lines.
  const n = am.length
  const m = bm.length
  const dp: Uint32Array[] = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) dp[i]![j] = am[i] === bm[j] ? dp[i + 1]![j + 1]! + 1 : Math.max(dp[i + 1]![j]!, dp[i]![j + 1]!)
  }
  const changes: TextChange[] = []
  let i = 0
  let j = 0
  let pos = base
  let runFrom = -1
  let runInsert = ''
  let runLen = 0
  const flush = (): void => {
    if (runFrom >= 0) changes.push({ from: runFrom, to: runFrom + runLen, insert: runInsert })
    runFrom = -1
    runInsert = ''
    runLen = 0
  }
  while (i < n || j < m) {
    if (i < n && j < m && am[i] === bm[j]) {
      flush()
      pos += am[i]!.length
      i++
      j++
    } else if (j < m && (i >= n || dp[i]![j + 1]! >= dp[i + 1]![j]!)) {
      if (runFrom < 0) runFrom = pos
      runInsert += bm[j]
      j++
    } else {
      if (runFrom < 0) runFrom = pos
      runLen += am[i]!.length
      pos += am[i]!.length
      i++
    }
  }
  flush()
  return changes
}

/** Applies changes (offsets in the original text, ascending, not overlapping). */
export function applyChanges(text: string, changes: TextChange[]): string {
  let out = text
  for (const c of [...changes].sort((x, y) => y.from - x.from)) out = out.slice(0, c.from) + c.insert + out.slice(c.to)
  return out
}

export interface FormatOptions {
  indentSize: number
  keywordCase: 'UPPER' | 'LOWER' | 'PRESERVE'
  maxLineWidth: number
  commaStyle: 'TRAILING' | 'LEADING'
}

export const DEFAULT_FORMAT: FormatOptions = { indentSize: 4, keywordCase: 'UPPER', maxLineWidth: 100, commaStyle: 'TRAILING' }

const overlaps = (r: Range, from: number, to: number): boolean => r.start.offset < Math.max(to, from + 1) && from < r.end.offset

/**
 * "Formatted N statements · M kept as written": the top-level statements in scope (all, or the
 * ones the selection overlaps), minus those the formatter left alone.
 */
export function formatSummary(statements: Statement[], skipped: Range[], selection: { from: number; to: number } | null, changed: boolean): string {
  if (!changed) return selection ? 'The selected statements are already formatted.' : 'Already formatted: nothing to change.'
  const kept = skipped.length
  // A script that does not parse has no statement list to count; the formatter still lays out what it can.
  if (statements.length === 0) return kept ? `Formatted the script · ${kept} kept as written` : 'Formatted the script'
  const scope = selection ? statements.filter((s) => overlaps(s.range, selection.from, selection.to)) : statements
  const formatted = Math.max(0, scope.length - scope.filter((s) => skipped.some((k) => overlaps(s.range, k.start.offset, k.end.offset))).length)
  const parts = [`Formatted ${formatted} ${formatted === 1 ? 'statement' : 'statements'}`]
  if (kept) parts.push(`${kept} kept as written`)
  return parts.join(' · ')
}
