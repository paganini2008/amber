import clsx from 'clsx'
import { Boxes, CirclePlay, GitCompareArrows, History, ListTree, Network, Spline, SquareCode, Workflow, type LucideIcon } from 'lucide-react'
import { ClipboardPaste, FileText, FolderOpen, GitFork, NotebookPen, ScanSearch, ShieldCheck, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { compareWithDropped, compareWithFile, compareWithSaved, exportJson, exportMermaid, exportPng, exportSvg, openFileDialog, openPath, openSample } from './actions'
import logo from './assets/logo.png'
import { AboutDialog, HandoverDialog, PasteDialog } from './components/Dialogs'
import { ContextMenu } from './components/ContextMenu'
import { DetailsPanel } from './components/DetailsPanel'
import { RewriteDialog } from './components/Fixes'
import { OptimizedDialog } from './components/Optimized'
import { OutlinePanel } from './components/OutlinePanel'
import { SettingsDialog } from './components/SettingsDialog'
import { Splitter, usePaneSize } from './components/Splitter'
import { ErrorScreen, PartialBanner, ProblemsBar, SeverityIcon } from './components/StatusParts'
import { TitleBar } from './components/TitleBar'
import { Button, Spinner, TooltipProvider } from './components/ui'
import { SqlEditor } from './editor/SqlEditor'
import { useScrollFade, useSystemDark, useWindowWidth } from './hooks'
import { api, describeError } from './lib/api'
import { fitPanes } from './lib/panes'
import { t, useLanguage, type MessageKey } from './i18n'
import { SAMPLES, sampleLabel, sampleNote } from './samples'
import { useStore, type ViewId } from './store'
import { applyTheme } from './theme'
import { DataFlowView } from './views/DataFlowView'
import { LifecycleView } from './views/LifecycleView'
import { LineageView } from './views/LineageView'
import { OutlineView } from './views/OutlineView'
import { PlaybackBar, PlaybackClock, PlaybackView } from './views/Playback'
import { StatementView } from './views/StatementView'
import { DiffView } from './views/DiffView'
import { ErView } from './views/ErView'
import { StructureView } from './views/StructureView'
import { DbIcon } from './components/DbIcon'

/** The view tabs; each has its own glyph (16 px, stroke 1.75) beside the label, never instead of it. */
const VIEWS: { id: ViewId; label: MessageKey; icon: LucideIcon }[] = [
  { id: 'outline', label: 'app.views.outline', icon: ListTree },
  { id: 'lifecycle', label: 'app.views.lifecycle', icon: History },
  { id: 'dataflow', label: 'app.views.dataflow', icon: Workflow },
  { id: 'lineage', label: 'app.views.lineage', icon: Spline },
  { id: 'er', label: 'app.views.er', icon: Network },
  { id: 'statement', label: 'app.views.statement', icon: SquareCode },
  { id: 'structure', label: 'app.views.structure', icon: Boxes },
  { id: 'playback', label: 'app.views.playback', icon: CirclePlay }
]

export function App(): React.JSX.Element {
  const settings = useStore((s) => s.settings)
  // Re-render the whole tree when the language changes (Settings → General → Language).
  useLanguage()
  const systemDark = useSystemDark()

  // Settings, engine status and menu commands from the main process.
  useEffect(() => {
    const st = useStore.getState()
    void api().settings.get().then(st.setSettings)
    void api().engine.status().then(st.setEngine)
    const offSettings = api().settings.onChanged((s) => useStore.getState().setSettings(s))
    const offEngine = api().engine.onStatus((s) => useStore.getState().setEngine(s))
    const offMenu = api().app.onMenu((command) => {
      const s = useStore.getState()
      switch (command) {
        case 'open-file':
          void openFileDialog()
          break
        case 'paste-sql':
          s.openDialog('paste')
          break
        case 'settings':
          s.openDialog('settings')
          break
        case 'about':
          s.openDialog('about')
          break
        case 'reanalyze':
          void s.analyze()
          break
        case 'export-json':
          void exportJson()
          break
        case 'export-svg':
          void exportSvg()
          break
        case 'export-png':
          void exportPng(2)
          break
        case 'export-mermaid':
          void exportMermaid()
          break
        case 'compare-saved':
          if (s.doc) void compareWithSaved()
          break
        case 'compare-file':
          if (s.doc) void compareWithFile('older')
          else s.toast(t('app.toast.openBeforeCompare'), 'error')
          break
        case 'format-script':
          if (s.doc) void s.formatScript('script')
          break
        case 'format-selection':
          if (s.doc) void s.formatScript('selection')
          break
        case 'export-handover':
          if (s.analysis && s.analysis.status !== 'FAILED') s.openDialog('handover')
          else s.toast(t('app.toast.analyzeFirst'), 'error')
          break
      }
    })
    return () => {
      offSettings()
      offEngine()
      offMenu()
    }
  }, [])

  useEffect(() => {
    if (settings) applyTheme(settings, systemDark)
  }, [settings, systemDark])

  // Optional update check on start-up (off in Settings → Updates).
  const checkUpdates = settings?.checkForUpdates
  useEffect(() => {
    if (!checkUpdates) return
    let cancelled = false
    void api()
      .app.checkForUpdates()
      .then((r) => {
        if (!cancelled && r.ok && r.value?.newer) useStore.getState().toast(t('app.toast.updateAvailable', { version: r.value.latest }))
      })
    return () => {
      cancelled = true
    }
  }, [checkUpdates])

  // Re-analyze when the fallback default changes is not wanted; only explicit actions re-run.

  return (
    <TooltipProvider>
      <div className="flex h-full flex-col">
        <TitleBar />
        <Workspace />
        <ProblemsBar />
      </div>
      <SettingsDialog />
      <AboutDialog />
      <PasteDialog />
      <HandoverDialog />
      <RewriteDialog />
      <OptimizedDialog />
      <PlaybackClock />
      <Toasts />
      <ContextMenu />
      <DropTarget />
      <KeyboardShortcuts />
    </TooltipProvider>
  )
}

function Workspace(): React.JSX.Element {
  const doc = useStore((s) => s.doc)
  const rightPanel = useStore((s) => s.rightPanel)
  const view = useStore((s) => s.view)
  const [left, setLeft] = usePaneSize('amber.pane.left', 280)
  const [right, setRight] = usePaneSize('amber.pane.right', 340)
  const [editor, setEditor] = usePaneSize('amber.pane.editor', 300)
  const windowWidth = useWindowWidth()
  // Saved widths may come from a larger window; shrink them so the center stays usable.
  const fit = fitPanes(windowWidth, left, right, rightPanel)

  if (!doc) return <EmptyState />

  return (
    <div className="flex min-h-0 flex-1">
      <div style={{ width: fit.left }} className="min-w-0 shrink-0">
        <OutlinePanel />
      </div>
      <Splitter direction="x" size={fit.left} onChange={setLeft} min={180} max={520} label={t('app.resize.outline')} />
      <main className="flex min-w-0 flex-1 flex-col">
        <PartialBanner />
        <PlaybackBar />
        <CenterView />
        {/* The comparison has its own side-by-side editors, so the script editor steps aside. */}
        {view === 'diff' ? null : <Splitter direction="y" size={editor} onChange={setEditor} min={80} max={900} invert label={t('app.resize.editor')} />}
        {/* The center view keeps at least 160px on small windows, whatever the saved editor height. */}
        <div style={{ height: editor, maxHeight: 'calc(100% - 160px)' }} className={clsx('min-h-0 shrink-0', view === 'diff' && 'hidden')}>
          <SqlEditor />
        </div>
      </main>
      {rightPanel ? (
        <>
          <Splitter direction="x" size={fit.right} onChange={setRight} min={260} max={620} invert label={t('app.resize.details')} />
          <div style={{ width: fit.right }} className="min-w-0 shrink-0">
            <DetailsPanel />
          </div>
        </>
      ) : null}
    </div>
  )
}

function CenterView(): React.JSX.Element {
  const view = useStore((s) => s.view)
  const setView = useStore((s) => s.setView)
  const analysis = useStore((s) => s.analysis)
  const analyzing = useStore((s) => s.analyzing)
  const analyzeError = useStore((s) => s.analyzeError)
  const doc = useStore((s) => s.doc)!
  const analyze = useStore((s) => s.analyze)
  const cancelAnalysis = useStore((s) => s.cancelAnalysis)
  const failed = analysis?.status === 'FAILED'
  const diffStatus = useStore((s) => s.diff.status)
  const diffRisk = useStore((s) => s.diff.result?.summary.risk)
  // On a narrow window the tab row scrolls; a fade marks the side where more tabs are.
  const tabsRef = useRef<HTMLDivElement>(null)
  const tabsFade = useScrollFade(tabsRef)

  let body: React.JSX.Element
  if (view === 'diff') {
    // The comparison stands on its own: it does not need the open script's analysis.
    body = <DiffView />
  } else if (analyzeError) {
    body = (
      <div className="flex h-full items-center justify-center p-8">
        <div className="max-w-[480px]">
          <h2 className="text-lg font-semibold">{t('app.analysis.didNotFinish')}</h2>
          <p className="mt-1 text-sm text-ink-muted">{describeError(analyzeError)}</p>
          <Button className="mt-4" onClick={() => void analyze()}>
            {t('common.retry')}
          </Button>
        </div>
      </div>
    )
  } else if (!analysis) {
    body = (
      <div className="flex h-full items-center justify-center gap-3 text-sm text-ink-muted">
        {analyzing ? (
          <>
            <Spinner /> {t('app.analysis.analyzingFile', { name: doc.name })}
            <Button size="sm" variant="ghost" onClick={cancelAnalysis}>
              {t('common.cancel')}
            </Button>
          </>
        ) : (
          <Button onClick={() => void analyze()}>{t('app.analysis.analyze')}</Button>
        )}
      </div>
    )
  } else if (failed) {
    body = <ErrorScreen />
  } else {
    body =
      view === 'outline' ? (
        <OutlineView />
      ) : view === 'lifecycle' ? (
        <LifecycleView />
      ) : view === 'dataflow' ? (
        <DataFlowView />
      ) : view === 'lineage' ? (
        <LineageView />
      ) : view === 'er' ? (
        <ErView />
      ) : view === 'statement' ? (
        <StatementView />
      ) : view === 'structure' ? (
        <StructureView />
      ) : (
        <PlaybackView />
      )
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-canvas">
      <div className="chrome flex h-9 shrink-0 items-end gap-1 border-b border-line px-3">
        {/* Tabs keep one line; on a narrow center they scroll sideways instead of wrapping. */}
        <div ref={tabsRef} className={clsx('no-scrollbar -mb-px flex min-w-0 items-end gap-1 overflow-x-auto', tabsFade.left && 'tabs-fade-left', tabsFade.right && 'tabs-fade-right')} role="tablist" aria-label={t('app.views.label')}>
        {VIEWS.map((v, i) => (
          <button
            key={v.id}
            type="button"
            role="tab"
            aria-selected={view === v.id}
            disabled={!analysis || failed}
            onClick={() => setView(v.id)}
            title={`${t(v.label)} (${navigator.userAgent.includes('Mac') ? '⌘' : 'Ctrl+'}${i + 1})`}
            className={clsx(
              'flex h-8 shrink-0 items-center gap-1.5 border-b-2 px-2.5 text-sm whitespace-nowrap transition-colors disabled:opacity-40',
              view === v.id && analysis && !failed ? 'border-amber font-medium text-ink' : 'border-transparent text-ink-muted hover:text-ink'
            )}
          >
            <v.icon size={16} strokeWidth={1.75} aria-hidden className={view === v.id && analysis && !failed ? 'text-amber-ink' : undefined} />
            {t(v.label)}
          </button>
        ))}
        {diffStatus !== 'idle' ? (
          <button
            type="button"
            role="tab"
            aria-selected={view === 'diff'}
            onClick={() => setView('diff')}
            className={clsx(
              'flex h-8 shrink-0 items-center gap-1.5 border-b-2 px-2.5 text-sm whitespace-nowrap transition-colors',
              view === 'diff' ? 'border-amber font-medium text-ink' : 'border-transparent text-ink-muted hover:text-ink'
            )}
          >
            <GitCompareArrows size={16} strokeWidth={1.75} aria-hidden className={view === 'diff' ? 'text-amber-ink' : undefined} />
            {diffStatus === 'loading' ? <Spinner size={11} /> : diffRisk ? <SeverityIcon severity={diffRisk} size={12} /> : null}
            {t('app.views.diff')}
          </button>
        ) : null}
        </div>
        <div className="flex-1" />
        {doc.edited ? (
          <div className="mb-1 flex shrink-0 items-center gap-2 text-xs whitespace-nowrap text-ink-muted">
            {t('app.analysis.edited')}
            <Button size="sm" onClick={() => void analyze()}>
              {t('app.analysis.again')}
            </Button>
          </div>
        ) : analyzing && analysis ? (
          <div className="mb-2 flex shrink-0 items-center gap-1.5 text-xs whitespace-nowrap text-ink-muted">
            <Spinner size={12} /> {t('app.analysis.analyzing')}
          </div>
        ) : null}
      </div>
      <div className="min-h-0 flex-1" role="tabpanel">
        {body}
      </div>
    </div>
  )
}

/** What people open Amber for; each maps to a feature (outline and data flow, lineage, findings, handover document). */
const MAINTENANCE_QUESTIONS: { icon: typeof ScanSearch; text: MessageKey }[] = [
  { icon: ScanSearch, text: 'app.start.questionWhat' },
  { icon: GitFork, text: 'app.start.questionBreaks' },
  { icon: ShieldCheck, text: 'app.start.questionSafe' },
  { icon: NotebookPen, text: 'app.start.questionHandover' }
]

function EmptyState(): React.JSX.Element {
  const settings = useStore((s) => s.settings)
  const openDialog = useStore((s) => s.openDialog)
  const recent = settings?.recentFiles ?? []
  return (
    <div className="flex min-h-0 flex-1 overflow-hidden bg-canvas">
      {/* Each column scrolls on its own; my-auto centres the content without clipping it on short windows. */}
      <section className="chrome hero relative flex w-[44%] max-w-[620px] min-w-[360px] shrink-0 flex-col overflow-x-hidden overflow-y-auto px-12 py-10 max-[1100px]:px-8">
        <div className="hero-glow" aria-hidden />
        <div className="relative my-auto">
          <img src={logo} alt="" className="hero-logo h-28 w-28" draggable={false} />
          <h1 className="mt-8 max-w-[440px] text-[34px] leading-[40px] font-semibold tracking-[-0.02em] text-ink">{t('app.start.title')}</h1>
          <p className="mt-3 max-w-[420px] text-[15px] leading-[23px] text-ink-muted">
            {t('app.start.lead')}
          </p>
          <ul className="mt-5 space-y-1.5 text-[14px] leading-[21px] text-ink">
            {MAINTENANCE_QUESTIONS.map((q) => (
              <li key={q.text} className="flex items-center gap-2.5">
                <q.icon size={15} className="shrink-0 text-amber-ink" aria-hidden />
                {t(q.text)}
              </li>
            ))}
          </ul>
          <div className="mt-8 flex flex-wrap gap-2">
            <Button variant="primary" className="h-9 px-4 text-base" onClick={() => void openFileDialog()}>
              <FolderOpen size={16} /> {t('app.start.open')}
            </Button>
            <Button className="h-9 px-4 text-base" onClick={() => openDialog('paste')}>
              <ClipboardPaste size={16} /> {t('app.start.paste')}
            </Button>
          </div>
          <p className="mt-4 text-sm text-ink-muted">{t('app.start.drop')}</p>
        </div>
      </section>
      <div className="flex min-w-0 flex-1 flex-col overflow-y-auto px-12 py-10 max-[1100px]:px-8">
        <div className="my-auto w-full max-w-[720px] space-y-8">
          <section>
            <h2 className="text-lg font-semibold text-ink">{t('app.start.samples')}</h2>
            <p className="mt-0.5 text-sm text-ink-muted">{t('app.start.samplesLead')}</p>
            <ul className="mt-3 grid grid-cols-2 gap-2">
              {SAMPLES.map((s) => (
                <li key={s.dialect}>
                  <button
                    type="button"
                    onClick={() => openSample(s.dialect)}
                    title={sampleNote(s)}
                    className="sample-card group flex h-full w-full items-center gap-3 rounded-[7px] border border-line bg-surface px-3 py-2.5 text-left"
                  >
                    <DbIcon dialect={s.dialect} size={20} className="sample-db-icon" />
                    <span className="min-w-0">
                      <span className="block text-[15px] leading-5 font-semibold text-ink">{s.database}</span>
                      <span className="block truncate text-xs text-ink-muted">{sampleLabel(s)}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
          {recent.length ? (
            <section>
              <h2 className="mb-2 text-lg font-semibold text-ink">{t('app.start.recent')}</h2>
              <ul className="space-y-0.5">
                {recent.slice(0, 6).map((p) => (
                  <li key={p}>
                    <button type="button" onClick={() => void openPath(p)} className="flex w-full items-baseline gap-2 rounded-[4px] px-2 py-1 text-left hover:bg-surface-hover" title={p}>
                      <FileText size={13} className="shrink-0 self-center text-ink-muted" />
                      <span className="code shrink-0 text-[13px] text-ink">{p.split(/[\\/]/).pop()}</span>
                      <span className="min-w-0 truncate text-xs text-ink-muted">{p}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
        </div>
      </div>
    </div>
  )
}

/** Which half a file is dropped on: open it, or compare it with the open script. */
type DropZone = 'open' | 'compare'

function DropTarget(): React.JSX.Element | null {
  const [dragging, setDragging] = useState(false)
  const [zone, setZone] = useState<DropZone>('open')
  const hasDoc = useStore((s) => Boolean(s.doc))
  useEffect(() => {
    let depth = 0
    const hasFiles = (e: DragEvent): boolean => Boolean(e.dataTransfer?.types.includes('Files'))
    const zoneAt = (e: DragEvent): DropZone => (useStore.getState().doc && e.clientX > window.innerWidth / 2 ? 'compare' : 'open')
    const enter = (e: DragEvent): void => {
      if (!hasFiles(e)) return
      depth += 1
      setDragging(true)
    }
    const leave = (): void => {
      depth = Math.max(0, depth - 1)
      if (depth === 0) setDragging(false)
    }
    const over = (e: DragEvent): void => {
      if (!hasFiles(e)) return
      e.preventDefault()
      setZone(zoneAt(e))
    }
    const drop = (e: DragEvent): void => {
      e.preventDefault()
      depth = 0
      setDragging(false)
      const file = e.dataTransfer?.files[0]
      if (!file) return
      const path = api().files.pathForFile(file) || null
      if (zoneAt(e) === 'compare') {
        if (path) void compareWithDropped({ path, name: file.name })
        else void file.text().then((text) => compareWithDropped({ path: null, name: file.name, text }))
        return
      }
      if (path) void openPath(path)
      else void file.text().then((text) => useStore.getState().loadDocument({ path: null, name: file.name, text, encoding: 'UTF-8' }))
    }
    window.addEventListener('dragenter', enter)
    window.addEventListener('dragleave', leave)
    window.addEventListener('dragover', over)
    window.addEventListener('drop', drop)
    return () => {
      window.removeEventListener('dragenter', enter)
      window.removeEventListener('dragleave', leave)
      window.removeEventListener('dragover', over)
      window.removeEventListener('drop', drop)
    }
  }, [])
  if (!dragging) return null
  const box = (active: boolean): string =>
    clsx(
      'flex flex-1 flex-col items-center justify-center gap-1 rounded-[10px] border-2 border-dashed px-6 text-center transition-colors',
      active ? 'border-amber bg-[color-mix(in_srgb,var(--amber)_12%,var(--canvas))]' : 'border-line-strong bg-[color-mix(in_srgb,var(--canvas)_85%,transparent)]'
    )
  return (
    <div className="pointer-events-none fixed inset-3 z-50 flex gap-3">
      <div className={box(!hasDoc || zone === 'open')}>
        <span className="text-lg font-medium text-ink">{t('app.drop.open')}</span>
        {hasDoc ? <span className="text-sm text-ink-muted">{t('app.drop.openHint')}</span> : null}
      </div>
      {hasDoc ? (
        <div className={box(zone === 'compare')}>
          <span className="text-lg font-medium text-ink">{t('app.drop.compare')}</span>
          <span className="text-sm text-ink-muted">{t('app.drop.compareHint')}</span>
        </div>
      ) : null}
    </div>
  )
}

function KeyboardShortcuts(): null {
  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => {
      const st = useStore.getState()
      const mod = e.metaKey || e.ctrlKey
      if (mod && /^[1-8]$/.test(e.key) && st.analysis && st.analysis.status !== 'FAILED') {
        e.preventDefault()
        st.setView(VIEWS[Number(e.key) - 1]!.id)
        return
      }
      const inEditor = (e.target as HTMLElement | null)?.closest?.('.cm-editor, input, textarea, [role="dialog"]')
      if (inEditor) return
      if (e.key === 'Escape' && st.selection) st.select(null)
      if (e.key === ' ' && st.view === 'playback' && st.analysis) {
        e.preventDefault()
        st.setPlaying(!st.playback.playing)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])
  return null
}

function Toasts(): React.JSX.Element {
  const toasts = useStore((s) => s.toasts)
  const dismiss = useStore((s) => s.dismissToast)
  return (
    <div className="pointer-events-none fixed right-4 bottom-10 z-50 flex flex-col items-end gap-2" role="status" aria-live="polite">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className={clsx(
            'pointer-events-auto flex max-w-[420px] items-start gap-2 rounded-[6px] border bg-surface px-3 py-2 text-sm shadow-[0_8px_28px_rgba(0,0,0,0.18)]',
            toast.tone === 'error' ? 'border-risk-high' : 'border-line'
          )}
        >
          <span className="min-w-0 flex-1">{toast.message}</span>
          {toast.actions?.map((a) => (
            <button
              key={a.label}
              type="button"
              className="shrink-0 font-medium text-amber-ink hover:underline"
              onClick={() => {
                dismiss(toast.id)
                a.run()
              }}
            >
              {a.label}
            </button>
          ))}
          <button type="button" aria-label={t('app.toast.dismiss')} onClick={() => dismiss(toast.id)} className="text-ink-muted hover:text-ink">
            <X size={13} />
          </button>
        </div>
      ))}
    </div>
  )
}
