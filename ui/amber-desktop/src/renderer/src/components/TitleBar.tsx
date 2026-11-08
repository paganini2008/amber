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
  Info,
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
import { t, type MessageKey } from '@/i18n'
import { api } from '@/lib/api'
import { dialectName } from '@/lib/kinds'
import { useStore } from '@/store'
import { IconButton, Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuSub, MenuTrigger, Select } from './ui'
import { SAMPLES, sampleLabel } from '@/samples'
import { OnlineMenu } from './Metadata'
import { DbIcon } from './DbIcon'

export const isMac = navigator.userAgent.includes('Mac')

const THEME_OPTIONS: { id: ThemeId; label: MessageKey; icon: typeof Sun }[] = [
  { id: 'system', label: 'app.themes.system', icon: Monitor },
  { id: 'amber-light', label: 'app.themes.amberLight', icon: Sun },
  { id: 'amber-dark', label: 'app.themes.amberDark', icon: Moon },
  { id: 'resin', label: 'app.themes.resin', icon: Sunset },
  { id: 'high-contrast', label: 'app.themes.highContrast', icon: Contrast }
]

const FALLBACK_DIALECTS = ['oracle', 'sqlserver', 'postgresql', 'mysql', 'mariadb', 'db2', 'h2', 'sqlite'].map((id) => ({ id, name: dialectName(id) }))

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
  const ThemeIcon = THEME_OPTIONS.find((o) => o.id === theme)?.icon ?? Monitor
  const list = dialects.length ? dialects : FALLBACK_DIALECTS

  return (
    <header
      className={clsx('chrome drag flex h-10 shrink-0 items-center gap-2 border-b border-line', isMac ? 'pl-[84px]' : 'pl-3')}
      style={{ paddingRight: isMac ? 10 : 148 }}
    >
      <div className="flex items-center gap-2 pr-2">
        <img src={logo} alt="" className="h-[22px] w-[22px]" draggable={false} />
        <span className="text-[15px] font-semibold tracking-[-0.01em] max-[760px]:hidden">Amber</span>
      </div>

      <Menu>
        <MenuTrigger asChild>
          <button
            type="button"
            className="no-drag flex h-7 max-w-[340px] min-w-0 shrink items-center gap-1.5 rounded-[5px] px-2 text-sm hover:bg-surface-hover"
            aria-label={t('app.title.fileMenu')}
          >
            <span className={clsx('truncate', doc ? 'code text-[13px] text-ink' : 'text-ink-muted')}>{doc ? doc.name : t('app.title.noScript')}</span>
            {doc?.edited ? <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-ink-muted" title={t('app.analysis.edited')} /> : null}
            <ChevronDown size={13} className="shrink-0 text-ink-muted" />
          </button>
        </MenuTrigger>
        <MenuContent>
          <MenuItem onSelect={() => void openFileDialog()} hint={isMac ? '⌘O' : 'Ctrl+O'}>
            <FolderOpen size={14} /> {t('app.start.open')}
          </MenuItem>
          <MenuItem onSelect={() => openDialog('paste')} hint={isMac ? '⇧⌘V' : 'Ctrl+Shift+V'}>
            <ClipboardPaste size={14} /> {t('app.start.paste')}
          </MenuItem>
          {doc ? (
            <>
              <MenuSeparator />
              <MenuLabel>{t('app.title.readable')}</MenuLabel>
              <MenuItem onSelect={() => void useStore.getState().formatScript('script')} hint={isMac ? '⇧⌥F' : 'Shift+Alt+F'}>
                <AlignLeft size={14} /> {t('app.title.formatScript')}
              </MenuItem>
              <MenuItem onSelect={() => void useStore.getState().formatScript('selection')} hint={isMac ? '⌘K ⌘F' : 'Ctrl+K Ctrl+F'}>
                <span className="pl-[22px]">{t('app.title.formatSelection')}</span>
              </MenuItem>
              <MenuSeparator />
              <MenuLabel>{t('app.title.isSafe')}</MenuLabel>
              <MenuItem onSelect={() => void compareWithSaved()} disabled={!doc.path} hint={isMac ? '⇧⌘D' : 'Ctrl+Shift+D'}>
                <GitCompareArrows size={14} /> {t('app.title.compareSaved')}
              </MenuItem>
              <MenuItem onSelect={() => void compareWithFile('older')}>
                <span className="pl-[22px]">{t('app.title.compareOlder')}</span>
              </MenuItem>
              <MenuItem onSelect={() => void compareWithFile('newer')}>
                <span className="pl-[22px]">{t('app.title.compareNewer')}</span>
              </MenuItem>
              <MenuSeparator />
            </>
          ) : null}
          <MenuSub
            label={
              <>
                <FileText size={14} /> {t('app.title.openSample')}
              </>
            }
          >
            {SAMPLES.map((s) => (
              <MenuItem key={s.dialect} onSelect={() => openSample(s.dialect)} hint={sampleLabel(s)}>
                <DbIcon dialect={s.dialect} size={14} />
                {s.database}
              </MenuItem>
            ))}
          </MenuSub>
          {settings?.recentFiles.length ? (
            <>
              <MenuSeparator />
              <MenuLabel>{t('app.start.recent')}</MenuLabel>
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
                <span className="pl-[22px] text-ink-muted">{t('app.title.clearRecent')}</span>
              </MenuItem>
            </>
          ) : null}
          <MenuSeparator />
          <MenuItem onSelect={() => openDialog('about')}>
            <Info size={14} /> {t('app.title.about')}
          </MenuItem>
        </MenuContent>
      </Menu>

      <Select
        label={t('app.title.dialect')}
        value={dialect}
        onChange={setDialect}
        options={list.map((d) => ({ value: String(d.id), label: d.name, icon: <DbIcon dialect={String(d.id)} size={14} /> }))}
        className="w-[150px] shrink-0 max-[700px]:w-[118px]"
      />

      <OnlineMenu />

      <div className="flex-1" />

      <Menu>
        <MenuTrigger asChild>
          <IconButton label={t('app.export.label')} disabled={!hasAnalysis}>
            <Download size={16} />
          </IconButton>
        </MenuTrigger>
        <MenuContent align="end">
          <MenuLabel>{view === 'structure' ? t('app.export.currentStructure') : t('app.export.currentView')}</MenuLabel>
          <MenuItem onSelect={() => void exportSvg()}>SVG</MenuItem>
          <MenuItem onSelect={() => void exportPng(1)}>{t('app.export.png', { scale: 1 })}</MenuItem>
          <MenuItem onSelect={() => void exportPng(2)}>{t('app.export.png', { scale: 2 })}</MenuItem>
          <MenuSeparator />
          <MenuItem onSelect={() => void exportMermaid()}>{view === 'structure' ? t('app.export.mermaidStructure') : view === 'lineage' ? t('app.export.mermaidLineage') : t('app.export.mermaidObjects')}</MenuItem>
          <MenuItem onSelect={() => void exportJson()}>{t('app.export.json')}</MenuItem>
          <MenuSeparator />
          <MenuLabel>{t('app.export.handover')}</MenuLabel>
          <MenuItem onSelect={() => openDialog('handover')}>{t('app.export.preview')}</MenuItem>
          <MenuItem onSelect={() => void exportHandover('markdown')}>{t('app.export.markdown')}</MenuItem>
          <MenuItem onSelect={() => void exportHandover('html')}>{t('app.export.html')}</MenuItem>
        </MenuContent>
      </Menu>

      <Menu>
        <MenuTrigger asChild>
          <IconButton label={t('app.title.theme')}>
            <ThemeIcon size={16} />
          </IconButton>
        </MenuTrigger>
        <MenuContent align="end">
          {THEME_OPTIONS.map((o) => (
            <MenuItem key={o.id} checked={o.id === theme} onSelect={() => void api().settings.update({ theme: o.id })}>
              <o.icon size={14} className="text-ink-muted" /> {t(o.label)}
            </MenuItem>
          ))}
        </MenuContent>
      </Menu>
      <IconButton label={t('app.title.detailsPanel')} active={rightPanel} onClick={toggleRightPanel}>
        <PanelRight size={16} />
      </IconButton>
      <IconButton label={t('app.title.settings')} onClick={() => openDialog('settings')}>
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
