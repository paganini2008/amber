import clsx from 'clsx'
import { ArrowLeft } from 'lucide-react'
import { useMemo } from 'react'
import type { LineageEdge, LineageNode } from '@shared/protocol'
import { isRows, ownerKeyForObject, ownerKindLabel, statementLineage, transformLabel } from '@/lib/lineage'
import { useStore } from '@/store'

function nodeText(n: LineageNode, kind: string): string {
  if (kind === 'RESULT') return n.column
  if (kind === 'UNKNOWN') return `?.${n.column}`
  return `${n.owner}.${n.column}`
}

/**
 * The statement's own column mapping: each output column and the columns it is computed from,
 * including hops through CTEs and subqueries, from the lineage edges this statement creates.
 */
export function ColumnMapping({ statementId, compact, lit }: { statementId: number; compact?: boolean; lit?: boolean }): React.JSX.Element | null {
  const lineage = useStore((s) => s.lineage)
  const traceColumn = useStore((s) => s.traceColumn)
  const selectStatement = useStore((s) => s.selectStatement)
  const markRange = useStore((s) => s.markRange)
  const data = useMemo(() => (lineage ? statementLineage(lineage, statementId) : null), [lineage, statementId])
  if (!lineage || !data || (data.mappings.length === 0 && data.indirect.length === 0)) return null
  const kindOf = (n: LineageNode): string => lineage.owners.get(n.ownerKey)?.kind ?? n.ownerKind
  const showEdge = (e: LineageEdge): void => {
    selectStatement(e.statement, 'view', false)
    markRange({ from: e.range.start.offset, to: e.range.end.offset })
  }

  const ColumnButton = ({ node, strong }: { node: LineageNode; strong?: boolean }): React.JSX.Element => {
    const kind = kindOf(node)
    return (
      <button
        type="button"
        onClick={() => traceColumn(node.id)}
        title={`Trace ${nodeText(node, kind)} (${ownerKindLabel(kind)})`}
        className={clsx(
          'code max-w-full truncate rounded-[3px] px-1 text-left text-[12px] hover:underline',
          strong ? 'text-ink' : 'text-ink-muted',
          (kind === 'CTE' || kind === 'SUBQUERY') && 'border border-dashed border-line',
          kind === 'UNKNOWN' && 'italic'
        )}
      >
        {nodeText(node, kind)}
      </button>
    )
  }

  const outputs = compact ? data.mappings.slice(0, 12) : data.mappings
  return (
    <section>
      <h3 className={clsx('mb-2 font-semibold', compact ? 'text-sm' : 'text-base')}>Columns</h3>
      <ul className="space-y-1.5">
        {outputs.map((m) => (
          <li key={m.output.id} className="grid grid-cols-[minmax(90px,max-content)_1fr] items-start gap-x-2 text-sm">
            <button
              type="button"
              onClick={() => traceColumn(m.output.id)}
              className={clsx('code truncate text-left text-[12.5px] font-medium hover:underline', lit ? 'text-amber-ink' : 'text-ink')}
              title={`Trace ${m.output.owner}.${m.output.column}`}
            >
              {m.output.column}
            </button>
            <div className="min-w-0 space-y-0.5">
              {m.paths.length === 0 ? <span className="text-xs text-ink-faint">constant</span> : null}
              {m.paths.slice(0, compact ? 2 : 6).map((p, i) => (
                <div key={i} className="flex min-w-0 flex-wrap items-center gap-x-1 gap-y-0.5">
                  {p.nodes
                    .slice(0, -1)
                    .reverse()
                    .map((n, j, arr) => {
                      const edge = p.edges[p.edges.length - 1 - j]!
                      return (
                        <span key={`${n.id}-${j}`} className="flex min-w-0 items-center gap-1">
                          <button
                            type="button"
                            onClick={() => showEdge(edge)}
                            className="flex items-center gap-0.5 text-xs text-ink-faint hover:text-ink"
                            title="Show where this happens"
                          >
                            <ArrowLeft size={11} />
                            {transformLabel(edge) !== 'copy' ? <span>{transformLabel(edge)}</span> : null}
                          </button>
                          <ColumnButton node={n} strong={j === arr.length - 1} />
                        </span>
                      )
                    })}
                </div>
              ))}
              {m.paths.length > (compact ? 2 : 6) ? <div className="text-xs text-ink-faint">+{m.paths.length - (compact ? 2 : 6)} more sources</div> : null}
            </div>
          </li>
        ))}
        {compact && data.mappings.length > outputs.length ? <li className="text-xs text-ink-faint">+{data.mappings.length - outputs.length} more columns</li> : null}
      </ul>
      {data.indirect.length && !compact ? (
        <div className="mt-3">
          <div className="mb-1 text-sm text-ink-muted">Which rows arrive is decided by</div>
          <ul className="flex flex-wrap gap-x-3 gap-y-1">
            {dedupe(data.indirect).map((x) => (
              <li key={`${x.source.id}-${x.role}`} className="flex items-center gap-1 text-sm">
                <ColumnButton node={x.source} strong />
                <button type="button" className="text-xs text-ink-faint hover:text-ink" onClick={() => showEdge(x.edge)}>
                  {x.role.toLowerCase()}
                </button>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  )
}

function dedupe<T extends { source: LineageNode; role: string }>(list: T[]): T[] {
  const seen = new Set<string>()
  return list.filter((x) => {
    const k = `${x.source.id}|${x.role}`
    if (seen.has(k)) return false
    seen.add(k)
    return true
  })
}

/** The columns Amber saw for a table or view, each one a link into the Lineage view. */
export function ObjectColumns({ objectKey }: { objectKey: string }): React.JSX.Element | null {
  const lineage = useStore((s) => s.lineage)
  const traceColumn = useStore((s) => s.traceColumn)
  const showOwnerColumns = useStore((s) => s.showOwnerColumns)
  const ownerKey = lineage ? ownerKeyForObject(lineage, objectKey) : null
  const owner = ownerKey ? lineage!.owners.get(ownerKey) : undefined
  if (!lineage || !owner) return null
  const columns = owner.nodeIds.map((id) => lineage.nodes.get(id)!).filter((n) => !isRows(n))
  if (!columns.length) return null
  return (
    <section>
      <div className="mb-1.5 flex items-baseline justify-between">
        <h3 className="text-sm font-semibold">Columns seen in the script</h3>
        <button type="button" className="text-xs text-ink-muted hover:text-ink hover:underline" onClick={() => showOwnerColumns(owner.key)}>
          Open in lineage
        </button>
      </div>
      <ul className="flex flex-wrap gap-1.5">
        {columns.map((c) => (
          <li key={c.id}>
            <button type="button" onClick={() => traceColumn(c.id)} className="code rounded-[3px] border border-line px-1.5 text-[12px] hover:border-amber hover:text-amber-ink">
              {c.column}
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}
