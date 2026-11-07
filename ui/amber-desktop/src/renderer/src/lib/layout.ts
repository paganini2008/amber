
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

export function layoutFlow(view: LayoutInput): Promise<LayoutResult> {
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
      'elk.separateConnectedComponents': 'true'
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
