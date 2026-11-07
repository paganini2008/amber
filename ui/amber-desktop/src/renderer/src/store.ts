import { create } from 'zustand'
import type { EngineStatus, OpenedFile, Settings } from '@shared/api'
import type { AnalysisResult, AnalyzeParams, Dialect, DiffParams, DiffResult, FormatResult, Range, ReferenceQuery, ReferencesResult, Rewrite, RpcErrorObject, TableMeta } from '@shared/protocol'
import { RPC_ERROR } from '@shared/protocol'
import { api, isNotAvailable, newRequestId } from './lib/api'
import { buildFlowGraph, type FlowGraph } from './lib/dataflow'
import { indexLineage, type LineageDirection, type LineageIndex } from './lib/lineage'
import { addPaths, metadataInputs, metadataKey, OFFLINE, type MetadataFiles, type OnlineState } from './lib/metadata'
import { indexAnalysis, type AnalysisIndex } from './lib/model'
import { queryLabel } from './lib/references'
import { formatSummary, minimalChanges } from './lib/format'
import { toChanges, type TextChange } from './lib/rewrites'
import { buildPlan, BRANCH_KINDS, type BranchDecision, type PlanStep } from './lib/playback'

export type ViewId = 'outline' | 'lifecycle' | 'dataflow' | 'lineage' | 'statement' | 'structure' | 'playback' | 'diff'
export type Selection = { type: 'statement'; id: number } | { type: 'object'; key: string } | null
export type SelectionSource = 'editor' | 'view' | 'playback'
export type DialogName = 'settings' | 'about' | 'paste' | 'handover'

export interface ReferencesState {
  status: 'idle' | 'loading' | 'ready' | 'error'
  query: ReferenceQuery | null
  result: ReferencesResult | null
  error: string | null
  requestId: string | null
}

/** One version in a comparison. Ranges in the result index into `text`. */
export interface CompareSide {
  name: string
  text: string
  path: string | null
}

export interface DiffState {
  status: 'idle' | 'loading' | 'ready' | 'error'
  before: CompareSide | null
  after: CompareSide | null
  result: DiffResult | null
  error: string | null
  requestId: string | null
  /** The statement pair shown side by side (`pairKey`), and a range on each side to bring into view. */
  selected: { key: string; focus?: { before: Range | null; after: Range | null } } | null
  showUnchanged: boolean
}

export interface ContextMenuItem {
  label: string
  hint?: string
  disabled?: boolean
  onSelect: () => void
}

export interface DocState {
  /** Changes whenever a new document is loaded, so the editor knows to replace its content. */
  token: number
  path: string | null
  name: string
  text: string
  encoding: string
  /** Edited in the editor since the last analysis. */
  edited: boolean
  /** Edited in the editor since it was opened, so the file on disk no longer matches `text`. */
  modified: boolean
}

export interface PlaybackState {
  active: boolean
  playing: boolean
  cursor: number
  speed: number
  decisions: Map<number, BranchDecision>
  plan: PlanStep[]
  askAtBranches: boolean
  /** Container waiting for a decision while playing. */
  pending: number | null
}

export interface Toast {
  id: number
  message: string
  tone: 'info' | 'error'
  actions?: { label: string; run: () => void }[]
}

export interface RewritesState {
  status: 'idle' | 'loading' | 'ready' | 'error' | 'unavailable'
  items: Rewrite[]
  error: string | null
  /** The script text the rewrites were computed for; offsets only hold for it. */
  forText: string | null
  requestId: string | null
}

interface State {
  settings: Settings | null
  engine: EngineStatus
  dialects: Dialect[]
  doc: DocState | null
  dialect: string
  analysis: AnalysisResult | null
  index: AnalysisIndex | null
  graph: FlowGraph | null
  /** Null when the engine sends no lineage (older engines). */
  lineage: LineageIndex | null
  /** The column whose lineage is shown, a lineage node id. */
  lineageFocus: number | null
  lineageDirection: LineageDirection
  lineageIndirect: boolean
  /** Restricts the column picker to one owner (set from the lifecycle matrix or object details). */
  lineageOwnerFilter: string | null
  /** A range to mark in the editor in addition to the selection, e.g. a lineage edge. */
  editorMark: { from: number; to: number } | null
  /** Index into analysis.findings of the finding shown in the details panel. */
  selectedFinding: number | null
  analyzing: boolean
  analyzeError: RpcErrorObject | null
  usedFallback: boolean
  requestId: string | null
  selection: Selection
  selectionSource: SelectionSource
  reveal: { from: number; to: number; nonce: number } | null
  view: ViewId
  collapsed: Set<number>
  playback: PlaybackState
  dialogs: { settings: boolean; about: boolean; paste: boolean; handover: boolean; settingsTab: string }
  toasts: Toast[]
  rightPanel: boolean
  /** The open tab of the bottom panel, or null when closed. */
  bottomPanel: 'findings' | 'fixes' | 'syntax' | 'metadata' | 'references' | 'format' | null
  /** Find references / go to definition: the last query and its result. */
  references: ReferencesState
  /** Rule-based quick fixes for the current analysis. */
  rewrites: RewritesState
  /** The rewrite shown in the preview dialog. */
  rewritePreview: string | null
  /** Changes for the editor to apply as one undoable transaction (from quick fixes). */
  editorEdits: { nonce: number; changes: TextChange[]; label: string; baseText: string; report?: boolean } | null
  /** The editor's current selection (document offsets), for "Format selection". */
  editorSelection: { from: number; to: number }
  /** What the last formatting kept as written, for the Format report tab. */
  formatReport: { warnings: string[]; skipped: Range[] } | null
  formatting: boolean
  /** The text before the last quick fix, for "Check the change" when there is no file on disk. */
  beforeFixes: { name: string; text: string } | null
  /** Version comparison (change impact). */
  diff: DiffState
  /** A context menu at a screen position (editor, chips, rows). */
  contextMenu: { x: number; y: number; items: ContextMenuItem[] } | null
  /** Offline metadata for this session: DDL files and metadata packs. */
  metadataFiles: MetadataFiles
  /** Online mode: what was loaded from the selected connections. */
  online: OnlineState
  /** Structure view: whether the Logical order panel is open, and a pending "step inside" request. */
  logicalOrder: { open: boolean; request: { statementId: number; nonce: number } | null }

  setSettings(settings: Settings): void
  setEngine(status: EngineStatus): void
  loadDocument(file: OpenedFile): void
  setDocText(text: string): void
  setDialect(dialect: string): void
  analyze(options?: { allowFallback?: boolean }): Promise<void>
  cancelAnalysis(): void
  select(selection: Selection, source?: SelectionSource, reveal?: boolean): void
  selectStatement(id: number, source?: SelectionSource, reveal?: boolean): void
  revealRange(from: number, to: number): void
  setView(view: ViewId): void
  toggleCollapsed(id: number): void
  setCollapsedAll(collapsed: boolean): void
  openDialog(name: DialogName, tab?: string): void
  closeDialog(name: DialogName): void
  toast(message: string, tone?: Toast['tone'], actions?: Toast['actions']): void
  dismissToast(id: number): void
  toggleRightPanel(): void
  traceColumn(nodeId: number): void
  setLineageOptions(options: { direction?: LineageDirection; indirect?: boolean; ownerFilter?: string | null }): void
  showOwnerColumns(ownerKey: string): void
  markRange(range: { from: number; to: number } | null): void
  selectFinding(index: number | null): void
  setBottomPanel(panel: State['bottomPanel']): void
  addMetadataFiles(kind: keyof MetadataFiles, paths: string[]): void
  removeMetadataFile(kind: keyof MetadataFiles, path: string): void
  setOnline(enabled: boolean): Promise<void>
  setOnlineConnections(ids: string[]): Promise<void>
  /** Reads metadata again now, even if the script did not change. */
  refreshMetadata(): void
  cancelMetadata(): void
  /** Finds every use of an object or column; with goToDefinition, jumps to where it is defined. */
  findReferences(query: ReferenceQuery, options?: { goToDefinition?: boolean }): Promise<void>
  closeReferences(): void
  /** Formats the script or the statements the selection overlaps, in the editor only. */
  formatScript(scope: 'script' | 'selection'): Promise<void>
  setEditorSelection(from: number, to: number): void
  loadRewrites(): Promise<void>
  previewRewrite(id: string | null): void
  /** Applies rewrites to the editor buffer (one undoable step), then analyzes again. Never writes the file. */
  applyRewrites(ids: string[]): void
  /** Called by the editor once it applied `editorEdits`. */
  editsApplied(text: string): void
  /** Compares the script before the last quick fixes with the edited buffer. */
  checkAppliedChange(): Promise<void>
  /** Compares two versions and opens the Change impact view. */
  compare(before: CompareSide, after: CompareSide): Promise<void>
  swapCompare(): void
  closeCompare(): void
  selectDiffPair(key: string | null, focus?: { before: Range | null; after: Range | null }): void
  setShowUnchanged(show: boolean): void
  openContextMenu(x: number, y: number, items: ContextMenuItem[]): void
  closeContextMenu(): void
  setLogicalOrderOpen(open: boolean): void
  /** Opens the statement's logical order in the Structure view, at its first step. */
  stepInside(statementId: number): void
  clearStepInside(): void

  startPlayback(): void
  stopPlayback(): void
  setPlaying(playing: boolean): void
  stepPlayback(delta: number): void
  seekPlayback(cursor: number): void
  setSpeed(speed: number): void
  decide(containerId: number, decision: BranchDecision): void
  setAskAtBranches(ask: boolean): void
}

/**
 * The params `analyze` is called with. `ai.buildContext` and `ai.check` reuse them so the engine
 * sees the same script, dialect and metadata (statement ids then match the analysis).
 * Metadata params (`metadataPacks`, `ddlFiles`, `metadata`) belong here once the app loads metadata.
 */
export function analyzeParams(
  doc: DocState,
  dialect: string,
  settings: Settings | null,
  allowFallback: boolean,
  metadata: Pick<AnalyzeParams, 'metadataPacks' | 'ddlFiles' | 'metadata'> = {}
): AnalyzeParams {
  // A file that has not been edited is read by the engine itself, so it detects the encoding.
  const base: AnalyzeParams =
    doc.path && !doc.modified
      ? { dialect, path: doc.path, name: doc.name, allowFallback, ...(settings?.encoding && settings.encoding !== 'auto' ? { encoding: settings.encoding } : {}) }
      : { dialect, text: doc.text, name: doc.name, allowFallback }
  return { ...base, ...metadata }
}

/** The metadata `analyze` gets right now: offline files, plus online tables when online mode is on. */
export function currentMetadata(state: Pick<State, 'metadataFiles' | 'online' | 'settings'>): Pick<AnalyzeParams, 'metadataPacks' | 'ddlFiles' | 'metadata'> {
  const onlineTables: TableMeta[] | null = state.settings?.online.enabled ? state.online.tables : null
  return metadataInputs(state.metadataFiles, onlineTables)
}

/** The metadata part of {@link analyzeParams}, for methods such as `ai.check` that take only that. */
export function metadataParams(params: AnalyzeParams): Pick<AnalyzeParams, 'metadataPacks' | 'ddlFiles' | 'metadata'> {
  const out: Pick<AnalyzeParams, 'metadataPacks' | 'ddlFiles' | 'metadata'> = {}
  if (params.metadataPacks) out.metadataPacks = params.metadataPacks
  if (params.ddlFiles) out.ddlFiles = params.ddlFiles
  if (params.metadata) out.metadata = params.metadata
  return out
}

/** Waits this long after an analysis before reading metadata, so quick re-analyses coalesce. */
export const METADATA_DEBOUNCE_MS = 600
let metadataTimer: ReturnType<typeof setTimeout> | null = null

let docToken = 0
let toastId = 0

const emptyPlayback = (): PlaybackState => ({
  active: false,
  playing: false,
  cursor: 0,
  speed: 1,
  decisions: new Map(),
  plan: [],
  askAtBranches: true,
  pending: null
})

export const useStore = create<State>((set, get) => ({
  settings: null,
  engine: { state: 'starting' },
  dialects: [],
  doc: null,
  dialect: 'oracle',
  analysis: null,
  index: null,
  graph: null,
  lineage: null,
  lineageFocus: null,
  lineageDirection: 'upstream',
  lineageIndirect: false,
  lineageOwnerFilter: null,
  editorMark: null,
  selectedFinding: null,
  analyzing: false,
  analyzeError: null,
  usedFallback: false,
  requestId: null,
  selection: null,
  selectionSource: 'view',
  reveal: null,
  view: 'outline',
  collapsed: new Set(),
  playback: emptyPlayback(),
  dialogs: { settings: false, about: false, paste: false, handover: false, settingsTab: 'general' },
  toasts: [],
  rightPanel: true,
  bottomPanel: null,
  metadataFiles: { ddlFiles: [], metadataPacks: [] },
  online: OFFLINE,
  logicalOrder: { open: false, request: null },
  references: { status: 'idle', query: null, result: null, error: null, requestId: null },
  contextMenu: null,
  diff: { status: 'idle', before: null, after: null, result: null, error: null, requestId: null, selected: null, showUnchanged: false },
  rewrites: { status: 'idle', items: [], error: null, forText: null, requestId: null },
  rewritePreview: null,
  editorEdits: null,
  editorSelection: { from: 0, to: 0 },
  formatReport: null,
  formatting: false,
  beforeFixes: null,

  setSettings(settings) {
    const first = get().settings === null
    set({ settings })
    if (first) set({ dialect: settings.defaultDialect, online: settings.online.enabled ? { ...OFFLINE, status: 'idle' } : OFFLINE })
    else if (!settings.online.enabled && get().online.status !== 'off') {
      // Online mode was switched off elsewhere (another window, the settings file): drop its state.
      get().cancelMetadata()
      set({ online: OFFLINE })
    }
  },

  setEngine(status) {
    set({ engine: status })
    if (status.info?.dialects?.length) set({ dialects: status.info.dialects })
  },

  loadDocument(file) {
    get().cancelAnalysis()
    get().cancelMetadata()
    docToken += 1
    set({
      doc: { token: docToken, path: file.path, name: file.name, text: file.text, encoding: file.encoding, edited: false, modified: false },
      analysis: null,
      index: null,
      graph: null,
      lineage: null,
      lineageFocus: null,
      lineageOwnerFilter: null,
      editorMark: null,
      selectedFinding: null,
      analyzeError: null,
      selection: null,
      collapsed: new Set(),
      playback: emptyPlayback(),
      references: { status: 'idle', query: null, result: null, error: null, requestId: null },
      rewrites: { status: 'idle', items: [], error: null, forText: null, requestId: null },
      rewritePreview: null,
      beforeFixes: null,
      bottomPanel: get().bottomPanel === 'references' ? null : get().bottomPanel,
      // Online tables belong to the previous script; the new one loads its own after analysis.
      online: get().online.status === 'off' ? OFFLINE : { ...OFFLINE, status: 'idle' }
    })
    void get().analyze()
  },

  setDocText(text) {
    const doc = get().doc
    if (!doc || doc.text === text) return
    set({ doc: { ...doc, text, edited: true, modified: true } })
  },

  setDialect(dialect) {
    if (dialect === get().dialect) return
    set({ dialect })
    if (get().doc) void get().analyze()
  },

  async analyze(options) {
    const { doc, settings, dialect } = get()
    if (!doc) return
    get().cancelAnalysis()
    const requestId = newRequestId('analyze')
    const allowFallback = options?.allowFallback ?? settings?.allowFallback ?? false
    set({ analyzing: true, requestId, analyzeError: null })
    const params = analyzeParams(doc, dialect, settings, allowFallback, currentMetadata(get()))
    // The text this analysis describes; typing during the analysis keeps the document "edited".
    const analyzedText = doc.text
    const result = await api().engine.analyze(params, requestId)
    if (get().requestId !== requestId) return
    if (!result.ok) {
      set({ analyzing: false, requestId: null, analyzeError: result.error.code === RPC_ERROR.REQUEST_CANCELLED ? null : result.error })
      return
    }
    const analysis = result.value
    const index = indexAnalysis(analysis)
    const graph = buildFlowGraph(index)
    const lineage = analysis.lineage ? indexLineage(analysis.lineage) : null
    const focus = get().lineageFocus
    const previous = get().selection
    const keep =
      previous?.type === 'statement' ? index.byId.has(previous.id) : previous?.type === 'object' ? index.lifecycleByKey.has(previous.key) : false
    set({
      analysis,
      index,
      graph,
      lineage,
      lineageFocus: focus !== null && lineage?.nodes.has(focus) ? focus : null,
      editorMark: null,
      selectedFinding: null,
      analyzing: false,
      requestId: null,
      usedFallback: allowFallback,
      selection: keep ? previous : null,
      doc: get().doc ? { ...get().doc!, edited: get().doc!.text !== analyzedText } : null,
      playback: emptyPlayback()
    })
    if (analysis.status !== 'FAILED') scheduleMetadataLoad(false)
    void get().loadRewrites()
    // Offsets may have moved: refresh an open References panel.
    const rq = get().references.query
    if (rq && get().bottomPanel === 'references' && analysis.status !== 'FAILED') void get().findReferences(rq)
  },

  cancelAnalysis() {
    const id = get().requestId
    if (id) {
      void api().engine.cancel(id)
      set({ requestId: null, analyzing: false })
    }
  },

  select(selection, source = 'view', reveal = true) {
    set({ selection, selectionSource: source, editorMark: null, selectedFinding: null })
    if (!reveal || source === 'editor') return
    const index = get().index
    if (selection?.type === 'statement') {
      const s = index?.byId.get(selection.id)
      if (s) get().revealRange(s.range.start.offset, s.range.end.offset)
    } else if (selection?.type === 'object') {
      const e = index?.lifecycleByKey.get(selection.key)?.events[0]
      if (e) get().revealRange(e.range.start.offset, e.range.end.offset)
    }
  },

  selectStatement(id, source = 'view', reveal = true) {
    get().select({ type: 'statement', id }, source, reveal)
  },

  revealRange(from, to) {
    set({ reveal: { from, to, nonce: Date.now() + Math.random() } })
  },

  setView(view) {
    set({ view })
  },

  toggleCollapsed(id) {
    const next = new Set(get().collapsed)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    set({ collapsed: next })
  },

  setCollapsedAll(collapsed) {
    const index = get().index
    if (!index || !collapsed) {
      set({ collapsed: new Set() })
      return
    }
    set({ collapsed: new Set(index.order.filter((s) => s.children.length > 0).map((s) => s.id)) })
  },

  openDialog(name, tab) {
    set({ dialogs: { ...get().dialogs, [name]: true, settingsTab: tab ?? get().dialogs.settingsTab } })
  },

  closeDialog(name) {
    set({ dialogs: { ...get().dialogs, [name]: false } })
  },

  toast(message, tone = 'info', actions) {
    toastId += 1
    const id = toastId
    set({ toasts: [...get().toasts, { id, message, tone, actions }] })
    setTimeout(() => get().dismissToast(id), actions?.length ? 12000 : tone === 'error' ? 8000 : 4000)
  },

  dismissToast(id) {
    set({ toasts: get().toasts.filter((t) => t.id !== id) })
  },

  toggleRightPanel() {
    set({ rightPanel: !get().rightPanel })
  },

  traceColumn(nodeId) {
    set({ lineageFocus: nodeId, view: 'lineage', lineageOwnerFilter: null })
  },

  setLineageOptions({ direction, indirect, ownerFilter }) {
    set({
      lineageDirection: direction ?? get().lineageDirection,
      lineageIndirect: indirect ?? get().lineageIndirect,
      lineageOwnerFilter: ownerFilter === undefined ? get().lineageOwnerFilter : ownerFilter
    })
  },

  showOwnerColumns(ownerKey) {
    set({ lineageOwnerFilter: ownerKey, view: 'lineage' })
  },

  selectFinding(i) {
    const f = i === null ? undefined : get().analysis?.findings?.[i]
    if (!f) {
      set({ selectedFinding: null })
      return
    }
    if (get().index?.byId.has(f.statement)) get().selectStatement(f.statement, 'view', false)
    get().markRange({ from: f.range.start.offset, to: f.range.end.offset })
    set({ selectedFinding: i, rightPanel: true })
  },

  setBottomPanel(panel) {
    set({ bottomPanel: panel })
  },

  addMetadataFiles(kind, paths) {
    const files = get().metadataFiles
    const next = { ...files, [kind]: addPaths(files[kind], paths) }
    if (next[kind].length === files[kind].length) return
    set({ metadataFiles: next })
    if (get().doc) void get().analyze()
  },

  removeMetadataFile(kind, path) {
    const files = get().metadataFiles
    set({ metadataFiles: { ...files, [kind]: files[kind].filter((p) => p !== path) } })
    if (get().doc) void get().analyze()
  },

  async setOnline(enabled) {
    const settings = get().settings
    if (!settings || settings.online.enabled === enabled) return
    const next = await api().settings.update({ online: { ...settings.online, enabled } })
    get().setSettings(next)
    if (enabled) {
      set({ online: { ...OFFLINE, status: 'idle' } })
      scheduleMetadataLoad(true)
      return
    }
    get().cancelMetadata()
    const hadTables = Boolean(get().online.tables?.length)
    set({ online: OFFLINE })
    // Back to offline: analyze again without the online tables, exactly as before.
    if (hadTables && get().doc) void get().analyze()
  },

  async setOnlineConnections(ids) {
    const settings = get().settings
    if (!settings) return
    const next = await api().settings.update({ online: { ...settings.online, connectionIds: ids } })
    get().setSettings(next)
    if (next.online.enabled) scheduleMetadataLoad(false)
  },

  refreshMetadata() {
    set({ online: { ...get().online, key: null } })
    scheduleMetadataLoad(true)
  },

  cancelMetadata() {
    if (metadataTimer) clearTimeout(metadataTimer)
    metadataTimer = null
    const { online } = get()
    if (online.requestId) {
      void api().engine.cancel(online.requestId)
      set({ online: { ...online, status: online.tables ? 'ready' : online.status === 'off' ? 'off' : 'idle', requestId: null, key: online.tables ? online.key : null } })
    }
  },

  async findReferences(query, options) {
    const { doc, dialect, settings, usedFallback } = get()
    if (!doc || !get().analysis) return
    const previous = get().references.requestId
    if (previous) void api().engine.cancel(previous)
    const requestId = newRequestId('references')
    set({ references: { status: 'loading', query, result: null, error: null, requestId } })
    if (!options?.goToDefinition) set({ bottomPanel: 'references' })
    const params = { ...analyzeParams(doc, dialect, settings, usedFallback, currentMetadata(get())), ...query }
    const r = await api().engine.call<ReferencesResult>('references', params, requestId)
    if (get().references.requestId !== requestId) return
    if (!r.ok) {
      if (r.error.code === RPC_ERROR.REQUEST_CANCELLED) return
      set({ references: { status: 'error', query, result: null, error: r.error.message || 'Could not find references.', requestId: null } })
      set({ bottomPanel: 'references' })
      return
    }
    set({ references: { status: 'ready', query, result: r.value, error: null, requestId: null } })
    if (!options?.goToDefinition) return
    const def = r.value.definition
    if (def) {
      if (get().index?.byId.has(def.statement)) get().selectStatement(def.statement, 'view', false)
      get().markRange({ from: def.range.start.offset, to: def.range.end.offset })
    } else {
      get().toast(r.value.occurrences.length ? `${r.value.name} is defined outside this script.` : `Nothing named ${queryLabel(query)} was found in this script.`)
      set({ bottomPanel: 'references' })
    }
  },

  closeReferences() {
    const id = get().references.requestId
    if (id) void api().engine.cancel(id)
    set({ references: { status: 'idle', query: null, result: null, error: null, requestId: null }, bottomPanel: get().bottomPanel === 'references' ? null : get().bottomPanel })
  },

  setEditorSelection(from, to) {
    const cur = get().editorSelection
    if (cur.from !== from || cur.to !== to) set({ editorSelection: { from, to } })
  },

  async formatScript(scope) {
    const { doc, dialect, settings, analysis } = get()
    if (!doc || get().formatting) return
    const sel = get().editorSelection
    const selection = scope === 'selection' ? { from: Math.min(sel.from, sel.to), to: Math.max(sel.from, sel.to) } : null
    const text = doc.text
    set({ formatting: true })
    const f = settings?.format
    const params = {
      dialect,
      text,
      ...(f ? { indentSize: f.indentSize, keywordCase: f.keywordCase, maxLineWidth: f.maxLineWidth, commaStyle: f.commaStyle } : {}),
      ...(selection ? { start: selection.from, end: Math.max(selection.to, selection.from + 1) } : {})
    }
    const r = await api().engine.call<FormatResult>('format', params, newRequestId('format'))
    set({ formatting: false })
    if (!r.ok) {
      get().toast(isNotAvailable(r.error) ? 'This engine version cannot format scripts yet.' : `Could not format: ${r.error.message}`, 'error')
      return
    }
    const report = r.value.warnings.length || r.value.skipped.length ? { warnings: r.value.warnings, skipped: r.value.skipped } : null
    set({ formatReport: report })
    const summary = formatSummary(analysis?.statements ?? [], r.value.skipped, selection, r.value.changed && r.value.text !== text)
    if (!r.value.changed || r.value.text === text) {
      get().toast(summary, 'info', report ? [{ label: 'Show', run: () => get().setBottomPanel('format') }] : undefined)
      return
    }
    if (get().doc?.text !== text) {
      get().toast('The script changed while it was being formatted. Format it again.', 'error')
      return
    }
    set({
      beforeFixes: get().beforeFixes ?? { name: doc.name, text },
      editorEdits: { nonce: Date.now(), changes: minimalChanges(text, r.value.text), label: `${summary}.`, baseText: text, report: Boolean(report) }
    })
  },

  async loadRewrites() {
    const { doc, analysis, dialect, settings, usedFallback } = get()
    const previous = get().rewrites.requestId
    if (previous) void api().engine.cancel(previous)
    if (!doc || !analysis || analysis.status === 'FAILED' || !(analysis.findings ?? []).length) {
      set({ rewrites: { status: 'idle', items: [], error: null, forText: null, requestId: null } })
      return
    }
    const requestId = newRequestId('rewrite')
    const text = doc.text
    set({ rewrites: { ...get().rewrites, status: 'loading', requestId } })
    const params = { ...analyzeParams(doc, dialect, settings, usedFallback, currentMetadata(get())), statements: [] as number[] }
    const r = await api().engine.call<{ rewrites: Rewrite[] }>('rewrite.suggest', params, requestId)
    if (get().rewrites.requestId !== requestId) return
    if (!r.ok) {
      if (r.error.code === RPC_ERROR.REQUEST_CANCELLED) return
      set({ rewrites: { status: isNotAvailable(r.error) ? 'unavailable' : 'error', items: [], error: r.error.message, forText: null, requestId: null } })
      return
    }
    set({ rewrites: { status: 'ready', items: r.value.rewrites ?? [], error: null, forText: text, requestId: null } })
  },

  previewRewrite(id) {
    set({ rewritePreview: id })
  },

  applyRewrites(ids) {
    const { rewrites, doc } = get()
    if (!doc || rewrites.forText !== doc.text || doc.edited) {
      get().toast('The script changed since these fixes were suggested. Analyze it again to refresh them.', 'error')
      return
    }
    const chosen = rewrites.items.filter((r) => ids.includes(r.id))
    if (!chosen.length) return
    let changes: TextChange[]
    try {
      changes = toChanges(chosen.flatMap((r) => r.edits))
    } catch {
      get().toast('These fixes overlap. Apply them one at a time.', 'error')
      return
    }
    const label = chosen.length === 1 ? `Applied “${chosen[0]!.title}”.` : `Applied ${chosen.length} fixes.`
    set({ beforeFixes: get().beforeFixes ?? { name: doc.name, text: doc.text }, rewritePreview: null, editorEdits: { nonce: Date.now(), changes, label, baseText: doc.text } })
  },

  editsApplied(text) {
    const pending = get().editorEdits
    set({ editorEdits: null })
    const doc = get().doc
    if (!doc) return
    set({ doc: { ...doc, text, edited: true, modified: true } })
    void get().analyze()
    const actions: NonNullable<Toast['actions']> = []
    if (pending?.report) actions.push({ label: 'Show', run: () => get().setBottomPanel('format') })
    actions.push({ label: 'Check the change', run: () => void get().checkAppliedChange() })
    get().toast(`${pending?.label ?? 'Applied.'} The file on disk is unchanged until you save.`, 'info', actions)
  },

  async checkAppliedChange() {
    const doc = get().doc
    if (!doc) return
    const before = get().beforeFixes
    if (doc.path) {
      const saved = await api().files.open(doc.path)
      if (saved.ok) {
        await get().compare({ name: `${saved.value.name} (saved)`, text: saved.value.text, path: doc.path }, { name: `${doc.name} (edited)`, text: doc.text, path: null })
        return
      }
    }
    if (before) await get().compare({ name: `${before.name} (before fixes)`, text: before.text, path: null }, { name: `${doc.name} (edited)`, text: doc.text, path: null })
  },

  async compare(before, after) {
    const previous = get().diff.requestId
    if (previous) void api().engine.cancel(previous)
    const requestId = newRequestId('diff')
    set({ diff: { ...get().diff, status: 'loading', before, after, result: null, error: null, requestId, selected: null }, view: 'diff' })
    const params: DiffParams = {
      dialect: get().dialect,
      before: { text: before.text, name: before.name },
      after: { text: after.text, name: after.name },
      ...metadataInputs(get().metadataFiles, get().settings?.online.enabled ? get().online.tables : null)
    }
    const r = await api().engine.call<DiffResult>('diff', params, requestId)
    if (get().diff.requestId !== requestId) return
    if (!r.ok) {
      if (r.error.code === RPC_ERROR.REQUEST_CANCELLED) return
      set({ diff: { ...get().diff, status: 'error', error: isNotAvailable(r.error) ? 'This engine version cannot compare scripts yet.' : r.error.message || 'The comparison did not finish.', requestId: null } })
      return
    }
    set({ diff: { ...get().diff, status: 'ready', result: r.value, requestId: null } })
  },

  swapCompare() {
    const { before, after } = get().diff
    if (before && after) void get().compare(after, before)
  },

  closeCompare() {
    const id = get().diff.requestId
    if (id) void api().engine.cancel(id)
    set({ diff: { status: 'idle', before: null, after: null, result: null, error: null, requestId: null, selected: null, showUnchanged: get().diff.showUnchanged }, view: get().view === 'diff' ? 'outline' : get().view })
  },

  selectDiffPair(key, focus) {
    set({ diff: { ...get().diff, selected: key ? { key, focus } : null } })
  },

  setShowUnchanged(show) {
    set({ diff: { ...get().diff, showUnchanged: show } })
  },

  openContextMenu(x, y, items) {
    set({ contextMenu: { x, y, items } })
  },

  closeContextMenu() {
    if (get().contextMenu) set({ contextMenu: null })
  },

  setLogicalOrderOpen(open) {
    set({ logicalOrder: { ...get().logicalOrder, open } })
  },

  stepInside(statementId) {
    if (get().playback.playing) get().setPlaying(false)
    get().select({ type: 'statement', id: statementId }, 'view', false)
    set({ view: 'structure', logicalOrder: { open: true, request: { statementId, nonce: Date.now() } } })
  },

  clearStepInside() {
    set({ logicalOrder: { ...get().logicalOrder, request: null } })
  },

  markRange(range) {
    set({ editorMark: range })
    if (range) get().revealRange(range.from, range.to)
  },

  startPlayback() {
    const analysis = get().analysis
    if (!analysis) return
    const pb = get().playback
    const plan = buildPlan(analysis.statements, pb.decisions)
    if (plan.length === 0) return
    set({ playback: { ...pb, active: true, playing: false, cursor: 0, plan, pending: null } })
    get().selectStatement(plan[0]!.id, 'playback')
  },

  stopPlayback() {
    set({ playback: { ...get().playback, active: false, playing: false, pending: null } })
  },

  setPlaying(playing) {
    const pb = get().playback
    if (!pb.active) {
      get().startPlayback()
    }
    const next = get().playback
    // Restart from the top when play is pressed at the end.
    const cursor = playing && next.cursor >= next.plan.length - 1 ? 0 : next.cursor
    set({ playback: { ...next, playing, cursor, pending: null } })
    if (playing && cursor !== next.cursor) get().selectStatement(next.plan[cursor]!.id, 'playback')
  },

  stepPlayback(delta) {
    const pb = get().playback
    if (!pb.active) {
      get().startPlayback()
      return
    }
    const current = pb.plan[pb.cursor]
    // When playing forward into an undecided IF/CASE, pause and ask.
    if (delta > 0 && pb.playing && pb.askAtBranches && current && current.branch && !pb.decisions.has(current.id)) {
      set({ playback: { ...pb, playing: false, pending: current.id } })
      return
    }
    const cursor = Math.max(0, Math.min(pb.plan.length - 1, pb.cursor + delta))
    const atEnd = delta > 0 && cursor === pb.cursor
    set({ playback: { ...pb, cursor, playing: atEnd ? false : pb.playing, pending: null } })
    const step = pb.plan[cursor]
    if (step) get().selectStatement(step.id, 'playback')
  },

  seekPlayback(cursor) {
    const pb = get().playback
    if (!pb.active) get().startPlayback()
    const next = get().playback
    const c = Math.max(0, Math.min(next.plan.length - 1, cursor))
    set({ playback: { ...next, cursor: c, pending: null } })
    const step = next.plan[c]
    if (step) get().selectStatement(step.id, 'playback')
  },

  setSpeed(speed) {
    set({ playback: { ...get().playback, speed } })
  },

  decide(containerId, decision) {
    const { playback: pb, analysis, index } = get()
    if (!analysis) return
    const decisions = new Map(pb.decisions)
    decisions.set(containerId, decision)
    const currentId = pb.plan[pb.cursor]?.id
    const plan = buildPlan(analysis.statements, decisions)
    const cursor = Math.max(0, plan.findIndex((p) => p.id === currentId))
    const resume = pb.pending === containerId
    set({ playback: { ...pb, decisions, plan, cursor, pending: null, playing: resume ? true : pb.playing } })
    if (resume && index && BRANCH_KINDS.has(index.byId.get(containerId)?.kind ?? '')) {
      // Move past the container now that it is decided.
      get().stepPlayback(1)
    }
  },

  setAskAtBranches(ask) {
    set({ playback: { ...get().playback, askAtBranches: ask } })
  }
}))

/**
 * Reads metadata for the current script from the selected connections, then analyzes again with
 * it. Runs only when online mode is on and the script, dialect or connections changed since the
 * last load (the key), so the re-analysis it triggers does not load again. Debounced; a newer
 * load or analysis cancels an older one.
 */
function scheduleMetadataLoad(immediate: boolean): void {
  const st = useStore.getState()
  const { settings, doc, analysis, dialect, online } = st
  if (!settings?.online.enabled || !doc || !analysis || analysis.status === 'FAILED') return
  const ids = settings.online.connectionIds.filter((id) => settings.connections.some((c) => c.id === id))
  if (ids.length === 0) {
    if (online.status !== 'idle' || online.tables) useStore.setState({ online: { ...OFFLINE, status: 'idle' } })
    return
  }
  const key = metadataKey(dialect, ids, doc.text)
  if (online.key === key) return
  st.cancelMetadata()
  metadataTimer = setTimeout(() => void loadMetadata(key, ids), immediate ? 0 : METADATA_DEBOUNCE_MS)
}

async function loadMetadata(key: string, connectionIds: string[]): Promise<void> {
  metadataTimer = null
  const { doc, dialect } = useStore.getState()
  if (!doc) return
  const requestId = newRequestId('metadata')
  const previous = useStore.getState().online
  useStore.setState({ online: { ...previous, status: 'loading', key, requestId, error: null } })
  const source = doc.path && !doc.modified ? { path: doc.path } : { text: doc.text }
  const r = await api().db.loadMetadata({ dialect, ...source, connectionIds }, requestId)
  const now = useStore.getState()
  if (now.online.requestId !== requestId || !now.settings?.online.enabled) return
  if (!r.ok) {
    if (r.error.code === RPC_ERROR.REQUEST_CANCELLED) return
    useStore.setState({ online: { ...now.online, status: 'error', error: r.error.message || 'Could not read metadata.', requestId: null } })
    return
  }
  const tables = r.value.pack?.tables ?? []
  const changed = tables.length > 0 || Boolean(previous.tables?.length)
  useStore.setState({ online: { status: 'ready', key, tables, result: r.value, error: null, requestId: null } })
  // The key now matches, so this analysis does not trigger another load.
  if (changed) void now.analyze()
}
