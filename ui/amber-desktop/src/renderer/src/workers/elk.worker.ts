/// <reference lib="webworker" />
import ELK, { type ElkNode } from 'elkjs/lib/elk.bundled.js'

// Graph layout runs here so large graphs never block the UI thread.
const elk = new ELK()

interface LayoutRequest {
  id: number
  graph: ElkNode
}

self.onmessage = async (event: MessageEvent<LayoutRequest>) => {
  const { id, graph } = event.data
  try {
    const result = await elk.layout(graph)
    const positions: Record<string, { x: number; y: number }> = {}
    for (const child of result.children ?? []) positions[child.id] = { x: child.x ?? 0, y: child.y ?? 0 }
    self.postMessage({ id, positions, width: result.width ?? 0, height: result.height ?? 0 })
  } catch (error) {
    self.postMessage({ id, error: error instanceof Error ? error.message : String(error) })
  }
}
