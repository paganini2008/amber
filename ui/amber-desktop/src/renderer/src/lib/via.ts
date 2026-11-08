import type { LifecycleEvent, Ref, Statement } from '@shared/protocol'
import { WRITE_ACCESS } from './model'
import { t } from '../i18n'

/**
 * Call linking: when a statement calls a routine defined in the script, its refs also list what the
 * routine's body reads and writes, with `via` set to the routine. These helpers keep direct mentions
 * and objects reached through calls apart, so views can say "Inserts into dw.daily · via dw.p_load".
 */

export interface ViaGroup {
  /** The routine as written, e.g. "dw.p_load". */
  routine: string
  refs: Ref[]
}

/** Direct refs, and refs reached through each called routine (in the order the routines appear). */
export function splitRefs(refs: Ref[]): { direct: Ref[]; via: ViaGroup[] } {
  const direct: Ref[] = []
  const groups = new Map<string, ViaGroup>()
  for (const r of refs) {
    if (!r.via) {
      direct.push(r)
      continue
    }
    const g = groups.get(r.via) ?? { routine: r.via, refs: [] }
    if (!g.refs.some((x) => x.key === r.key && x.access === r.access)) g.refs.push(r)
    groups.set(r.via, g)
  }
  return { direct, via: [...groups.values()] }
}

export interface ViaSummary {
  routine: string
  /** Distinct objects written (insert, update, delete, merge, truncate, create, alter, drop). */
  writes: number
  /** Distinct objects only read. */
  reads: number
}

/** Per called routine: how many objects it writes and reads (CTEs and routines themselves left out). */
export function viaSummary(statement: Pick<Statement, 'refs'>): ViaSummary[] {
  const out: ViaSummary[] = []
  for (const g of splitRefs(statement.refs).via) {
    const data = g.refs.filter((r) => r.kind !== 'CTE' && r.access !== 'EXECUTE')
    const written = new Set(data.filter((r) => WRITE_ACCESS.has(r.access)).map((r) => r.key))
    const read = new Set(data.filter((r) => r.access === 'READ' && !written.has(r.key)).map((r) => r.key))
    if (written.size || read.size) out.push({ routine: g.routine, writes: written.size, reads: read.size })
  }
  return out
}

/** "writes 2 tables via dw.p_load", "reads 1 table via dw.p_load", "writes 2 and reads 1 table via …". */
export function viaBadgeText(s: ViaSummary): string {
  const params = { writes: s.writes, reads: s.reads, routine: s.routine }
  if (s.writes && s.reads) return t('outline.via.writesReads', params)
  if (s.writes) return t('outline.via.writes', params)
  return t('outline.via.reads', params)
}

/** The routine(s) a lifecycle cell's accesses come through, and the tooltip line for each event. */
export function eventLabel(e: Pick<LifecycleEvent, 'access' | 'via' | 'range'>): string {
  const params = { access: e.access, via: e.via ?? '', line: e.range.start.line }
  return e.via ? t('lifecycle.event.via', params) : t('lifecycle.event.direct', params)
}

/** True when every access in the cell comes through a call (no direct mention in that statement). */
export function onlyViaCalls(events: Pick<LifecycleEvent, 'via'>[]): boolean {
  return events.length > 0 && events.every((e) => Boolean(e.via))
}
