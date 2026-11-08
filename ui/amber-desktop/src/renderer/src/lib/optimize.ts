import type { Finding, OptimizedStatement, Range, StatementPair } from '@shared/protocol'
import { tOr } from '../i18n'
import { minimalChanges } from './format'
import { position, type TextChange } from './rewrites'

/**
 * Optimized SQL without AI: `rewrite.optimize` applies every non-conflicting verified rewrite of a
 * statement together; findings may carry their own optimized SQL. Helpers to word, choose and apply them.
 */

/**
 * The editor change that replaces a statement with its optimized text. The engine's `original` must be
 * what the script holds at the statement's range (or, when ranges and text disagree slightly, the first
 * exact occurrence at or after the statement start). Null when the script no longer has it.
 */
export function statementChange(text: string, range: Range | undefined, original: string, optimized: string): TextChange | null {
  if (!range || !original) return null
  const from = range.start.offset
  if (text.slice(from, from + original.length) === original) return { from, to: from + original.length, insert: optimized }
  const at = text.indexOf(original, Math.max(0, from - 1))
  if (at >= 0 && at <= range.end.offset) return { from: at, to: at + original.length, insert: optimized }
  return null
}

/** Changes sorted by position; overlapping ones are rejected. */
export function sortChanges(changes: TextChange[]): TextChange[] {
  const sorted = [...changes].sort((a, b) => a.from - b.from || a.to - b.to)
  for (let i = 1; i < sorted.length; i++) {
    if (sorted[i]!.from < sorted[i - 1]!.to) throw new Error('Overlapping changes cannot be applied together.')
  }
  return sorted
}

/** Whether "Apply" can replace the finding's statement with its SQL (one statement, not DDL). */
export function canApplyFindingSql(f: Pick<Finding, 'rule' | 'suggestedSql' | 'suggestedSqlKind' | 'verified'>): boolean {
  // AMB-P004 combines two statements into one: the SQL replaces more than the finding's statement.
  return Boolean(f.suggestedSql) && f.suggestedSqlKind === 'REWRITE_OF_STATEMENT' && f.rule !== 'AMB-P004' && f.verified !== 'INVALID_SQL'
}

/** "Apply all safe optimizations": statements whose every applied rewrite keeps the same result for any data. */
export function safeOptimizations(items: OptimizedStatement[]): OptimizedStatement[] {
  return items.filter((o) => o.safety === 'EQUIVALENT' && o.optimized !== o.original && o.check?.label !== 'INVALID_SQL')
}

/** Statements that change the SQL (not only suggest DDL). */
export function rewrittenStatements(items: OptimizedStatement[]): OptimizedStatement[] {
  return items.filter((o) => o.optimized && o.optimized !== o.original)
}

export function skipReasonText(reason: string): string {
  return tOr(`optimize.skip.${reason}`, reason)
}

/** A whole statement and its replacement as a diff pair for the side-by-side panes (changed lines highlighted). */
export function wholePair(before: string, after: string, statement: number): StatementPair {
  let delta = 0
  const hunks = minimalChanges(before, after).map((c) => {
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
  const whole = (text: string): Range => ({ start: position(text, 0), end: position(text, text.length) })
  return {
    status: 'MODIFIED',
    kind: 'REWRITE',
    target: null,
    beforeId: statement,
    afterId: statement,
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
