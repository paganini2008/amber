import clsx from 'clsx'
import { ChevronDown, Database, Download, FileCode2, Package, RefreshCw, Save, X } from 'lucide-react'
import { useMemo } from 'react'
import type { MetadataImportResult, MetadataPack } from '@shared/protocol'
import { fmt, t } from '@/i18n'
import { api, describeError, newRequestId } from '@/lib/api'
import { fileName, mergePacks, summarizeLoad, type MetadataFiles } from '@/lib/metadata'
import { needsDriverDownload, openDriverDownload, useDriverStatuses } from '@/lib/drivers'
import { useStore } from '@/store'
import { Button, Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger, Spinner } from './ui'
import { DbIcon } from './DbIcon'

const ddlFilters = (): { name: string; extensions: string[] }[] => [
  { name: t('online.files.ddl'), extensions: ['sql', 'ddl', 'txt'] },
  { name: t('online.files.all'), extensions: ['*'] }
]
const packFilters = (): { name: string; extensions: string[] }[] => [
  { name: t('online.files.packs'), extensions: ['json'] },
  { name: t('online.files.all'), extensions: ['*'] }
]

export async function addMetadataFiles(kind: keyof MetadataFiles): Promise<void> {
  const paths = await api().files.pickFiles({
    title: kind === 'ddlFiles' ? t('online.files.addDdlTitle') : t('online.files.addPacksTitle'),
    filters: kind === 'ddlFiles' ? ddlFilters() : packFilters(),
    multiple: true
  })
  if (paths.length) useStore.getState().addMetadataFiles(kind, paths)
}

/** Saves all metadata in use (offline files first, then online tables) as one pack file. */
export async function saveMetadataPack(): Promise<void> {
  const st = useStore.getState()
  const { metadataFiles: files, online, dialect, settings } = st
  const packs: (MetadataPack | null)[] = []
  if (files.ddlFiles.length || files.metadataPacks.length) {
    const r = await api().engine.call<MetadataImportResult>(
      'metadata.import',
      { dialect, ddlFiles: files.ddlFiles, packs: files.metadataPacks },
      newRequestId('metadata')
    )
    if (!r.ok) {
      st.toast(t('online.toast.readFailed', { reason: describeError(r.error) }), 'error')
      return
    }
    packs.push(r.value.pack)
  }
  if (settings?.online.enabled && online.result?.pack) packs.push(online.result.pack)
  const pack = mergePacks(dialect, packs, new Date().toISOString())
  if (pack.tables.length === 0) {
    st.toast(t('online.toast.nothingToSave'), 'error')
    return
  }
  const base = (st.doc?.name ?? 'script').replace(/\.[^.]+$/, '') || 'script'
  const path = await api().files.save({
    defaultName: `${base}-metadata.json`,
    filters: [{ name: t('online.files.pack'), extensions: ['json'] }],
    data: JSON.stringify(pack, null, 2)
  })
  if (path) st.toast(t('online.toast.saved', { count: pack.tables.length, file: fileName(path) }))
}

/** Title-bar control: Offline (default) or Online with the connections to look tables up in. */
export function OnlineMenu(): React.JSX.Element {
  const settings = useStore((s) => s.settings)
  const online = useStore((s) => s.online)
  const hasDoc = useStore((s) => Boolean(s.doc))
  const files = useStore((s) => s.metadataFiles)
  const setOnline = useStore((s) => s.setOnline)
  const setOnlineConnections = useStore((s) => s.setOnlineConnections)
  const refreshMetadata = useStore((s) => s.refreshMetadata)
  const openDialog = useStore((s) => s.openDialog)
  const setBottomPanel = useStore((s) => s.setBottomPanel)
  const enabled = Boolean(settings?.online.enabled)
  const connections = settings?.connections ?? []
  const selected = settings?.online.connectionIds ?? []
  const used = selected.filter((id) => connections.some((c) => c.id === id))
  const offlineCount = files.ddlFiles.length + files.metadataPacks.length

  const toggleConnection = (id: string): void => {
    void setOnlineConnections(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id])
  }

  return (
    <Menu>
      <MenuTrigger asChild>
        <button
          type="button"
          className={clsx('no-drag flex h-7 shrink-0 items-center gap-1.5 rounded-[5px] px-2 text-sm whitespace-nowrap hover:bg-surface-hover', enabled ? 'text-ink' : 'text-ink-muted')}
          aria-label={t('online.menu.mode')}
        >
          {online.status === 'loading' ? (
            <Spinner size={10} />
          ) : (
            <span className={clsx('h-2 w-2 rounded-full border', enabled ? 'border-amber bg-amber' : 'border-ink-muted')} aria-hidden />
          )}
          {enabled ? `${t('online.menu.online')}${used.length ? ` · ${used.length}` : ''}` : t('online.menu.offline')}
          <ChevronDown size={13} />
        </button>
      </MenuTrigger>
      <MenuContent>
        <div className="max-w-80 px-2 py-1.5 text-xs text-ink-muted">
          {t('online.menu.lead')}
        </div>
        <MenuSeparator />
        <MenuItem checked={!enabled} onSelect={() => void setOnline(false)}>
          {t('online.menu.offline')}
        </MenuItem>
        <MenuItem checked={enabled} onSelect={() => void setOnline(true)} disabled={connections.length === 0}>
          {t('online.menu.online')}
          {connections.length === 0 ? <span className="ml-auto text-xs text-ink-muted">{t('online.menu.addFirst')}</span> : null}
        </MenuItem>
        {connections.length ? (
          <>
            <MenuSeparator />
            <MenuLabel>{t('online.menu.lookUp')}</MenuLabel>
            {connections.map((c) => {
              const order = selected.indexOf(c.id)
              return (
                <MenuItem key={c.id} checked={order >= 0} keepOpen onSelect={() => toggleConnection(c.id)} hint={order >= 0 && used.length > 1 ? `${order + 1}` : c.dialect}>
                  <DbIcon dialect={c.dialect} size={14} />
                  <span className="truncate">{c.name}</span>
                </MenuItem>
              )
            })}
          </>
        ) : null}
        {enabled ? (
          <MenuItem onSelect={refreshMetadata} disabled={!hasDoc || used.length === 0}>
            <RefreshCw size={13} className="text-ink-muted" /> {t('online.menu.refresh')}
          </MenuItem>
        ) : null}
        <MenuItem onSelect={() => openDialog('settings', 'connections')}>
          <Database size={13} className="text-ink-muted" /> {t('online.menu.manage')}
        </MenuItem>
        <MenuSeparator />
        <MenuLabel>{t('online.offline.title')}{offlineCount ? ` (${fmt.number(offlineCount)})` : ''}</MenuLabel>
        <MenuItem onSelect={() => void addMetadataFiles('ddlFiles')}>
          <FileCode2 size={13} className="text-ink-muted" /> {t('online.menu.addDdl')}
        </MenuItem>
        <MenuItem onSelect={() => void addMetadataFiles('metadataPacks')}>
          <Package size={13} className="text-ink-muted" /> {t('online.menu.addPacks')}
        </MenuItem>
        {offlineCount || enabled ? (
          <MenuItem onSelect={() => setBottomPanel('metadata')} disabled={!hasDoc}>
            <span className="pl-[21px]">{t('online.menu.details')}</span>
          </MenuItem>
        ) : null}
      </MenuContent>
    </Menu>
  )
}

/** Bottom panel tab: what metadata the analysis uses and what online mode found. */
export function MetadataPanel(): React.JSX.Element {
  const settings = useStore((s) => s.settings)
  const online = useStore((s) => s.online)
  const files = useStore((s) => s.metadataFiles)
  const remove = useStore((s) => s.removeMetadataFile)
  const refreshMetadata = useStore((s) => s.refreshMetadata)
  const cancelMetadata = useStore((s) => s.cancelMetadata)
  const enabled = Boolean(settings?.online.enabled)
  const ids = useMemo(() => settings?.online.connectionIds ?? [], [settings])
  const sources = useMemo(
    () => (online.result ? summarizeLoad(online.result, ids, settings?.connections ?? []) : []),
    [online.result, ids, settings?.connections]
  )
  const names = useMemo(() => new Map((settings?.connections ?? []).map((c) => [c.id, c.name])), [settings?.connections])
  const dialects = useMemo(() => new Map((settings?.connections ?? []).map((c) => [c.id, c.dialect])), [settings?.connections])
  const connections = useMemo(() => new Map((settings?.connections ?? []).map((c) => [c.id, c])), [settings?.connections])
  // A connection without a driver gets a Download action next to the engine's "not installed" error.
  const drivers = useDriverStatuses()
  const r = online.result

  return (
    <div className="grid grid-cols-[1fr_1fr] gap-6 px-3 py-2 text-sm">
      <section className="min-w-0 space-y-2">
        <div className="flex items-center gap-2">
          <h3 className="font-semibold">{t('online.menu.online')}</h3>
          {!enabled ? (
            <span className="text-xs text-ink-muted">{t('online.panel.off')}</span>
          ) : online.status === 'loading' ? (
            <span className="flex items-center gap-1.5 text-xs text-ink-muted">
              <Spinner size={11} /> {t('online.panel.reading')}
              <button type="button" className="underline" onClick={cancelMetadata}>
                {t('common.cancel')}
              </button>
            </span>
          ) : (
            <Button size="sm" variant="ghost" onClick={refreshMetadata}>
              <RefreshCw size={12} /> {t('online.panel.refresh')}
            </Button>
          )}
        </div>
        {enabled && ids.length === 0 ? <p className="text-xs text-ink-muted">{t('online.panel.choose')}</p> : null}
        {enabled && online.status === 'error' ? <p className="text-xs text-risk-high">{online.error}</p> : null}
        {enabled && r ? (
          <>
            <p className="text-xs text-ink-muted">
              {t('online.panel.loaded', { count: r.tables })}
            </p>
            <ul className="space-y-0.5 text-xs">
              {sources.map((s) => {
                const connection = connections.get(s.id)
                return (
                <li key={s.id} className="flex items-center gap-2">
                  <span className="flex items-center gap-1.5 font-medium text-ink">
                    <DbIcon dialect={dialects.get(s.id)} size={14} />
                    {s.name}
                  </span>
                  {s.error ? (
                    <>
                      <span className="min-w-0 text-risk-high" title={s.error}>
                        {s.error}
                      </span>
                      {connection && needsDriverDownload(drivers, connection.dialect, connection.driverPath) ? (
                        <Button size="sm" variant="primary" className="shrink-0" onClick={() => openDriverDownload(connection.dialect, () => refreshMetadata())}>
                          <Download size={12} /> {t('settings.drivers.downloadDriver')}
                        </Button>
                      ) : null}
                    </>
                  ) : (
                    <span className="text-ink-muted">
                      {t('common.tables', { count: s.tables })}
                      {s.server ? ` · ${s.server}` : ''}
                    </span>
                  )}
                </li>
                )
              })}
            </ul>
            {r.ambiguous?.length ? (
              <div className="text-xs">
                <div className="text-ink">{t('online.panel.ambiguous')}</div>
                <p className="text-ink-muted">{t('online.panel.ambiguousHint')}</p>
                <ul className="mt-0.5 space-y-0.5">
                  {r.ambiguous.map((a) => (
                    <li key={a.name}>
                      <span className="code text-[12px] text-ink">{a.name}</span>
                      <span className="text-ink-muted">
                        {' '}
                        {t('online.panel.foundIn', {
                          sources: a.dataSources
                            .map((id, i) => ((a.chosen ? id === a.chosen : i === 0) ? t('online.panel.used', { name: names.get(id) ?? id }) : (names.get(id) ?? id)))
                            .join(t('common.listSeparator'))
                        })}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
            {r.missing?.length ? (
              <div className="text-xs">
                <div className="text-ink">{t('online.panel.missing', { count: r.missing.length })}</div>
                <p className="code text-[12px] break-words text-ink-muted">{r.missing.join(', ')}</p>
              </div>
            ) : null}
          </>
        ) : null}
      </section>
      <section className="min-w-0 space-y-2">
        <div className="flex items-center gap-2">
          <h3 className="font-semibold">{t('online.offline.title')}</h3>
          <Button size="sm" variant="ghost" onClick={() => void addMetadataFiles('ddlFiles')}>
            <FileCode2 size={12} /> {t('online.offline.addDdl')}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => void addMetadataFiles('metadataPacks')}>
            <Package size={12} /> {t('online.offline.addPack')}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => void saveMetadataPack()} disabled={!files.ddlFiles.length && !files.metadataPacks.length && !(enabled && r)}>
            <Save size={12} /> {t('online.offline.save')}
          </Button>
        </div>
        {files.ddlFiles.length + files.metadataPacks.length === 0 ? (
          <p className="text-xs text-ink-muted">{t('online.offline.none')}</p>
        ) : (
          <ul className="space-y-0.5 text-xs">
            {(['ddlFiles', 'metadataPacks'] as const).flatMap((kind) =>
              files[kind].map((p) => (
                <li key={`${kind}:${p}`} className="flex items-center gap-2">
                  {kind === 'ddlFiles' ? <FileCode2 size={12} className="shrink-0 text-ink-muted" /> : <Package size={12} className="shrink-0 text-ink-muted" />}
                  <span className="code min-w-0 truncate text-[12px] text-ink" title={p}>
                    {fileName(p)}
                  </span>
                  <span className="text-ink-muted">{kind === 'ddlFiles' ? t('online.offline.ddl') : t('online.offline.pack')}</span>
                  <button type="button" aria-label={t('online.offline.remove', { name: fileName(p) })} className="ml-auto text-ink-muted hover:text-ink" onClick={() => remove(kind, p)}>
                    <X size={12} />
                  </button>
                </li>
              ))
            )}
          </ul>
        )}
      </section>
    </div>
  )
}
