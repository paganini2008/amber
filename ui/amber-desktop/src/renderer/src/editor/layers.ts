import { StateEffect, StateField, RangeSetBuilder, type EditorState, type Extension } from '@codemirror/state'
import { Decoration, EditorView, type DecorationSet } from '@codemirror/view'

export interface Mark {
  from: number
  to: number
  className: string
  /** Also decorate every line the range covers. */
  lineClass?: string
  title?: string
}

/** A decoration layer whose content is replaced by an effect and mapped through edits. */
export function createLayer(): { extension: Extension; set: (view: EditorView, marks: Mark[]) => void } {
  const effect = StateEffect.define<Mark[]>()
  const field = StateField.define<DecorationSet>({
    create: () => Decoration.none,
    update(value, tr) {
      let next = value.map(tr.changes)
      for (const e of tr.effects) if (e.is(effect)) next = build(tr.state, e.value)
      return next
    },
    provide: (f) => EditorView.decorations.from(f)
  })
  return {
    extension: field,
    set(view, marks) {
      view.dispatch({ effects: effect.of(marks) })
    }
  }
}

function build(state: EditorState, marks: Mark[]): DecorationSet {
  const length = state.doc.length
  const items: { from: number; to: number; deco: Decoration }[] = []
  for (const m of marks) {
    const from = Math.max(0, Math.min(length, m.from))
    const to = Math.max(from, Math.min(length, m.to))
    if (m.lineClass) {
      const first = state.doc.lineAt(from).number
      const last = state.doc.lineAt(to).number
      for (let n = first; n <= last; n++) {
        const line = state.doc.line(n)
        items.push({ from: line.from, to: line.from, deco: Decoration.line({ class: m.lineClass }) })
      }
    }
    if (to > from) {
      items.push({
        from,
        to,
        deco: Decoration.mark({ class: m.className, attributes: m.title ? { title: m.title } : undefined })
      })
    }
  }
  // RangeSetBuilder needs sorted input; line decorations sort before marks at the same position.
  items.sort((a, b) => a.from - b.from || startSide(a.deco) - startSide(b.deco) || a.to - b.to)
  const builder = new RangeSetBuilder<Decoration>()
  for (const i of items) builder.add(i.from, i.to, i.deco)
  return builder.finish()
}

function startSide(deco: Decoration): number {
  return (deco as unknown as { startSide: number }).startSide ?? 0
}
