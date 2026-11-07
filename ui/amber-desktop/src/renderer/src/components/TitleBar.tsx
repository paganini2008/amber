import clsx from 'clsx'
import {
  AlignLeft,
  ChevronDown,
  ClipboardPaste,
  Clock3,
  Contrast,
  Download,
  FileText,
  FolderOpen,
  GitCompareArrows,
  Monitor,
  Moon,
  PanelRight,
  Settings,
  Sun,
  Sunset
} from 'lucide-react'
import type { ThemeId } from '@shared/api'
import { compareWithFile, compareWithSaved, exportHandover, exportJson, exportMermaid, exportPng, exportSvg, openFileDialog, openPath, openSample } from '@/actions'
import logo from '@/assets/logo.png'
import { api } from '@/lib/api'
import { useStore } from '@/store'
import { IconButton, Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuSub, MenuTrigger, Select } from './ui'
import { SAMPLES } from '@/samples'
import { OnlineMenu } from './Metadata'

export const isMac = navigator.userAgent.includes('Mac')

const THEME_OPTIONS: { id: ThemeId; label: string; icon: typeof Sun }[] = [
  { id: 'system', label: 'System', icon: Monitor },
  { id: 'amber-light', label: 'Amber Light', icon: Sun },
  { id: 'amber-dark', label: 'Amber Dark', icon: Moon },
  { id: 'resin', label: 'Resin', icon: Sunset },
  { id: 'high-contrast', label: 'High Contrast', icon: Contrast }
]

const FALLBACK_DIALECTS = [
  { id: 'oracle', name: 'Oracle' },
  { id: 'sqlserver', name: 'SQL Server' },
  { id: 'postgresql', name: 'PostgreSQL' },
  { id: 'mysql', name: 'MySQL' },
  { id: 'mariadb', name: 'MariaDB' },
  { id: 'db2', name: 'Db2' },
  { id: 'h2', name: 'H2' },
  { id: 'sqlite', name: 'SQLite' }
]

export function TitleBar(): React.JSX.Element {
  const doc = useStore((s) => s.doc)
  const dialect = useStore((s) => s.dialect)
  const dialects = useStore((s) => s.dialects)
  const settings = useStore((s) => s.settings)
  const setDialect = useStore((s) => s.setDialect)
  const openDialog = useStore((s) => s.openDialog)
  const rightPanel = useStore((s) => s.rightPanel)
  const toggleRightPanel = useStore((s) => s.toggleRightPanel)
  const view = useStore((s) => s.view)
  const hasAnalysis = useStore((s) => Boolean(s.analysis && s.analysis.status !== 'FAILED'))
  const theme = settings?.theme ?? 'system'
  const ThemeIcon = THEME_OPTIONS.find((t) => t.id === theme)?.icon ?? Monitor
  const list = dialects.length ? dialects : FALLBACK_DIALECTS

  return (
    <header
      className={clsx('chrome drag flex h-10 shrink-0 items-center gap-2 border-b border-line', isMac ? 'pl-[84px]' : 'pl-3')}
      style={{ paddingRight: isMac ? 10 : 148 }}
    >
      <div className="flex items-center gap-2 pr-2">
        <img src={logo} alt="" className="h-[22px] w-[22px]" draggable={false} />
        <span className="text-[15px] font-semibold tracking-[-0.01em]">Amber</span>
      </div>

      <Menu>
        <MenuTrigger asChild>
          <button
            type="button"
            className="no-drag flex h-7 max-w-[340px] items-center gap-1.5 rounded-[5px] px-2 text-sm hover:bg-surface-hover"
            aria-label="File"
          >
            <span className={clsx('truncate', doc ? 'code text-[13px] text-ink' : 'text-ink-muted')}>{doc ? doc.name : 'No script open'}</span>
            {doc?.edited ? <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-ink-muted" title="Edited since the last analysis" /> : null}
            <ChevronDown size={13} className="shrink-0 text-ink-muted" />
          </button>
        </MenuTrigger>
        <MenuContent>
          <MenuItem onSelect={() => void openFileDialog()} hint={isMac ? '⌘O' : 'Ctrl+O'}>
            <FolderOpen size={14} /> Open script…
          </MenuItem>
          <MenuItem onSelect={() => openDialog('paste')} hint={isMac ? '⇧⌘V' : 'Ctrl+Shift+V'}>
            <ClipboardPaste size={14} /> Paste SQL…
          </MenuItem>
          {doc ? (
            <>
              <MenuSeparator />
              <MenuLabel>Make it readable again</MenuLabel>
              <MenuItem onSelect={() => void useStore.getState().formatScript('script')} hint={isMac ? '⇧⌥F' : 'Shift+Alt+F'}>
                <AlignLeft size={14} /> Format script
              </MenuItem>
              <MenuItem onSelect={() => void useStore.getState().formatScript('selection')} hint={isMac ? '⌘K ⌘F' : 'Ctrl+K Ctrl+F'}>
                <span className="pl-[22px]">Format selection</span>
              </MenuItem>
              <MenuSeparator />
              <MenuLabel>Is this change safe?</MenuLabel>
              <MenuItem onSelect={() => void compareWithSaved()} disabled={!doc.path} hint={isMac ? '⇧⌘D' : 'Ctrl+Shift+D'}>
                <GitCompareArrows size={14} /> Compare with the saved file
              </MenuItem>
              <MenuItem onSelect={() => void compareWithFile('older')}>
                <span className="pl-[22px]">Compare with an older version…</span>
              </MenuItem>
              <MenuItem onSelect={() => void compareWithFile('newer')}>
                <span className="pl-[22px]">Compare with a newer version…</span>
              </MenuItem>
              <MenuSeparator />
            </>
          ) : null}
          <MenuSub
            label={
              <>
                <FileText size={14} /> Open sample
              </>
            }
          >
            {SAMPLES.map((s) => (
              <MenuItem key={s.dialect} onSelect={() => openSample(s.dialect)} hint={s.label}>
                {s.database}
              </MenuItem>
            ))}
          </MenuSub>
          {settings?.recentFiles.length ? (
            <>
              <MenuSeparator />
              <MenuLabel>Recent</MenuLabel>
              {settings.recentFiles.map((p) => (
                <MenuItem key={p} onSelect={() => void openPath(p)}>
                  <Clock3 size={14} className="shrink-0 text-ink-muted" />
                  <span className="truncate" title={p}>
                    {p.split(/[\\/]/).pop()}
                    <span className="ml-2 text-xs text-ink-muted">{shortDir(p)}</span>
                  </span>
                </MenuItem>
              ))}
              <MenuItem onSelect={() => void api().settings.clearRecentFiles()}>
                <span className="pl-[22px] text-ink-muted">Clear recent files</span>
              </MenuItem>
            </>
          ) : null}
        </MenuContent>
      </Menu>

      <Select
        label="SQL dialect"
        value={dialect}
        onChange={setDialect}
        options={list.map((d) => ({ value: String(d.id), label: d.name }))}
        className="w-[132px]"
      />

      <OnlineMenu />

      <div className="flex-1" />

      <Menu>
        <MenuTrigger asChild>
          <IconButton label="Export" disabled={!hasAnalysis}>
            <Download size={16} />
          </IconButton>
        </MenuTrigger>
        <MenuContent align="end">
          <MenuLabel>{view === 'structure' ? 'Current query structure' : 'Current graph, matrix or lineage'}</MenuLabel>
          <MenuItem onSelect={() => void exportSvg()}>SVG</MenuItem>
          <MenuItem onSelect={() => void exportPng(1)}>PNG at 1×</MenuItem>
          <MenuItem onSelect={() => void exportPng(2)}>PNG at 2×</MenuItem>
          <MenuSeparator />
          <MenuItem onSelect={() => void exportMermaid()}>{view === 'structure' ? 'Mermaid (nested subgraphs)' : view === 'lineage' ? 'Mermaid (lineage)' : 'Mermaid (object level)'}</MenuItem>
          <MenuItem onSelect={() => void exportJson()}>Analysis as JSON</MenuItem>
          <MenuSeparator />
          <MenuLabel>Handover document for the next maintainer</MenuLabel>
          <MenuItem onSelect={() => openDialog('handover')}>Preview…</MenuItem>
          <MenuItem onSelect={() => void exportHandover('markdown')}>Save as Markdown…</MenuItem>
          <MenuItem onSelect={() => void exportHandover('html')}>Save as HTML…</MenuItem>
        </MenuContent>
      </Menu>

      <Menu>
        <MenuTrigger asChild>
          <IconButton label="Theme">
            <ThemeIcon size={16} />
          </IconButton>
        </MenuTrigger>
        <MenuContent align="end">
          {THEME_OPTIONS.map((t) => (
            <MenuItem key={t.id} checked={t.id === theme} onSelect={() => void api().settings.update({ theme: t.id })}>
              <t.icon size={14} className="text-ink-muted" /> {t.label}
            </MenuItem>
          ))}
        </MenuContent>
      </Menu>
      <IconButton label="Details panel" active={rightPanel} onClick={toggleRightPanel}>
        <PanelRight size={16} />
      </IconButton>
      <IconButton label="Settings" onClick={() => openDialog('settings')}>
        <Settings size={16} />
      </IconButton>
    </header>
  )
}

function shortDir(path: string): string {
  const parts = path.split(/[\\/]/)
  parts.pop()
  const dir = parts.slice(-2).join('/')
  return dir ? `…/${dir}` : ''
}
