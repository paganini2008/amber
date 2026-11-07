import { useEffect, useMemo, useState } from 'react'
import { emptyTrace, traceObject, traceStatement, type Trace } from './lib/dataflow'
import { playedSet } from './lib/playback'
import { useStore } from './store'

/**
 * The amber path for the current selection: what is lit, so everything else can recede.
 * Returns null when nothing is selected. Selecting a statement with no data refs lights it alone.
 */
export function useTrace(): Trace | null {
  const selection = useStore((s) => s.selection)
  const graph = useStore((s) => s.graph)
  const index = useStore((s) => s.index)
  return useMemo(() => {
    if (!selection || !graph || !index) return null
    if (selection.type === 'object') return traceObject(graph, selection.key)
    if (graph.readsOf.has(selection.id)) return traceStatement(graph, selection.id)
    const t = emptyTrace()
    t.statements.add(selection.id)
    // Structural statements (CREATE, DROP, …) still light the objects they touch.
    for (const r of index.byId.get(selection.id)?.refs ?? []) {
      if (index.lifecycleByKey.has(r.key)) t.objects.add(r.key)
    }
    return t
  }, [selection, graph, index])
}

export interface PlaybackView {
  active: boolean
  current: number | null
  played: Set<number>
}

export function usePlaybackView(): PlaybackView {
  const pb = useStore((s) => s.playback)
  return useMemo(() => {
    if (!pb.active) return { active: false, current: null, played: new Set<number>() }
    return { active: true, current: pb.plan[pb.cursor]?.id ?? null, played: playedSet(pb.plan, pb.cursor) }
  }, [pb.active, pb.plan, pb.cursor])
}

export function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(() => window.matchMedia('(prefers-reduced-motion: reduce)').matches)
  useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)')
    const onChange = (): void => setReduced(mq.matches)
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])
  return reduced
}

export function useSystemDark(): boolean {
  const [dark, setDark] = useState(() => window.matchMedia('(prefers-color-scheme: dark)').matches)
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = (): void => setDark(mq.matches)
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])
  return dark
}

/** The window's inner width, updated on resize. */
export function useWindowWidth(): number {
  const [width, setWidth] = useState(() => window.innerWidth)
  useEffect(() => {
    const onResize = (): void => setWidth(window.innerWidth)
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])
  return width
}
