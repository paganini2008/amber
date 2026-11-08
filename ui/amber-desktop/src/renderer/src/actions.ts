import type { Statement } from '@shared/protocol'
import type { DocGenerateResult } from '@shared/protocol'
import { t } from './i18n'
import { api, describeError, isNotAvailable, newRequestId } from './lib/api'
import { SAMPLES } from './samples'
import { diffMarkdown } from './lib/diff'
import { buildFlowView, traceObject, traceStatement, type Trace } from './lib/dataflow'
import { erToSvg, flowToSvg, lineageToSvg, matrixToSvg, structureToSvg, svgToPngBase64 } from './lib/exportSvg'
import { layoutFlow } from './lib/layout'
import { buildMatrix } from './lib/lifecycle'
import { lineageToMermaid } from './lib/lineage'
import { phaseBadges } from './lib/logicalOrder'
import { toMermaid } from './lib/mermaid'
import { buildStructureTree, layoutStructure, statementTitle, structureToMermaid, structurePalette } from './lib/structure'
import { analyzeParams, currentMetadata, useStore, type CompareSide } from './store'
import { activeTokens } from './theme'
import { flowSnapshot } from './views/DataFlowView'
import { lifecycleOptions } from './views/LifecycleView'
import { erSnapshot } from './views/ErView'
import { lineageSnapshot } from './views/LineageView'
import { structureSnapshot } from './views/StructureView'

export async function openFileDialog(): Promise<void> {
  try {
    const file = await api().files.openDialog()
    if (file) useStore.getState().loadDocument(file)
  } catch (error) {
    useStore.getState().toast(t('app.toast.openFailed', { reason: (error as Error).message }), 'error')
  }
}

export async function openPath(path: string): Promise<void> {
  const result = await api().files.open(path)
  if (result.ok) useStore.getState().loadDocument(result.value)
  else useStore.getState().toast(t('app.toast.openPathFailed', { path, reason: result.error.message }), 'error')
}

/** Opens the bundled sample for a database and switches to its dialect. */
export function openSample(dialect: string): void {
  const sample = SAMPLES.find((s) => s.dialect === dialect)
  if (!sample) return
  const st = useStore.getState()
  // Set the dialect without re-analyzing the current document; loading the sample analyzes it.
  useStore.setState({ dialect: sample.dialect })
  st.loadDocument({ path: null, name: sample.name, text: sample.text, encoding: 'UTF-8' })
}

function baseName(): string {
  const name = useStore.getState().analysis?.name ?? useStore.getState().doc?.name ?? 'script.sql'
  return name.replace(/\.[^.]+$/, '') || 'script'
}

function currentTrace(): Trace | null {
  const { selection, graph } = useStore.getState()
  if (!selection || !graph) return null
  if (selection.type === 'object') return traceObject(graph, selection.key)
  return graph.readsOf.has(selection.id) ? traceStatement(graph, selection.id) : null
}

async function save(defaultName: string, filters: { name: string; extensions: string[] }[], data: string, encoding: 'utf8' | 'base64' = 'utf8'): Promise<void> {
  const path = await api().files.save({ defaultName, filters, data, encoding })
  if (path) useStore.getState().toast(t('app.toast.saved', { file: path.split(/[\\/]/).pop() ?? path }))
}

/** SVG of the current graph or matrix, generated from the view model. */
async function currentSvg(): Promise<{ svg: string; suffix: string } | null> {
  const { index, graph, view, analysis } = useStore.getState()
  if (!index || !graph || !analysis) return null
  const tokens = activeTokens()
  const trace = currentTrace()
  if (view === 'structure') {
    const statement = structureStatement()
    if (!statement) return null
    const tree = buildStructureTree(statement.structure)
    const layout = layoutStructure(statement, tree, structureSnapshot.current?.collapsed, undefined, phaseBadges(statement.executionOrder))
    return {
      svg: structureToSvg(layout, tokens, `${analysis.name} · structure of ${statementTitle(statement)} (line ${statement.range.start.line})`),
      suffix: `structure-${statement.id + 1}`
    }
  }
  if (view === 'lineage') {
    const snap = lineageSnapshot.current
    const lineage = useStore.getState().lineage
    if (!snap || !lineage) {
      useStore.getState().toast(t('app.toast.pickColumn'), 'error')
      return null
    }
    return { svg: lineageToSvg(snap.model, snap.layout, lineage, tokens, `${analysis.name} · lineage of ${snap.title}`), suffix: 'lineage' }
  }
  if (view === 'er') {
    const snap = erSnapshot.current
    if (!snap || snap.cards.length === 0) {
      useStore.getState().toast(t('er.empty.filtered'), 'error')
      return null
    }
    return { svg: erToSvg(snap.cards, snap.relationships, snap.layout, tokens, `${analysis.name} · ${t('er.title')}`), suffix: 'er' }
  }
  if (view === 'lifecycle') {
    const matrix = buildMatrix(index, lifecycleOptions.current)
    return { svg: matrixToSvg(matrix, index, tokens, trace, `${analysis.name} · object lifecycle`), suffix: 'lifecycle' }
  }
  let snapshot = flowSnapshot.current
  if (!snapshot || snapshot.view.nodes.length === 0 || useStore.getState().graph !== graph) {
    const flowView = buildFlowView(index, graph)
    snapshot = { view: flowView, layout: await layoutFlow(flowView) }
  }
  return { svg: flowToSvg(snapshot.view, snapshot.layout, tokens, trace, `${analysis.name} · data flow`), suffix: 'data-flow' }
}

/** The statement shown in the Structure view, or null (with a toast) when there is none. */
function structureStatement(): Statement | null {
  const { index } = useStore.getState()
  const snap = structureSnapshot.current
  const statement = snap ? index?.byId.get(snap.statementId) : undefined
  if (!statement) {
    useStore.getState().toast(t('app.toast.pickStructure'), 'error')
    return null
  }
  return statement
}

function requireAnalysis(): boolean {
  if (useStore.getState().analysis?.status === 'FAILED' || !useStore.getState().analysis) {
    useStore.getState().toast(t('app.toast.analyzeFirst'), 'error')
    return false
  }
  return true
}

export async function exportSvg(): Promise<void> {
  if (!requireAnalysis()) return
  const out = await currentSvg()
  if (out) await save(`${baseName()}-${out.suffix}.svg`, [{ name: t('app.files.svg'), extensions: ['svg'] }], out.svg)
}

export async function exportPng(scale: 1 | 2 = 2): Promise<void> {
  if (!requireAnalysis()) return
  const out = await currentSvg()
  if (!out) return
  try {
    const png = await svgToPngBase64(out.svg, scale)
    await save(`${baseName()}-${out.suffix}${scale === 2 ? '@2x' : ''}.png`, [{ name: t('app.files.png'), extensions: ['png'] }], png, 'base64')
  } catch (error) {
    useStore.getState().toast(t('app.toast.pngFailed', { reason: (error as Error).message }), 'error')
  }
}

export async function exportMermaid(): Promise<void> {
  if (!requireAnalysis()) return
  const { index, graph, view, lineage } = useStore.getState()
  if (!index || !graph) return
  if (view === 'structure') {
    const statement = structureStatement()
    if (!statement) return
    const mmd = structureToMermaid(statement, buildStructureTree(statement.structure), structurePalette(activeTokens()), phaseBadges(statement.executionOrder))
    await save(`${baseName()}-structure-${statement.id + 1}.mmd`, [{ name: 'Mermaid', extensions: ['mmd', 'md'] }], mmd)
    return
  }
  if (view === 'er') {
    const model = useStore.getState().er.result
    if (model) await save(`${baseName()}-er.mmd`, [{ name: 'Mermaid', extensions: ['mmd', 'md'] }], model.mermaid)
    return
  }
  // Column-level Mermaid is unreadable beyond a handful of columns, so lineage exports objects only.
  if (view === 'lineage' && lineage && lineageSnapshot.current) {
    await save(`${baseName()}-lineage.mmd`, [{ name: 'Mermaid', extensions: ['mmd', 'md'] }], lineageToMermaid(lineage, lineageSnapshot.current.trace))
    return
  }
  await save(`${baseName()}-data-flow.mmd`, [{ name: 'Mermaid', extensions: ['mmd', 'md'] }], toMermaid(index, graph))
}

/** The handover document of the current script, generated by the engine (`doc.generate`). */
export async function generateHandover(format: 'markdown' | 'html'): Promise<{ ok: true; text: string } | { ok: false; message: string }> {
  const { doc, dialect, settings, usedFallback } = useStore.getState()
  if (!doc || !requireAnalysis()) return { ok: false, message: t('app.toast.analyzeFirst') }
  const params = { ...analyzeParams(doc, dialect, settings, usedFallback, currentMetadata(useStore.getState())), format }
  const r = await api().engine.call<DocGenerateResult>('doc.generate', params, newRequestId('doc'))
  if (!r.ok) return { ok: false, message: isNotAvailable(r.error) ? t('app.toast.noHandover') : describeError(r.error) }
  return { ok: true, text: r.value.text }
}

export async function exportHandover(format: 'markdown' | 'html'): Promise<void> {
  const r = await generateHandover(format)
  if (!r.ok) {
    useStore.getState().toast(t('app.toast.handoverFailed', { reason: r.message }), 'error')
    return
  }
  const md = format === 'markdown'
  await save(`${baseName()}-handover.${md ? 'md' : 'html'}`, [md ? { name: 'Markdown', extensions: ['md'] } : { name: t('app.files.html'), extensions: ['html'] }], r.text)
}

export async function exportJson(): Promise<void> {
  const analysis = useStore.getState().analysis
  if (!analysis) {
    useStore.getState().toast(t('app.toast.analyzeFirst'), 'error')
    return
  }
  await save(`${baseName()}-analysis.json`, [{ name: 'JSON', extensions: ['json'] }], JSON.stringify(analysis, null, 2))
}

/** The open script as one side of a comparison: the editor buffer, edits included. */
function currentSide(): CompareSide | null {
  const doc = useStore.getState().doc
  return doc ? { name: doc.name, text: doc.text, path: doc.path } : null
}

/** Compares the edited buffer with the file on disk: "what did my edits change?". */
export async function compareWithSaved(): Promise<void> {
  const st = useStore.getState()
  const current = currentSide()
  if (!current || !st.doc) return
  if (!st.doc.path) {
    st.toast(t('app.toast.noFileOnDisk'), 'error')
    return
  }
  const saved = await api().files.open(st.doc.path)
  if (!saved.ok) {
    st.toast(t('app.toast.savedReadFailed', { reason: saved.error.message }), 'error')
    return
  }
  if (saved.value.text === current.text) {
    st.toast(t('app.toast.noChanges'))
    return
  }
  await st.compare({ name: t('app.compare.saved', { name: saved.value.name }), text: saved.value.text, path: st.doc.path }, { name: t('app.compare.edited', { name: current.name }), text: current.text, path: null })
}

/** Picks another file; `older` makes it the old version and the open script the new one. */
export async function compareWithFile(role: 'older' | 'newer'): Promise<void> {
  const current = currentSide()
  if (!current) return
  const other = await api().files.openDialog()
  if (!other) return
  const side: CompareSide = { name: other.name, text: other.text, path: other.path }
  await useStore.getState().compare(role === 'older' ? side : current, role === 'older' ? current : side)
}

/** A file dropped on the "compare" target becomes the new version of the open script. */
export async function compareWithDropped(file: { path: string | null; name: string; text?: string }): Promise<void> {
  const current = currentSide()
  if (!current) return
  let text = file.text
  if (text === undefined && file.path) {
    const r = await api().files.open(file.path)
    if (!r.ok) {
      useStore.getState().toast(t('app.toast.readFailed', { name: file.name, reason: r.error.message }), 'error')
      return
    }
    text = r.value.text
  }
  if (text === undefined) return
  await useStore.getState().compare(current, { name: file.name, text, path: file.path })
}

export async function copyChangeSummary(): Promise<void> {
  const result = useStore.getState().diff.result
  if (!result) return
  try {
    await navigator.clipboard.writeText(diffMarkdown(result))
    useStore.getState().toast(t('app.toast.summaryCopied'))
  } catch {
    useStore.getState().toast(t('dialogs.about.copyFailed'), 'error')
  }
}
