import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { sql } from '@codemirror/lang-sql'
import { bracketMatching, foldGutter, indentOnInput, syntaxHighlighting } from '@codemirror/language'
import { classHighlighter } from '@lezer/highlight'
import { highlightSelectionMatches, search, searchKeymap } from '@codemirror/search'
import { Compartment, EditorState, Prec } from '@codemirror/state'
import {
  EditorView,
  drawSelection,
  highlightActiveLine,
  highlightActiveLineGutter,
  keymap,
  lineNumbers
} from '@codemirror/view'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReferenceQuery } from '@shared/protocol'
import { referenceMenuItems } from '@/components/References'
import { statementAtOffset } from '@/lib/model'
import { isKeywordToken, occurrenceRanges, referenceTarget, tokenAt } from '@/lib/references'
import { rewritesAt } from '@/lib/rewrites'
import { useStore } from '@/store'
import { codemirrorDialect } from './dialects'
import { findingsGutter, showFindings } from './findingsGutter'
import { fixGutter, showFixes } from './fixGutter'
import { createLayer, type Mark } from './layers'

/** What the name at a document position refers to, or null (with a hint) when nothing can be looked up. */
function queryAt(view: EditorView, pos: number): ReferenceQuery | null {
  const st = useStore.getState()
  if (!st.analysis || !st.index || st.analysis.status === 'FAILED') return null
  if (st.doc?.edited) {
    st.toast('The script was edited since the last analysis. Analyze it again to look names up.')
    return null
  }
  const text = view.state.doc.toString()
  const token = tokenAt(text, pos)
  if (!token || isKeywordToken(token, text)) return null
  const id = statementAtOffset(st.index, pos)
  return referenceTarget(token, st.analysis, id !== null ? st.index.byId.get(id) : undefined)
}

/** Opens one quick fix directly, or a menu when several apply. */
function openFixes(ids: string[], x: number, y: number): void {
  const st = useStore.getState()
  const items = st.rewrites.items.filter((r) => ids.includes(r.id))
  if (items.length === 1) st.previewRewrite(items[0]!.id)
  else if (items.length > 1)
    st.openContextMenu(
      x,
      y,
      items.map((r) => ({ label: `Fix: ${r.title}`, hint: r.rule.replace(/^AMB-/, ''), onSelect: () => st.previewRewrite(r.id) }))
    )
}

function fresh(): boolean {
  const st = useStore.getState()
  return Boolean(st.doc && !st.doc.edited && st.rewrites.forText === st.doc.text)
}

/** Cmd/Ctrl+.: the quick fixes at the cursor. */
function quickFix(view: EditorView): boolean {
  const st = useStore.getState()
  if (!st.index || !fresh()) return true
  const pos = view.state.selection.main.head
  const ranges = new Map(st.index.order.map((s) => [s.id, s.range]))
  const found = rewritesAt(st.rewrites.items, pos, ranges)
  if (!found.length) {
    st.toast('No quick fix here. Suggested fixes for the whole script are in the Fixes tab below.')
    return true
  }
  const c = view.coordsAtPos(pos)
  openFixes(
    found.map((r) => r.id),
    c?.left ?? 200,
    (c?.bottom ?? 200) + 4
  )
  return true
}

function lookUp(view: EditorView, goToDefinition: boolean): boolean {
  const q = queryAt(view, view.state.selection.main.head)
  if (q) void useStore.getState().findReferences(q, { goToDefinition })
  return true
}

/**
 * The SQL editor, synchronized with the views: the selected statement's range is held in amber,
 * and moving the cursor selects the statement under it. Amber never runs what is typed here.
 */
export function SqlEditor(): React.JSX.Element {
  const host = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)
  const language = useRef(new Compartment())
  const amberLayer = useRef(createLayer())
  const refsLayer = useRef(createLayer())
  const [lineCount, setLineCount] = useState(1)
  const diagLayer = useRef(createLayer())
  const programmatic = useRef(false)

  const doc = useStore((s) => s.doc)
  const dialect = useStore((s) => s.dialect)
  const index = useStore((s) => s.index)
  const analysis = useStore((s) => s.analysis)
  const selection = useStore((s) => s.selection)
  const reveal = useStore((s) => s.reveal)
  const playbackActive = useStore((s) => s.playback.active)
  const editorMark = useStore((s) => s.editorMark)

  // Create the view once.
  useEffect(() => {
    if (!host.current) return
    let cursorTimer: ReturnType<typeof setTimeout> | undefined
    let editTimer: ReturnType<typeof setTimeout> | undefined
    const view = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: '',
        extensions: [
          findingsGutter((i) => useStore.getState().selectFinding(i)),
          fixGutter((ids, e) => openFixes(ids, e.clientX, e.clientY)),
          lineNumbers(),
          foldGutter(),
          highlightActiveLineGutter(),
          history(),
          drawSelection(),
          indentOnInput(),
          bracketMatching(),
          highlightActiveLine(),
          highlightSelectionMatches(),
          search({ top: true }),
          syntaxHighlighting(classHighlighter),
          Prec.high(
            keymap.of([
              { key: 'F12', run: (v) => lookUp(v, true) },
              { key: 'Shift-F12', run: (v) => lookUp(v, false) },
              { key: 'Mod-.', run: quickFix },
              { key: 'Shift-Alt-f', run: () => (void useStore.getState().formatScript('script'), true) },
              { key: 'Mod-k Mod-f', run: () => (void useStore.getState().formatScript('selection'), true) }
            ])
          ),
          keymap.of([...defaultKeymap, ...historyKeymap, ...searchKeymap, indentWithTab]),
          EditorView.domEventHandlers({
            mousedown(e, v) {
              if (e.button !== 0 || !(e.metaKey || e.ctrlKey)) return false
              const pos = v.posAtCoords({ x: e.clientX, y: e.clientY })
              const q = pos === null ? null : queryAt(v, pos)
              if (!q) return false
              e.preventDefault()
              void useStore.getState().findReferences(q, { goToDefinition: true })
              return true
            },
            contextmenu(e, v) {
              const st = useStore.getState()
              if (!st.doc) return false
              const pos = v.posAtCoords({ x: e.clientX, y: e.clientY })
              const token = pos === null ? null : tokenAt(v.state.doc.toString(), pos)
              const q = token && pos !== null ? queryAt(v, pos) : null
              const sel = v.state.selection.main
              const mac = navigator.userAgent.includes('Mac')
              e.preventDefault()
              st.openContextMenu(e.clientX, e.clientY, [
                ...(q ? referenceMenuItems(q) : []),
                ...(sel.empty ? [] : [{ label: 'Format selection', hint: mac ? '⌘K ⌘F' : 'Ctrl+K Ctrl+F', onSelect: () => void st.formatScript('selection') }]),
                { label: 'Format script', hint: mac ? '⇧⌥F' : 'Shift+Alt+F', onSelect: () => void st.formatScript('script') }
              ])
              return true
            }
          }),
          language.current.of(sql({ dialect: codemirrorDialect('oracle') })),
          refsLayer.current.extension,
          amberLayer.current.extension,
          diagLayer.current.extension,
          EditorView.contentAttributes.of({ 'aria-label': 'SQL script', spellcheck: 'false' }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) setLineCount(update.state.doc.lines)
            if (update.docChanged && !programmatic.current) {
              clearTimeout(editTimer)
              editTimer = setTimeout(() => useStore.getState().setDocText(update.state.doc.toString()), 300)
            }
            if (update.selectionSet) useStore.getState().setEditorSelection(update.state.selection.main.from, update.state.selection.main.to)
            const userMoved = update.transactions.some((t) => t.isUserEvent('select'))
            if (update.selectionSet && userMoved && !programmatic.current) {
              clearTimeout(cursorTimer)
              const pos = update.state.selection.main.head
              cursorTimer = setTimeout(() => {
                const st = useStore.getState()
                if (!st.index || st.playback.playing || st.doc?.edited) return
                const id = statementAtOffset(st.index, pos)
                if (id !== null && !(st.selection?.type === 'statement' && st.selection.id === id)) {
                  st.selectStatement(id, 'editor')
                }
              }, 120)
            }
          })
        ]
      })
    })
    viewRef.current = view
    return () => {
      clearTimeout(cursorTimer)
      clearTimeout(editTimer)
      view.destroy()
      viewRef.current = null
    }
  }, [])

  // Replace the content when a new document loads.
  const token = doc?.token
  useEffect(() => {
    const view = viewRef.current
    if (!view) return
    const text = useStore.getState().doc?.text ?? ''
    programmatic.current = true
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text }, selection: { anchor: 0 } })
    setLineCount(view.state.doc.lines)
    view.scrollDOM.scrollTop = 0
    programmatic.current = false
  }, [token])

  useEffect(() => {
    viewRef.current?.dispatch({ effects: language.current.reconfigure(sql({ dialect: codemirrorDialect(dialect) })) })
  }, [dialect])

  // Diagnostics
  useEffect(() => {
    const view = viewRef.current
    if (!view) return
    const marks: Mark[] = (analysis?.diagnostics ?? []).map((d) => ({
      from: d.range.start.offset,
      to: Math.max(d.range.end.offset, d.range.start.offset + 1),
      className: d.severity === 'ERROR' ? 'cm-diag-error' : 'cm-diag-warning',
      title: d.message
    }))
    diagLayer.current.set(view, marks)
    showFindings(
      view,
      (analysis?.findings ?? []).map((f, index) => ({ offset: f.range.start.offset, severity: f.severity, index, title: `${f.title} (${f.rule})` }))
    )
  }, [analysis])

  // Amber: the selected statement's range, or every place the selected object is touched.
  useEffect(() => {
    const view = viewRef.current
    if (!view) return
    const marks: Mark[] = []
    if (index && selection?.type === 'statement') {
      const s = index.byId.get(selection.id)
      if (s) {
        marks.push({
          from: s.range.start.offset,
          to: s.range.end.offset,
          className: 'cm-amber-range',
          lineClass: playbackActive ? 'cm-play-line' : 'cm-amber-line'
        })
      }
    } else if (index && selection?.type === 'object') {
      for (const e of index.lifecycleByKey.get(selection.key)?.events ?? []) {
        marks.push({ from: e.range.start.offset, to: e.range.end.offset, className: 'cm-amber-ref', title: e.access.toLowerCase() })
      }
    }
    if (editorMark) marks.push({ from: editorMark.from, to: editorMark.to, className: 'cm-amber-ref' })
    amberLayer.current.set(view, marks)
  }, [index, selection, playbackActive, editorMark])

  // References: every occurrence while the References panel is open, plus ticks on the overview ruler.
  const refs = useStore((s) => s.references.result)
  const refsOpen = useStore((s) => s.bottomPanel === 'references')
  const ranges = useMemo(() => (refsOpen ? occurrenceRanges(refs) : []), [refs, refsOpen])
  useEffect(() => {
    const view = viewRef.current
    if (!view) return
    const length = view.state.doc.length
    refsLayer.current.set(
      view,
      ranges.filter((r) => r.to <= length).map((r) => ({ from: r.from, to: Math.max(r.to, r.from + 1), className: r.definition ? 'cm-ref-def' : 'cm-ref', title: r.definition ? 'definition' : undefined }))
    )
  }, [ranges])
  const ticks = useMemo(() => {
    const view = viewRef.current
    if (!view || !ranges.length) return []
    const length = view.state.doc.length
    return ranges.filter((r) => r.from <= length).map((r) => ({ top: ((view.state.doc.lineAt(r.from).number - 0.5) / Math.max(1, lineCount)) * 100, definition: r.definition }))
  }, [ranges, lineCount])

  // Quick fixes: lightbulbs where they apply, while they match the text.
  const rewrites = useStore((s) => s.rewrites)
  const docEdited = useStore((s) => s.doc?.edited)
  useEffect(() => {
    const view = viewRef.current
    if (!view) return
    const ok = rewrites.status === 'ready' && !docEdited && rewrites.forText === useStore.getState().doc?.text
    showFixes(view, ok ? rewrites.items.filter((r) => r.edits.length).map((r) => ({ offset: r.edits[0]!.range.start.offset, ids: [r.id], title: r.title })) : [])
  }, [rewrites, docEdited])

  // Apply quick-fix edits as one undoable step, then hand the new text back for analysis.
  const editorEdits = useStore((s) => s.editorEdits)
  useEffect(() => {
    const view = viewRef.current
    if (!view || !editorEdits) return
    if (view.state.doc.toString() !== editorEdits.baseText) {
      useStore.setState({ editorEdits: null })
      useStore.getState().toast('The editor text changed in the meantime. Try again.', 'error')
      return
    }
    view.dispatch({
      changes: editorEdits.changes,
      userEvent: 'input.amber',
      effects: EditorView.scrollIntoView(editorEdits.changes[0]?.from ?? 0, { y: 'center' })
    })
    useStore.getState().editsApplied(view.state.doc.toString())
  }, [editorEdits])

  // Scroll to a requested range.
  useEffect(() => {
    const view = viewRef.current
    if (!view || !reveal) return
    const length = view.state.doc.length
    const from = Math.min(reveal.from, length)
    programmatic.current = true
    view.dispatch({
      selection: { anchor: from },
      effects: EditorView.scrollIntoView(from, { y: 'start', yMargin: 48 })
    })
    programmatic.current = false
  }, [reveal])

  return (
    <div className="relative h-full min-h-0">
      <div ref={host} className="selectable h-full min-h-0 overflow-hidden" data-testid="sql-editor" />
      {ticks.length ? (
        <div className="ref-ruler" aria-hidden>
          {ticks.map((t, i) => (
            <span key={i} className={t.definition ? 'def' : undefined} style={{ top: `calc(${t.top}% - 1px)` }} />
          ))}
        </div>
      ) : null}
    </div>
  )
}
