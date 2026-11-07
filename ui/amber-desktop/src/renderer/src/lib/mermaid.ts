import type { FlowGraph } from './dataflow'
import type { AnalysisIndex } from './model'

/**
 * Object-level data flow as a Mermaid `flowchart LR`: an edge A → B means some statement
 * reads A and writes data into B. Edge labels list the statement kinds involved.
 */
export function toMermaid(index: AnalysisIndex, graph: FlowGraph): string {
  const ids = new Map<string, string>()
  const idFor = (key: string): string => {
    let id = ids.get(key)
    if (!id) {
      id = `n${ids.size}`
      ids.set(key, id)
    }
    return id
  }

  const edges = new Map<string, Set<string>>()
  for (const s of graph.statements) {
    const reads = graph.readsOf.get(s) ?? new Set<string>()
    const writes = graph.writesOf.get(s) ?? new Set<string>()
    const kind = index.byId.get(s)?.kind.replace(/_/g, ' ') ?? ''
    for (const from of reads) {
      for (const to of writes) {
        if (from === to) continue
        const k = `${from}\u0000${to}`
        const set = edges.get(k) ?? new Set<string>()
        set.add(kind)
        edges.set(k, set)
      }
    }
  }

  const lines = ['flowchart LR']
  const objects = [...graph.objects].sort((a, b) => firstStep(index, a) - firstStep(index, b))
  for (const key of objects) {
    const l = index.lifecycleByKey.get(key)
    const label = escapeLabel(l?.name ?? key)
    const id = idFor(key)
    switch (l?.role) {
      case 'SOURCE':
        lines.push(`  ${id}[("${label}")]:::source`)
        break
      case 'TARGET':
        lines.push(`  ${id}[["${label}"]]:::target`)
        break
      case 'INTERMEDIATE':
        lines.push(`  ${id}(["${label}"]):::intermediate`)
        break
      default:
        lines.push(`  ${id}["${label}"]:::structure`)
    }
  }
  for (const [k, kinds] of edges) {
    const [from, to] = k.split('\u0000') as [string, string]
    const label = escapeLabel([...kinds].join(', '))
    lines.push(`  ${idFor(from)} -->|"${label}"| ${idFor(to)}`)
  }
  lines.push('  classDef source fill:#F4F5F7,stroke:#5A626C,color:#1B1F24')
  lines.push('  classDef intermediate fill:#FFFFFF,stroke:#5A626C,stroke-dasharray:4 3,color:#1B1F24')
  lines.push('  classDef target fill:#FFF4DE,stroke:#8F5600,color:#1B1F24')
  lines.push('  classDef structure fill:#FFFFFF,stroke:#DCE0E5,color:#5A626C')
  return lines.join('\n') + '\n'
}

function firstStep(index: AnalysisIndex, key: string): number {
  return index.lifecycleByKey.get(key)?.events[0]?.step ?? Number.MAX_SAFE_INTEGER
}

export function escapeLabel(text: string): string {
  return text.replace(/"/g, '#quot;').replace(/[<>]/g, (c) => (c === '<' ? '#lt;' : '#gt;')).replace(/[\r\n]+/g, ' ')
}
