
export interface LayoutResult {
  positions: Map<string, { x: number; y: number }>
  width: number
  height: number
}

let worker: Worker | null = null
let nextId = 1
const pending = new Map<number, { resolve: (r: LayoutResult) => void; reject: (e: Error) => void }>()

function getWorker(): Worker {
  if (worker) return worker
  worker = new Worker(new URL('../workers/elk.worker.ts', import.meta.url), { type: 'module' })
  worker.onmessage = (event: MessageEvent<{ id: number; positions?: Record<string, { x: number; y: number }>; width?: number; height?: number; error?: string }>) => {
    const { id, positions, error, width = 0, height = 0 } = event.data
    const p = pending.get(id)
    if (!p) return
    pending.delete(id)
    if (error || !positions) p.reject(new Error(error ?? 'Layout failed'))
    else p.resolve({ positions: new Map(Object.entries(positions)), width, height })
  }
  worker.onerror = (event) => {
    for (const p of pending.values()) p.reject(new Error(event.message || 'Layout worker failed'))
    pending.clear()
    worker?.terminate()
    worker = null
  }
  return worker
}

/** Lays out the graph left to right with ELK's layered algorithm, in a Web Worker. */
export interface LayoutInput {
  nodes: { id: string; width: number; height: number }[]
  edges: { id: string; source: string; target: string }[]
}

/**
 * Above these sizes ELK's layered algorithm needs more memory than a Web Worker may use (a 100k-line
 * script's data-flow graph crashed the renderer with a V8 out-of-memory error), so a linear-time
 * layered layout is used instead: readable columns, no crossing minimization.
 */
export const ELK_MAX_NODES = 400
export const ELK_MAX_EDGES = 2000

/**
 * Left-to-right layers by longest path from the sources (cycles are cut by a pass limit), nodes stacked
 * in input order within a layer. O(nodes + edges · passes).
 */
export function simpleLayout(view: LayoutInput, gapX = 64, gapY = 16): LayoutResult {
  const layer = new Map(view.nodes.map((n) => [n.id, 0]))
  const passes = Math.min(view.nodes.length, 50)
  for (let p = 0; p < passes; p++) {
    let changed = false
    for (const e of view.edges) {
      const a = layer.get(e.source)
      const b = layer.get(e.target)
      if (a === undefined || b === undefined || e.source === e.target) continue
      if (b < a + 1) {
        layer.set(e.target, a + 1)
        changed = true
      }
    }
    if (!changed) break
  }
  const columns = new Map<number, LayoutInput['nodes']>()
  for (const n of view.nodes) {
    const l = layer.get(n.id) ?? 0
    columns.set(l, [...(columns.get(l) ?? []), n])
  }
  const positions = new Map<string, { x: number; y: number }>()
  let x = 0
  let height = 0
  for (const l of [...columns.keys()].sort((a, b) => a - b)) {
    const col = columns.get(l)!
    let y = 0
    let width = 0
    for (const n of col) {
      positions.set(n.id, { x, y })
      y += n.height + gapY
      width = Math.max(width, n.width)
    }
    height = Math.max(height, y)
    x += width + gapX
  }
  return { positions, width: Math.max(0, x - gapX), height: Math.max(0, height - gapY) }
}

/** `options` override ELK layout options (e.g. spacing, model order). */
export function layoutFlow(view: LayoutInput, options: Record<string, string> = {}): Promise<LayoutResult> {
  if (view.nodes.length > ELK_MAX_NODES || view.edges.length > ELK_MAX_EDGES) return Promise.resolve(simpleLayout(view))
  const graph = {
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'RIGHT',
      'elk.layered.spacing.nodeNodeBetweenLayers': '64',
      'elk.spacing.nodeNode': '16',
      'elk.spacing.componentComponent': '40',
      'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
      'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
      'elk.separateConnectedComponents': 'true',
      ...options
    },
    children: view.nodes.map((n) => ({ id: n.id, width: n.width, height: n.height })),
    edges: view.edges.map((e) => ({ id: e.id, sources: [e.source], targets: [e.target] }))
  }
  const id = nextId++
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
    getWorker().postMessage({ id, graph })
  })
}
