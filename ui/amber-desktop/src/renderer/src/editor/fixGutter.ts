import { RangeSet, StateEffect, StateField, type Extension } from '@codemirror/state'
import { GutterMarker, gutter, type EditorView } from '@codemirror/view'

export interface GutterFix {
  /** Where the fix applies (its first edit). */
  offset: number
  ids: string[]
  title: string
}

const BULB =
  '<svg width="13" height="13" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
  '<path d="M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5"/><path d="M9 18h6"/><path d="M10 22h4"/></svg>'

class FixMarker extends GutterMarker {
  constructor(
    readonly ids: string[],
    readonly title: string
  ) {
    super()
  }

  override eq(other: GutterMarker): boolean {
    return other instanceof FixMarker && other.ids.join() === this.ids.join()
  }

  override toDOM(): Node {
    const el = document.createElement('span')
    el.className = 'cm-fix-marker'
    el.title = this.ids.length > 1 ? `${this.ids.length} quick fixes (Cmd/Ctrl+.)` : `Quick fix: ${this.title} (Cmd/Ctrl+.)`
    el.setAttribute('aria-label', el.title)
    el.innerHTML = BULB
    return el
  }
}

const setFixes = StateEffect.define<GutterFix[]>()

const field = StateField.define<RangeSet<FixMarker>>({
  create: () => RangeSet.empty,
  update(value, tr) {
    let next = value.map(tr.changes)
    for (const e of tr.effects) {
      if (!e.is(setFixes)) continue
      const byLine = new Map<number, { ids: string[]; title: string; pos: number }>()
      for (const f of e.value) {
        if (f.offset > tr.state.doc.length) continue
        const line = tr.state.doc.lineAt(f.offset)
        const cur = byLine.get(line.number)
        if (cur) cur.ids.push(...f.ids)
        else byLine.set(line.number, { ids: [...f.ids], title: f.title, pos: line.from })
      }
      next = RangeSet.of([...byLine.values()].sort((a, b) => a.pos - b.pos).map((m) => new FixMarker(m.ids, m.title).range(m.pos)))
    }
    return next
  }
})

/** A gutter with a lightbulb where a quick fix applies; clicking it calls `onOpen` with the fixes on that line. */
export function fixGutter(onOpen: (ids: string[], event: MouseEvent) => void): Extension {
  return [
    field,
    gutter({
      class: 'cm-fix-gutter',
      markers: (view) => view.state.field(field),
      domEventHandlers: {
        mousedown(view, line, event) {
          let hit: FixMarker | null = null
          view.state.field(field).between(line.from, line.from, (_f, _t, m) => {
            hit = m
          })
          if (!hit) return false
          onOpen((hit as FixMarker).ids, event as MouseEvent)
          return true
        }
      }
    })
  ]
}

export function showFixes(view: EditorView, fixes: GutterFix[]): void {
  view.dispatch({ effects: setFixes.of(fixes) })
}
