import { sql } from '@codemirror/lang-sql'
import { syntaxHighlighting } from '@codemirror/language'
import { EditorState, type Extension } from '@codemirror/state'
import { EditorView, lineNumbers } from '@codemirror/view'
import { classHighlighter } from '@lezer/highlight'
import { useEffect, useRef } from 'react'
import type { Range, StatementPair } from '@shared/protocol'
import { codemirrorDialect } from './dialects'
import { createLayer, type Mark } from './layers'

function makeView(parent: HTMLElement, label: string, dialect: string, layer: Extension): EditorView {
  return new EditorView({
    parent,
    state: EditorState.create({
      doc: '',
      extensions: [
        lineNumbers(),
        EditorState.readOnly.of(true),
        EditorView.editable.of(false),
        syntaxHighlighting(classHighlighter),
        sql({ dialect: codemirrorDialect(dialect) }),
        EditorView.contentAttributes.of({ 'aria-label': label }),
        layer
      ]
    })
  })
}

function marksFor(side: 'before' | 'after', pair: StatementPair | null, focus: Range | null | undefined, length: number): Mark[] {
  const marks: Mark[] = []
  const clamp = (n: number): number => Math.max(0, Math.min(length, n))
  const range = side === 'before' ? pair?.beforeRange : pair?.afterRange
  if (range) marks.push({ from: clamp(range.start.offset), to: clamp(range.end.offset), lineClass: 'cm-diff-pair', className: 'cm-diff-in-pair' })
  if (pair && pair.status !== 'FORMAT_ONLY') {
    for (const h of pair.hunks ?? []) {
      const r = side === 'before' ? h.before : h.after
      const from = clamp(r.start.offset)
      const to = clamp(r.end.offset)
      if (to > from) marks.push({ from, to, className: side === 'before' ? 'cm-diff-del' : 'cm-diff-add', title: side === 'before' ? 'removed' : 'added' })
      else marks.push({ from, to: from, lineClass: 'cm-diff-gap', className: '' })
    }
  }
  if (focus) marks.push({ from: clamp(focus.start.offset), to: clamp(Math.max(focus.end.offset, focus.start.offset + 1)), className: 'cm-diff-focus' })
  return marks
}

/**
 * The old and new version side by side, read-only, with the selected pair's changed tokens
 * marked. Scrolling one side scrolls the other so the pair's lines stay aligned.
 */
export function DiffPanes({
  before,
  after,
  dialect,
  pair,
  focus
}: {
  before: string
  after: string
  dialect: string
  pair: StatementPair | null
  focus?: { before: Range | null; after: Range | null }
}): React.JSX.Element {
  const leftHost = useRef<HTMLDivElement>(null)
  const rightHost = useRef<HTMLDivElement>(null)
  const views = useRef<{ left: EditorView; right: EditorView } | null>(null)
  const layers = useRef({ left: createLayer(), right: createLayer() })
  const offsetRef = useRef(0)

  useEffect(() => {
    if (!leftHost.current || !rightHost.current) return
    const left = makeView(leftHost.current, 'Old version', dialect, layers.current.left.extension)
    const right = makeView(rightHost.current, 'New version', dialect, layers.current.right.extension)
    views.current = { left, right }
    // Keep the pair's lines level: the right side follows the left with the line offset of the pair.
    let syncing = false
    const follow = (from: EditorView, to: EditorView, sign: 1 | -1): void => {
      if (syncing) return
      syncing = true
      const top = from.scrollDOM.scrollTop
      const line = from.state.doc.lineAt(from.lineBlockAtHeight(top).from).number
      const target = Math.max(1, Math.min(to.state.doc.lines, line + sign * offsetRef.current))
      const block = to.lineBlockAt(to.state.doc.line(target).from)
      to.scrollDOM.scrollTop = block.top + (top - from.lineBlockAtHeight(top).top)
      requestAnimationFrame(() => {
        syncing = false
      })
    }
    const onLeft = (): void => follow(left, right, 1)
    const onRight = (): void => follow(right, left, -1)
    left.scrollDOM.addEventListener('scroll', onLeft)
    right.scrollDOM.addEventListener('scroll', onRight)
    return () => {
      left.scrollDOM.removeEventListener('scroll', onLeft)
      right.scrollDOM.removeEventListener('scroll', onRight)
      left.destroy()
      right.destroy()
      views.current = null
    }
    // The views are created once; content and dialect are set below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const v = views.current
    if (!v) return
    v.left.dispatch({ changes: { from: 0, to: v.left.state.doc.length, insert: before } })
    v.right.dispatch({ changes: { from: 0, to: v.right.state.doc.length, insert: after } })
  }, [before, after])

  useEffect(() => {
    const v = views.current
    if (!v) return
    layers.current.left.set(v.left, marksFor('before', pair, focus?.before, v.left.state.doc.length))
    layers.current.right.set(v.right, marksFor('after', pair, focus?.after, v.right.state.doc.length))
    const b = focus?.before ?? pair?.beforeRange ?? null
    const a = focus?.after ?? pair?.afterRange ?? null
    offsetRef.current = b && a ? a.start.line - b.start.line : 0
    const scroll = (view: EditorView, r: Range | null, other: Range | null): void => {
      // A side without the statement (added or removed) scrolls to where the other side has it.
      const line = r?.start.line ?? other?.start.line
      if (!line) return
      const pos = view.state.doc.line(Math.max(1, Math.min(view.state.doc.lines, line))).from
      view.dispatch({ effects: EditorView.scrollIntoView(pos, { y: 'start', yMargin: 40 }) })
    }
    scroll(v.left, b, a)
    scroll(v.right, a, b)
  }, [pair, focus, before, after])

  return (
    <div className="grid min-h-0 flex-1 grid-cols-2 divide-x divide-line">
      <div ref={leftHost} className="diff-pane selectable min-h-0 overflow-hidden" />
      <div ref={rightHost} className="diff-pane selectable min-h-0 overflow-hidden" />
    </div>
  )
}
