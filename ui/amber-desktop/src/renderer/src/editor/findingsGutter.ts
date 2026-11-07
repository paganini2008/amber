import { RangeSet, StateEffect, StateField, type Extension } from '@codemirror/state'
import { GutterMarker, gutter, type EditorView } from '@codemirror/view'
import { severityRank } from '@/lib/findings'

export interface GutterFinding {
  offset: number
  severity: string
  index: number
  title: string
}

const SHAPES: Record<string, string> = {
  HIGH: '<polygon points="3.5,0.5 8.5,0.5 11.5,3.5 11.5,8.5 8.5,11.5 3.5,11.5 0.5,8.5 0.5,3.5" fill="var(--risk-high)"/>',
  MEDIUM: '<polygon points="6,0.8 11.5,11 0.5,11" fill="var(--risk-medium)"/>',
  LOW: '<circle cx="6" cy="6" r="5" fill="var(--risk-low)"/>',
  INFO: '<circle cx="6" cy="6" r="4.2" fill="none" stroke="var(--ink-muted)" stroke-width="1.5"/>'
}

class FindingMarker extends GutterMarker {
  constructor(
    readonly severity: string,
    readonly index: number,
    readonly title: string,
    readonly count: number
  ) {
    super()
  }

  override eq(other: GutterMarker): boolean {
    return other instanceof FindingMarker && other.severity === this.severity && other.index === this.index && other.count === this.count
  }

  override toDOM(): Node {
    const el = document.createElement('span')
    el.className = 'cm-finding-marker'
    el.title = this.count > 1 ? `${this.title} (+${this.count - 1} more)` : this.title
    el.setAttribute('aria-label', `${this.severity.toLowerCase()} finding: ${this.title}`)
    el.innerHTML = `<svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">${SHAPES[this.severity] ?? SHAPES.INFO}</svg>`
    return el
  }
}

const setFindings = StateEffect.define<GutterFinding[]>()

const field = StateField.define<RangeSet<FindingMarker>>({
  create: () => RangeSet.empty,
  update(value, tr) {
    let next = value.map(tr.changes)
    for (const e of tr.effects) {
      if (!e.is(setFindings)) continue
      // One marker per line: the most severe finding on it.
      const byLine = new Map<number, { f: GutterFinding; count: number; pos: number }>()
      for (const f of e.value) {
        if (f.offset > tr.state.doc.length) continue
        const line = tr.state.doc.lineAt(f.offset)
        const cur = byLine.get(line.number)
        if (!cur) byLine.set(line.number, { f, count: 1, pos: line.from })
        else {
          cur.count += 1
          if (severityRank(f.severity) < severityRank(cur.f.severity)) cur.f = f
        }
      }
      const ranges = [...byLine.values()]
        .sort((a, b) => a.pos - b.pos)
        .map(({ f, count, pos }) => new FindingMarker(f.severity, f.index, f.title, count).range(pos))
      next = RangeSet.of(ranges)
    }
    return next
  }
})

/** A gutter showing findings; clicking a marker calls `onSelect` with the finding's index. */
export function findingsGutter(onSelect: (index: number) => void): Extension {
  return [
    field,
    gutter({
      class: 'cm-findings-gutter',
      markers: (view) => view.state.field(field),
      domEventHandlers: {
        mousedown(view, line) {
          let hit: FindingMarker | null = null
          view.state.field(field).between(line.from, line.from, (_from, _to, marker) => {
            hit = marker
          })
          if (!hit) return false
          onSelect((hit as FindingMarker).index)
          return true
        }
      }
    })
  ]
}

export function showFindings(view: EditorView, findings: GutterFinding[]): void {
  view.dispatch({ effects: setFindings.of(findings) })
}
