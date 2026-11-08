import type { AnalysisResult, Lifecycle, Ref, Statement } from '@shared/protocol'
import { t } from '../i18n'

/** Object kinds that carry data and appear in the lifecycle matrix and data-flow graph. */
export const DATA_KINDS = new Set(['TABLE', 'TEMP_TABLE', 'TABLE_VARIABLE', 'VIEW', 'MATERIALIZED_VIEW', 'SYNONYM', 'OTHER'])

export interface AnalysisIndex {
  result: AnalysisResult
  byId: Map<number, Statement>
  parentOf: Map<number, number | null>
  depthOf: Map<number, number>
  /** Statements in source order (depth first), i.e. by step id. */
  order: Statement[]
  /** Statements sorted by start offset, with their end, for cursor lookup. */
  byOffset: { start: number; end: number; id: number; depth: number }[]
  lifecycleByKey: Map<string, Lifecycle>
  /** Statement ids that reference each object key (non-CTE refs). */
  stepsByObject: Map<string, number[]>
}

export function indexAnalysis(result: AnalysisResult): AnalysisIndex {
  const byId = new Map<number, Statement>()
  const parentOf = new Map<number, number | null>()
  const depthOf = new Map<number, number>()
  const order: Statement[] = []
  const walk = (list: Statement[], parent: number | null, depth: number): void => {
    for (const s of list) {
      byId.set(s.id, s)
      parentOf.set(s.id, parent)
      depthOf.set(s.id, depth)
      order.push(s)
      walk(s.children ?? [], s.id, depth + 1)
    }
  }
  walk(result.statements ?? [], null, 0)
  order.sort((a, b) => a.id - b.id)

  const byOffset = order
    .map((s) => ({ start: s.range.start.offset, end: s.range.end.offset, id: s.id, depth: depthOf.get(s.id) ?? 0 }))
    .sort((a, b) => a.start - b.start || a.depth - b.depth)

  const lifecycleByKey = new Map<string, Lifecycle>()
  const stepsByObject = new Map<string, number[]>()
  for (const l of result.lifecycles ?? []) {
    lifecycleByKey.set(l.key, l)
    stepsByObject.set(l.key, [...new Set(l.events.map((e) => e.step))].sort((a, b) => a - b))
  }
  return { result, byId, parentOf, depthOf, order, byOffset, lifecycleByKey, stepsByObject }
}

/** The innermost statement whose range contains `offset`, or null between statements. */
export function statementAtOffset(index: AnalysisIndex, offset: number): number | null {
  const list = index.byOffset
  if (list.length === 0) return null
  // Binary search for the last start <= offset.
  let lo = 0
  let hi = list.length - 1
  let last = -1
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (list[mid]!.start <= offset) {
      last = mid
      lo = mid + 1
    } else {
      hi = mid - 1
    }
  }
  if (last < 0) return null
  // Walk back to find the deepest range containing the offset.
  let best: { id: number; depth: number } | null = null
  for (let i = last; i >= 0; i--) {
    const e = list[i]!
    if (offset >= e.start && offset <= e.end) {
      if (!best || e.depth > best.depth) best = e
      if (e.depth === 0) break
    } else if (e.depth === 0 && e.end < offset) {
      break
    }
  }
  return best?.id ?? null
}

/** Ancestors from the root to the statement's parent. */
export function ancestors(index: AnalysisIndex, id: number): Statement[] {
  const out: Statement[] = []
  let p = index.parentOf.get(id) ?? null
  while (p !== null) {
    const s = index.byId.get(p)
    if (!s) break
    out.unshift(s)
    p = index.parentOf.get(p) ?? null
  }
  return out
}

/** Refs that point at data objects tracked by lifecycles (CTEs and program objects excluded). */
export function dataRefs(index: AnalysisIndex, statement: Statement): Ref[] {
  return statement.refs.filter((r) => r.kind !== 'CTE' && index.lifecycleByKey.has(r.key))
}

export const WRITE_ACCESS = new Set(['CREATE', 'INSERT', 'UPDATE', 'DELETE', 'MERGE', 'TRUNCATE', 'ALTER', 'DROP'])
/** Accesses that move data into an object (used for data-flow edges). */
export const DATA_WRITE_ACCESS = new Set(['INSERT', 'UPDATE', 'DELETE', 'MERGE'])

export function isRead(access: string): boolean {
  return access === 'READ'
}

/** Whether a statement writes data into the object (CREATE counts when the statement also reads, e.g. CTAS). */
export function writesData(statement: Statement, ref: Ref): boolean {
  if (DATA_WRITE_ACCESS.has(ref.access)) return true
  if (ref.access === 'CREATE') return statement.refs.some((r) => r.access === 'READ' && r.kind !== 'CTE')
  return false
}

export function lineLabel(s: Statement): string {
  const a = s.range.start.line
  const b = s.range.end.line
  return a === b ? t('common.lineCap', { line: a }) : t('common.linesCap', { from: a, to: b })
}

export function humanize(value: string): string {
  const lower = value.replace(/_/g, ' ').toLowerCase()
  return lower.charAt(0).toUpperCase() + lower.slice(1)
}
