import * as Tabs from '@radix-ui/react-tabs'
import { AlignLeft, CircleCheck, Database, Download, ListChecks, Plus, RefreshCw, ScanSearch, SlidersHorizontal, Sparkles, Trash, type LucideIcon } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { DRIVERS, normalizeRepositoryUrl } from '@shared/drivers'
import { isOpenAiHost, modelOptions, OLLAMA_PRESETS, OPENAI_PRESETS } from '@shared/aiModels'
import { LANGUAGES, type AiProviderId, type ConnectionProfile, type Settings, type SettingsPatch, type ThemeId } from '@shared/api'
import type { ServerInfo } from '@shared/protocol'
import { t, type MessageKey } from '@/i18n'
import { CATALOGS } from '@/i18n/catalog'
import { resolveLanguage } from '@/i18n/core'
import { api, describeError } from '@/lib/api'
import { dialectName } from '@/lib/kinds'
import { useStore } from '@/store'
import { downloadLabel, driverSize, needsDriverDownload, openDriverDownload, refreshDrivers, useDriverStatuses } from '@/lib/drivers'
import { ModelCombobox } from './ModelCombobox'
import { RulesSettings } from './RulesSettings'
import { Button, Dialog, Field, Select, Spinner, Switch, inputClass } from './ui'
import { DbIcon } from './DbIcon'
import { ConnectionDriverField } from './ConnectionDriverField'
import { UpdateCheck } from './UpdateCheck'

const dialectOptions = (): { value: string; label: string }[] =>
  ['oracle', 'sqlserver', 'postgresql', 'mysql', 'mariadb', 'db2', 'h2', 'sqlite'].map((d) => ({ value: d, label: dialectName(d), icon: <DbIcon dialect={d} size={14} /> }))

const TABS: { id: string; label: MessageKey; icon: LucideIcon }[] = [
  { id: 'general', label: 'settings.tabs.general', icon: SlidersHorizontal },
  { id: 'analysis', label: 'settings.tabs.analysis', icon: ScanSearch },
  { id: 'rules', label: 'rules.tab', icon: ListChecks },
  { id: 'format', label: 'settings.tabs.format', icon: AlignLeft },
  { id: 'ai', label: 'settings.tabs.ai', icon: Sparkles },
  { id: 'connections', label: 'settings.tabs.connections', icon: Database },
  { id: 'updates', label: 'settings.tabs.updates', icon: RefreshCw }
]

export function SettingsDialog(): React.JSX.Element | null {
  const open = useStore((s) => s.dialogs.settings)
  const tab = useStore((s) => s.dialogs.settingsTab)
  const openDialog = useStore((s) => s.openDialog)
  const closeDialog = useStore((s) => s.closeDialog)
  const settings = useStore((s) => s.settings)
  if (!settings) return null
  const update = (patch: SettingsPatch): void => {
    void api().settings.update(patch)
  }

  return (
    <Dialog open={open} onOpenChange={(o) => (o ? openDialog('settings') : closeDialog('settings'))} title={t('settings.title')} width={760}>
      <Tabs.Root value={tab} onValueChange={(v) => openDialog('settings', v)} orientation="vertical" className="flex min-h-[460px]">
        <Tabs.List className="flex w-40 shrink-0 flex-col gap-0.5 border-r border-line p-2" aria-label={t('settings.sections')}>
          {TABS.map((tab) => (
            <Tabs.Trigger
              key={tab.id}
              value={tab.id}
              className="group flex h-8 items-center gap-2 rounded-[5px] px-2.5 text-left text-sm text-ink-muted hover:bg-surface-hover hover:text-ink data-[state=active]:bg-amber-wash data-[state=active]:text-ink data-[state=active]:shadow-[inset_2px_0_0_var(--amber)]"
            >
              <tab.icon size={16} strokeWidth={1.75} aria-hidden className="shrink-0 group-data-[state=active]:text-amber-ink" />
              <span className="min-w-0 truncate">{t(tab.label)}</span>
            </Tabs.Trigger>
          ))}
        </Tabs.List>
        <div className="min-w-0 flex-1 px-6 py-3">
          <Tabs.Content value="format">
            <FormatSettings settings={settings} update={update} />
          </Tabs.Content>
          <Tabs.Content value="general">
            <Field label={t('settings.general.language')} hint={t('settings.general.languageHint')}>
              <Select
                label={t('settings.general.language')}
                value={settings.language}
                onChange={(v) => update({ language: resolveLanguage(v) })}
                options={LANGUAGES.map((l) => ({
                  value: l,
                  label: l === 'en' ? t('settings.general.languageDefault', { name: CATALOGS[l].meta.nativeName }) : CATALOGS[l].meta.nativeName
                }))}
                className="w-56"
              />
            </Field>
            <Field label={t('settings.general.theme')}>
              <Select
                label={t('settings.general.theme')}
                value={settings.theme}
                onChange={(v) => update({ theme: v as ThemeId })}
                options={[
                  { value: 'system', label: t('settings.general.themeSystem') },
                  { value: 'amber-light', label: t('app.themes.amberLight') },
                  { value: 'amber-dark', label: t('app.themes.amberDark') },
                  { value: 'resin', label: t('settings.general.themeResin') },
                  { value: 'high-contrast', label: t('app.themes.highContrast') }
                ]}
                className="w-64"
              />
            </Field>
            <Field label={t('settings.general.fontSize')} hint={t('settings.general.fontSizeHint')}>
              <Select
                label={t('settings.general.fontSize')}
                value={String(settings.fontSize)}
                onChange={(v) => update({ fontSize: Number(v) })}
                options={[11, 12, 13, 14, 15, 16, 18].map((n) => ({ value: String(n), label: t('settings.general.px', { n }) }))}
                className="w-28"
              />
            </Field>
            <div className="mt-4 border-t border-line pt-3">
              <Button variant="ghost" onClick={() => openDialog('about')}>
                {t('settings.general.about')}
              </Button>
            </div>
          </Tabs.Content>

          <Tabs.Content value="analysis">
            <Field label={t('settings.analysis.dialect')} hint={t('settings.analysis.dialectHint')}>
              <Select label={t('settings.analysis.dialect')} value={settings.defaultDialect} onChange={(v) => update({ defaultDialect: v })} options={dialectOptions()} className="w-48" />
            </Field>
            <Field label={t('settings.analysis.encoding')} hint={t('settings.analysis.encodingHint')}>
              <Select
                label={t('settings.analysis.encoding')}
                value={settings.encoding}
                onChange={(v) => update({ encoding: v })}
                options={[
                  { value: 'auto', label: t('settings.analysis.encodingAuto') },
                  { value: 'UTF-8', label: 'UTF-8' },
                  { value: 'GB18030', label: 'GB18030' },
                  { value: 'GBK', label: 'GBK' },
                  { value: 'Big5', label: 'Big5' },
                  { value: 'Shift_JIS', label: 'Shift_JIS' },
                  { value: 'EUC-KR', label: 'EUC-KR' },
                  { value: 'windows-1252', label: 'Windows-1252' },
                  { value: 'ISO-8859-1', label: 'ISO-8859-1' }
                ]}
                className="w-56"
              />
            </Field>
            <Field
              label={t('settings.analysis.fallback')}
              hint={t('settings.analysis.fallbackHint')}
            >
              <Switch checked={settings.allowFallback} onChange={(v) => update({ allowFallback: v })} label={t('settings.analysis.fallback')} />
            </Field>
          </Tabs.Content>

          <Tabs.Content value="ai">
            <AiSettings settings={settings} update={update} />
          </Tabs.Content>

          <Tabs.Content value="connections">
            <ConnectionsSettings settings={settings} />
          </Tabs.Content>

          <Tabs.Content value="rules">
            <RulesSettings settings={settings} update={update} />
          </Tabs.Content>

          <Tabs.Content value="updates">
            <UpdateSettings settings={settings} update={update} />
          </Tabs.Content>
        </div>
      </Tabs.Root>
    </Dialog>
  )
}

function connectedMessage(info: ServerInfo): string {
  const server = [info.product, info.version].filter(Boolean).join(' ')
  const head = server && info.user
    ? t('settings.connections.connectedToAs', { server, user: info.user })
    : server
      ? t('settings.connections.connectedTo', { server })
      : info.user
        ? t('settings.connections.connectedAs', { user: info.user })
        : t('settings.connections.connected')
  return `${head} ${info.readOnly ? t('settings.connections.readOnly') : t('settings.connections.canWrite')}`
}

function TestResult({ result }: { result: { ok: boolean; message: string } | null }): React.JSX.Element | null {
  if (!result) return null
  return <span className={result.ok ? 'text-sm text-ink' : 'text-sm text-risk-high'}>{result.message}</span>
}

function AiSettings({ settings, update }: { settings: Settings; update: (p: SettingsPatch) => void }): React.JSX.Element {
  const { ollama, openai } = settings.ai
  const [models, setModels] = useState<string[] | null>(null)
  const [loadingModels, setLoadingModels] = useState(false)
  const [modelsError, setModelsError] = useState<string | null>(null)
  const [apiModels, setApiModels] = useState<string[] | null>(null)
  const [loadingApiModels, setLoadingApiModels] = useState(false)
  const [apiModelsError, setApiModelsError] = useState<string | null>(null)
  const [ollamaUrl, setOllamaUrl] = useState(ollama.baseUrl)
  const [openaiUrl, setOpenaiUrl] = useState(openai.baseUrl)
  const [apiKey, setApiKey] = useState('')
  const [ollamaKey, setOllamaKey] = useState('')
  const [testing, setTesting] = useState<AiProviderId | null>(null)
  const [results, setResults] = useState<Record<string, { ok: boolean; message: string } | null>>({})

  const loadModels = async (url = ollamaUrl): Promise<void> => {
    setLoadingModels(true)
    setModelsError(null)
    const r = await api().ai.listOllamaModels(url)
    setLoadingModels(false)
    if (r.ok) setModels(r.value)
    else setModelsError(t('settings.ai.ollamaUnreachable', { url, reason: r.error.message }))
  }

  const loadApiModels = async (): Promise<void> => {
    setLoadingApiModels(true)
    setApiModelsError(null)
    const r = await api().ai.listOpenAiModels(openaiUrl)
    setLoadingApiModels(false)
    if (r.ok) {
      setApiModels(r.value)
      if (!r.value.length) setApiModelsError(t('settings.ai.noChatModel'))
    } else setApiModelsError(r.error.message)
  }

  // Installed Ollama models load by themselves when this tab opens.
  const initialOllamaUrl = useRef(ollama.baseUrl)
  useEffect(() => {
    if (initialOllamaUrl.current) void loadModels(initialOllamaUrl.current)
    // Only on opening the tab; Load models refreshes later.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const ollamaOptions = useMemo(() => modelOptions(OLLAMA_PRESETS, models), [models])
  const apiOptions = useMemo(() => modelOptions(OPENAI_PRESETS, apiModels, isOpenAiHost(openaiUrl)), [apiModels, openaiUrl])

  const test = async (provider: AiProviderId): Promise<void> => {
    setTesting(provider)
    const r = await api().ai.test(provider)
    setTesting(null)
    setResults({ ...results, [provider]: r.ok ? { ok: true, message: r.value } : { ok: false, message: r.error.message } })
  }

  return (
    <div className="space-y-5">
      <p className="text-sm text-ink-muted">
        {t('settings.ai.lead')}
      </p>
      <Field label={t('settings.ai.use')}>
        <Select
          label={t('settings.ai.provider')}
          value={settings.activeAiProvider ?? 'off'}
          onChange={(v) => update({ activeAiProvider: v === 'off' ? null : (v as AiProviderId) })}
          options={[
            { value: 'off', label: t('settings.ai.off') },
            { value: 'ollama', label: t('settings.ai.ollamaLocal') },
            { value: 'openai', label: t('settings.ai.openai') }
          ]}
          className="w-64"
        />
      </Field>

      <fieldset className="rounded-[6px] border border-line px-4 py-2">
        <legend className="px-1 text-sm font-semibold">Ollama</legend>
        <Field label={t('settings.ai.baseUrl')} htmlFor="ollama-url">
          <input
            id="ollama-url"
            className={inputClass}
            value={ollamaUrl}
            onChange={(e) => setOllamaUrl(e.target.value)}
            onBlur={() => update({ ai: { ollama: { baseUrl: ollamaUrl } } })}
          />
        </Field>
        <Field
          label={t('settings.ai.model')}
          hint={
            modelsError ? (
              <span className="text-risk-high">{modelsError}</span>
            ) : models ? (
              t('settings.ai.installedCount', { count: models.length })
            ) : (
              t('settings.ai.ollamaHint')
            )
          }
        >
          <ModelCombobox
            label={t('settings.ai.ollamaModel')}
            value={ollama.model}
            onCommit={(m) => update({ ai: { ollama: { model: m } } })}
            options={ollamaOptions}
            availableWord={t('settings.ai.installed')}
            missingHint={(id) => t('settings.ai.notInstalled', { id })}
            loading={loadingModels}
            onLoad={() => void loadModels()}
            placeholder={t('settings.ai.example', { model: 'qwen2.5-coder:7b' })}
          />
        </Field>
        <Field label={t('settings.ai.apiKey')} hint={t('settings.ai.ollamaKeyHint')}>
          <div className="flex gap-2">
            <input
              type="password"
              className={inputClass}
              placeholder={ollama.hasApiKey ? t('settings.ai.keySaved') : t('common.none')}
              value={ollamaKey}
              autoComplete="off"
              aria-label={t('settings.ai.ollamaKey')}
              onChange={(e) => setOllamaKey(e.target.value)}
            />
            <Button
              disabled={!ollamaKey}
              onClick={() =>
                void api()
                  .settings.setAiApiKey('ollama', ollamaKey)
                  .then(() => setOllamaKey(''))
                  .catch((e: Error) => setResults({ ...results, ollama: { ok: false, message: e.message } }))
              }
            >
              {t('settings.ai.saveKey')}
            </Button>
            {ollama.hasApiKey ? (
              <Button variant="danger" onClick={() => void api().settings.setAiApiKey('ollama', null)}>
                {t('common.remove')}
              </Button>
            ) : null}
          </div>
        </Field>
        <Field label={t('settings.ai.mask')} hint={t('settings.ai.maskHint')}>
          <Switch checked={ollama.maskIdentifiers} onChange={(v) => update({ ai: { ollama: { maskIdentifiers: v } } })} label={t('settings.ai.maskOllama')} />
        </Field>
        <div className="flex items-center gap-3 py-2">
          <Button onClick={() => void test('ollama')} disabled={testing !== null}>
            {testing === 'ollama' ? <Spinner size={12} /> : null} {t('settings.ai.test')}
          </Button>
          <TestResult result={results.ollama ?? null} />
        </div>
      </fieldset>

      <fieldset className="rounded-[6px] border border-line px-4 py-2">
        <legend className="px-1 text-sm font-semibold">{t('settings.ai.openai')}</legend>
        <Field label={t('settings.ai.baseUrl')} htmlFor="openai-url">
          <input
            id="openai-url"
            className={inputClass}
            value={openaiUrl}
            onChange={(e) => setOpenaiUrl(e.target.value)}
            onBlur={() => update({ ai: { openai: { baseUrl: openaiUrl } } })}
          />
        </Field>
        <Field label={t('settings.ai.apiKey')} hint={t('settings.ai.openaiKeyHint')}>
          <div className="flex gap-2">
            <input
              type="password"
              className={inputClass}
              placeholder={openai.hasApiKey ? t('settings.ai.keySaved') : t('settings.ai.pasteKey')}
              value={apiKey}
              autoComplete="off"
              onChange={(e) => setApiKey(e.target.value)}
            />
            <Button
              disabled={!apiKey}
              onClick={() =>
                void api()
                  .settings.setAiApiKey('openai', apiKey)
                  .then(() => setApiKey(''))
                  .catch((e: Error) => setResults({ ...results, openai: { ok: false, message: e.message } }))
              }
            >
              {t('settings.ai.saveKey')}
            </Button>
            {openai.hasApiKey ? (
              <Button variant="danger" onClick={() => void api().settings.setAiApiKey('openai', null)}>
                {t('common.remove')}
              </Button>
            ) : null}
          </div>
        </Field>
        <Field
          label={t('settings.ai.model')}
          hint={
            apiModelsError ? (
              <span className="text-risk-high">{apiModelsError}</span>
            ) : apiModels ? (
              t('settings.ai.offeredCount', { count: apiModels.length })
            ) : (
              t('settings.ai.modelNamesHint')
            )
          }
        >
          <ModelCombobox
            label={t('settings.ai.apiModel')}
            value={openai.model}
            onCommit={(m) => update({ ai: { openai: { model: m } } })}
            options={apiOptions}
            availableWord={t('settings.ai.available')}
            missingHint={() => t('settings.ai.notOffered')}
            loading={loadingApiModels}
            onLoad={() => void loadApiModels()}
            placeholder={isOpenAiHost(openaiUrl) ? t('settings.ai.example', { model: 'gpt-6-luna' }) : t('settings.ai.providerModel')}
          />
        </Field>
        <Field label={t('settings.ai.mask')} hint={t('settings.ai.maskApiHint')}>
          <Switch checked={openai.maskIdentifiers} onChange={(v) => update({ ai: { openai: { maskIdentifiers: v } } })} label={t('settings.ai.maskApi')} />
        </Field>
        <div className="flex items-center gap-3 py-2">
          <Button onClick={() => void test('openai')} disabled={testing !== null}>
            {testing === 'openai' ? <Spinner size={12} /> : null} {t('settings.ai.test')}
          </Button>
          <TestResult result={results.openai ?? null} />
        </div>
      </fieldset>
    </div>
  )
}

const EMPTY_PROFILE = (): Omit<ConnectionProfile, 'hasPassword'> => ({
  id: crypto.randomUUID(),
  name: '',
  dialect: 'postgresql',
  host: 'localhost',
  port: 5432,
  database: '',
  user: '',
  url: ''
})

const DEFAULT_PORTS: Record<string, number> = { oracle: 1521, sqlserver: 1433, postgresql: 5432, mysql: 3306, mariadb: 3306, db2: 50000, h2: 9092, sqlite: 0 }

function ConnectionsSettings({ settings }: { settings: Settings }): React.JSX.Element {
  const [editing, setEditing] = useState<Omit<ConnectionProfile, 'hasPassword'> | null>(null)
  const [password, setPassword] = useState('')
  const [results, setResults] = useState<Record<string, { ok: boolean; message: string }>>({})
  const [testing, setTesting] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const drivers = useDriverStatuses()

  const save = async (): Promise<void> => {
    if (!editing) return
    try {
      await api().settings.saveConnection({ ...editing, name: editing.name || `${editing.user}@${editing.host}` }, password ? password : undefined)
      setEditing(null)
      setPassword('')
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const browseDriver = async (): Promise<void> => {
    const [path] = await api().files.pickFiles({
      title: t('settings.connections.driverPickTitle'),
      filters: [{ name: t('settings.connections.driverFilter'), extensions: ['jar'] }],
      multiple: false
    })
    if (path) setEditing((e) => (e ? { ...e, driverPath: path } : e))
  }

  const test = async (id: string): Promise<void> => {
    setTesting(id)
    const r = await api().db.test(id)
    setTesting(null)
    setResults((all) => ({
      ...all,
      [id]: r.ok ? { ok: true, message: connectedMessage(r.value) } : { ok: false, message: describeError(r.error) }
    }))
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-ink-muted">
        {t('settings.connections.lead')}
      </p>
      {settings.connections.length === 0 && !editing ? <p className="text-sm text-ink-muted">{t('settings.connections.none')}</p> : null}
      <ul className="divide-y divide-line rounded-[6px] border border-line empty:hidden">
        {settings.connections.map((c) => (
          <li key={c.id} className="px-3 py-2">
            <div className="flex items-center gap-3">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5 text-sm font-medium">
                  <DbIcon dialect={c.dialect} size={16} />
                  {c.name}
                </div>
                <div className="code truncate text-[12px] text-ink-muted">
                  {dialectName(c.dialect)} · {c.url ? c.url : `${c.user}@${c.host}:${c.port}/${c.database}`} {c.hasPassword ? `· ${t('settings.connections.passwordSaved')}` : ''}
                </div>
              </div>
              <Button size="sm" onClick={() => void test(c.id)} disabled={testing !== null}>
                {testing === c.id ? <Spinner size={11} /> : null} {t('settings.connections.test')}
              </Button>
              <Button size="sm" onClick={() => setEditing({ id: c.id, name: c.name, dialect: c.dialect, host: c.host, port: c.port, database: c.database, user: c.user, url: c.url ?? '', driverPath: c.driverPath ?? '' })}>
                {t('common.edit')}
              </Button>
              <Button size="sm" variant="danger" aria-label={t('settings.connections.deleteNamed', { name: c.name })} onClick={() => void api().settings.deleteConnection(c.id)}>
                <Trash size={12} />
              </Button>
            </div>
            {results[c.id] ? (
              <div className="mt-2 flex flex-wrap items-start gap-2">
                <TestResult result={results[c.id]!} />
                {!results[c.id]!.ok && needsDriverDownload(drivers, c.dialect, c.driverPath) ? (
                  <Button size="sm" variant="primary" onClick={() => openDriverDownload(c.dialect, () => void test(c.id))}>
                    <Download size={12} /> {t('settings.drivers.downloadDriver')}
                  </Button>
                ) : null}
              </div>
            ) : null}
          </li>
        ))}
      </ul>
      {editing ? (
        <div className="rounded-[6px] border border-line px-4 py-2">
          <Field label={t('settings.connections.name')} htmlFor="c-name">
            <input id="c-name" className={inputClass} value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} placeholder={t('settings.connections.namePlaceholder')} />
          </Field>
          <Field label={t('settings.connections.type')}>
            <Select label={t('settings.connections.type')} value={editing.dialect} onChange={(v) => setEditing({ ...editing, dialect: v, port: DEFAULT_PORTS[v] ?? editing.port })} options={dialectOptions()} className="w-48" />
          </Field>
          <Field label={t('settings.connections.hostPort')} htmlFor="c-host">
            <div className="flex gap-2">
              <input id="c-host" className={inputClass} value={editing.host} onChange={(e) => setEditing({ ...editing, host: e.target.value })} />
              <input
                aria-label={t('settings.connections.port')}
                className={`${inputClass} w-24`}
                inputMode="numeric"
                value={editing.port}
                onChange={(e) => setEditing({ ...editing, port: Number(e.target.value.replace(/\D/g, '')) || 0 })}
              />
            </div>
          </Field>
          <Field label={t('settings.connections.database')} htmlFor="c-db">
            <input id="c-db" className={inputClass} value={editing.database} onChange={(e) => setEditing({ ...editing, database: e.target.value })} />
          </Field>
          <Field label={t('settings.connections.url')} htmlFor="c-url" hint={t('settings.connections.urlHint')}>
            <input
              id="c-url"
              className={inputClass}
              value={editing.url ?? ''}
              autoComplete="off"
              spellCheck={false}
              placeholder="jdbc:…"
              onChange={(e) => setEditing({ ...editing, url: e.target.value })}
            />
          </Field>
          <ConnectionDriverField
            dialect={editing.dialect}
            value={editing.driverPath}
            installed={Boolean(drivers[editing.dialect]?.installed)}
            onChange={(driverPath) => setEditing({ ...editing, driverPath })}
            onBrowse={() => void browseDriver()}
            onDownload={() => openDriverDownload(editing.dialect)}
          />
          <Field label={t('settings.connections.user')} htmlFor="c-user">
            <input id="c-user" className={inputClass} value={editing.user} autoComplete="off" onChange={(e) => setEditing({ ...editing, user: e.target.value })} />
          </Field>
          <Field label={t('settings.connections.password')} htmlFor="c-pw" hint={t('settings.connections.passwordHint')}>
            <input id="c-pw" type="password" className={inputClass} value={password} autoComplete="off" onChange={(e) => setPassword(e.target.value)} />
          </Field>
          {error ? <p className="py-1 text-sm text-risk-high">{error}</p> : null}
          <div className="flex justify-end gap-2 py-2">
            <Button variant="ghost" onClick={() => setEditing(null)}>
              {t('common.cancel')}
            </Button>
            <Button variant="primary" onClick={() => void save()}>
              {t('settings.connections.save')}
            </Button>
          </div>
        </div>
      ) : (
        <Button onClick={() => setEditing(EMPTY_PROFILE())}>
          <Plus size={13} /> {t('settings.connections.add')}
        </Button>
      )}
      <DriversSettings settings={settings} />
    </div>
  )
}

/**
 * Settings → Connections → Drivers: every database type with its pinned driver version, whether it is
 * downloaded, and Download (through the license dialog) or Remove; then the repository to download from.
 */
function DriversSettings({ settings }: { settings: Settings }): React.JSX.Element {
  const drivers = useDriverStatuses()
  const [repo, setRepo] = useState(settings.driverRepository)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => setRepo(settings.driverRepository), [settings.driverRepository])
  const repoInvalid = repo.trim() !== '' && normalizeRepositoryUrl(repo) === null
  const effective = Object.values(drivers)[0]?.repository
  const saveRepo = async (): Promise<void> => {
    if (repoInvalid) return
    const value = repo.trim() ? normalizeRepositoryUrl(repo)! : ''
    if (value === settings.driverRepository) return
    await api().settings.update({ driverRepository: value })
    await refreshDrivers()
  }
  const remove = async (id: string): Promise<void> => {
    setBusy(id)
    const r = await api().drivers.remove(id)
    setBusy(null)
    setError(r.ok ? null : describeError(r.error))
    await refreshDrivers()
  }
  return (
    <section className="space-y-2 border-t border-line pt-4" aria-labelledby="drivers-title">
      <h3 id="drivers-title" className="text-sm font-semibold">
        {t('settings.drivers.title')}
      </h3>
      <p className="text-sm text-ink-muted">{t('settings.drivers.lead')}</p>
      <ul className="divide-y divide-line rounded-[6px] border border-line">
        {DRIVERS.map((d) => {
          const installed = Boolean(drivers[d.id]?.installed)
          return (
            <li key={d.id} className="flex items-center gap-3 px-3 py-1.5">
              <DbIcon dialect={d.id} size={16} />
              <div className="min-w-0 flex-1">
                <div className="text-sm text-ink">{dialectName(d.id)}</div>
                <div className="truncate text-xs text-ink-muted" title={`${d.groupId}:${d.artifactId}:${d.version}`}>
                  {d.name} {d.version} · {driverSize(d.size)}
                </div>
              </div>
              {installed ? (
                <span className="inline-flex items-center gap-1 text-xs text-amber-ink">
                  <CircleCheck size={13} aria-hidden /> {t('settings.drivers.installed')}
                </span>
              ) : (
                <span className="text-xs text-ink-muted">{t('settings.drivers.notInstalled')}</span>
              )}
              {installed ? (
                <Button
                  size="sm"
                  variant="danger"
                  disabled={busy !== null}
                  aria-label={t('settings.drivers.removeNamed', { database: dialectName(d.id) })}
                  onClick={() => void remove(d.id)}
                >
                  {busy === d.id ? <Spinner size={11} /> : <Trash size={12} />} {t('settings.drivers.remove')}
                </Button>
              ) : (
                <Button size="sm" aria-label={downloadLabel(d)} onClick={() => openDriverDownload(d.id)}>
                  <Download size={12} /> {t('settings.drivers.download')}
                </Button>
              )}
            </li>
          )
        })}
      </ul>
      {error ? <p className="text-sm text-risk-high">{error}</p> : null}
      <Field
        label={t('settings.drivers.repository')}
        htmlFor="drivers-repo"
        hint={
          repoInvalid ? (
            <span id="drivers-repo-error" role="alert" className="text-risk-high">
              {t('settings.drivers.repositoryInvalid')}
            </span>
          ) : (
            t('settings.drivers.repositoryHint', { url: (settings.driverRepository ? null : effective) ?? 'https://repo1.maven.org/maven2' })
          )
        }
      >
        <input
          id="drivers-repo"
          className={`${inputClass} code ${repoInvalid ? 'border-risk-high' : ''}`}
          value={repo}
          autoComplete="off"
          spellCheck={false}
          placeholder={(settings.driverRepository ? null : effective) ?? 'https://repo1.maven.org/maven2'}
          aria-invalid={repoInvalid}
          aria-describedby={repoInvalid ? 'drivers-repo-error' : undefined}
          onChange={(e) => setRepo(e.target.value)}
          onBlur={() => void saveRepo()}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void saveRepo()
          }}
        />
      </Field>
    </section>
  )
}

function UpdateSettings({ settings, update }: { settings: Settings; update: (p: SettingsPatch) => void }): React.JSX.Element {
  return (
    <div>
      <Field
        label={t('settings.updates.onStart')}
        hint={t('settings.updates.onStartHint')}
      >
        <Switch checked={settings.checkForUpdates} onChange={(v) => update({ checkForUpdates: v })} label={t('settings.updates.onStartLabel')} />
      </Field>
      <Field label={t('settings.updates.now')}>
        <UpdateCheck />
      </Field>
    </div>
  )
}

/** Layout used by Format script / Format selection. Only whitespace and keyword case change. */
function FormatSettings({ settings, update }: { settings: Settings; update: (p: SettingsPatch) => void }): React.JSX.Element {
  const f = settings.format
  const set = (patch: Partial<Settings['format']>): void => update({ format: { ...f, ...patch } })
  const [width, setWidth] = useState(String(f.maxLineWidth))
  return (
    <div>
      <p className="py-2 text-sm text-ink-muted">
        {t('settings.format.lead')}
      </p>
      <Field label={t('settings.format.indent')}>
        <Select
          label={t('settings.format.indentSize')}
          value={String(f.indentSize)}
          onChange={(v) => set({ indentSize: Number(v) })}
          options={[2, 3, 4, 8].map((n) => ({ value: String(n), label: t('settings.format.spaces', { count: n }) }))}
          className="w-40"
        />
      </Field>
      <Field label={t('settings.format.keywords')}>
        <Select
          label={t('settings.format.keywordCase')}
          value={f.keywordCase}
          onChange={(v) => set({ keywordCase: v as Settings['format']['keywordCase'] })}
          options={[
            { value: 'UPPER', label: t('settings.format.upper') },
            { value: 'LOWER', label: t('settings.format.lower') },
            { value: 'PRESERVE', label: t('settings.format.asWritten') }
          ]}
          className="w-40"
        />
      </Field>
      <Field label={t('settings.format.width')} htmlFor="fmt-width" hint={t('settings.format.widthHint')}>
        <input
          id="fmt-width"
          className={`${inputClass.replace('w-full', '')} w-24`}
          inputMode="numeric"
          value={width}
          onChange={(e) => setWidth(e.target.value.replace(/\D/g, ''))}
          onBlur={() => {
            const n = Math.min(400, Math.max(40, Number(width) || 100))
            setWidth(String(n))
            set({ maxLineWidth: n })
          }}
        />
      </Field>
      <Field label={t('settings.format.commas')} hint={t('settings.format.commasHint')}>
        <Select
          label={t('settings.format.commaStyle')}
          value={f.commaStyle}
          onChange={(v) => set({ commaStyle: v as Settings['format']['commaStyle'] })}
          options={[
            { value: 'TRAILING', label: t('settings.format.trailing') },
            { value: 'LEADING', label: t('settings.format.leading') }
          ]}
          className="w-64"
        />
      </Field>
    </div>
  )
}
