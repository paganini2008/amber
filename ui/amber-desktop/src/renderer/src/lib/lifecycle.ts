import type { Lifecycle } from '@shared/protocol'
import type { AnalysisIndex } from './model'

export type LifecycleSort = 'role' | 'first-use' | 'name' | 'kind'

export const ROLE_ORDER = ['SOURCE', 'INTERMEDIATE', 'TARGET', 'STRUCTURE']

export interface MatrixRow {
  type: 'header' | 'object'
  /** Header label, or object key. */
  key: string
  label: string
  lifecycle?: Lifecycle
  count?: number
}

export interface MatrixModel {
  rows: MatrixRow[]
  columns: number[]
  /** `${key}|${step}` → accesses, in event order. */
  cells: Map<string, string[]>
}

export const cellKey = (key: string, step: number): string => `${key}|${step}`

/** Access marker letters for the matrix. Shape (by class) and letter both carry meaning. */
export const ACCESS_MARK: Record<string, { letter: string; label: string; cls: 'write' | 'read' | 'struct' | 'exec' }> = {
  CREATE: { letter: 'C', label: 'Create', cls: 'struct' },
  INSERT: { letter: 'I', label: 'Insert', cls: 'write' },
  UPDATE: { letter: 'U', label: 'Update', cls: 'write' },
  DELETE: { letter: 'D', label: 'Delete', cls: 'write' },
  MERGE: { letter: 'M', label: 'Merge', cls: 'write' },
  READ: { letter: 'R', label: 'Read', cls: 'read' },
  DROP: { letter: 'X', label: 'Drop', cls: 'struct' },
  TRUNCATE: { letter: 'T', label: 'Truncate', cls: 'struct' },
  ALTER: { letter: 'A', label: 'Alter', cls: 'struct' },
  EXECUTE: { letter: 'E', label: 'Execute', cls: 'exec' }
}

export function markFor(access: string): { letter: string; label: string; cls: 'write' | 'read' | 'struct' | 'exec' } {
  return ACCESS_MARK[access] ?? { letter: access.charAt(0), label: access.toLowerCase(), cls: 'read' }
}

export interface MatrixOptions {
  sort: LifecycleSort
  filter: string
  /** Show every step, not only steps that touch an object. */
  allSteps: boolean
}

export function buildMatrix(index: AnalysisIndex, options: MatrixOptions): MatrixModel {
  const needle = options.filter.trim().toLowerCase()
  const lifecycles = (index.result.lifecycles ?? []).filter(
    (l) => !needle || l.name.toLowerCase().includes(needle) || l.kind.toLowerCase().includes(needle)
  )
  const cells = new Map<string, string[]>()
  const used = new Set<number>()
  for (const l of lifecycles) {
    for (const e of l.events) {
      const k = cellKey(l.key, e.step)
      const list = cells.get(k)
      if (list) {
        if (!list.includes(e.access)) list.push(e.access)
      } else {
        cells.set(k, [e.access])
      }
      used.add(e.step)
    }
  }
  const firstStep = (l: Lifecycle): number => l.events[0]?.step ?? Number.MAX_SAFE_INTEGER
  const byName = (a: Lifecycle, b: Lifecycle): number => a.name.localeCompare(b.name)
  const rows: MatrixRow[] = []
  const objectRow = (l: Lifecycle): MatrixRow => ({ type: 'object', key: l.key, label: l.name, lifecycle: l })

  if (options.sort === 'role' || options.sort === 'kind') {
    const groupKey = (l: Lifecycle): string => (options.sort === 'role' ? l.role : l.kind)
    const groups = new Map<string, Lifecycle[]>()
    for (const l of lifecycles) {
      const g = groupKey(l)
      const list = groups.get(g)
      if (list) list.push(l)
      else groups.set(g, [l])
    }
    const order = [...groups.keys()].sort((a, b) => {
      if (options.sort === 'role') return rank(ROLE_ORDER, a) - rank(ROLE_ORDER, b)
      return a.localeCompare(b)
    })
    for (const g of order) {
      const list = groups.get(g)!.sort((a, b) => firstStep(a) - firstStep(b) || byName(a, b))
      rows.push({ type: 'header', key: `header:${g}`, label: groupLabel(options.sort, g), count: list.length })
      rows.push(...list.map(objectRow))
    }
  } else {
    const sorted = [...lifecycles].sort(options.sort === 'name' ? byName : (a, b) => firstStep(a) - firstStep(b) || byName(a, b))
    rows.push(...sorted.map(objectRow))
  }

  const columns = options.allSteps ? index.order.map((s) => s.id) : [...used].sort((a, b) => a - b)
  return { rows, columns, cells }
}

function rank(order: string[], value: string): number {
  const i = order.indexOf(value)
  return i < 0 ? order.length : i
}

function groupLabel(sort: LifecycleSort, value: string): string {
  if (sort === 'role') {
    switch (value) {
      case 'SOURCE':
        return 'Sources'
      case 'INTERMEDIATE':
        return 'Intermediate'
      case 'TARGET':
        return 'Targets'
      case 'STRUCTURE':
        return 'Structure only'
    }
  }
  const lower = value.replace(/_/g, ' ').toLowerCase()
  return lower.charAt(0).toUpperCase() + lower.slice(1)
}
