import * as Tabs from '@radix-ui/react-tabs'
import { Plus, Trash } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { isOpenAiHost, MODEL_NAMES_HINT, modelOptions, OLLAMA_PRESETS, OPENAI_PRESETS } from '@shared/aiModels'
import type { AiProviderId, ConnectionProfile, Settings, SettingsPatch, ThemeId } from '@shared/api'
import type { ServerInfo } from '@shared/protocol'
import { api, describeError } from '@/lib/api'
import { useStore } from '@/store'
import { ModelCombobox } from './ModelCombobox'
import { Button, Dialog, Field, Select, Spinner, Switch, inputClass } from './ui'

const DIALECTS = [
  { value: 'oracle', label: 'Oracle' },
  { value: 'sqlserver', label: 'SQL Server' },
  { value: 'postgresql', label: 'PostgreSQL' },
  { value: 'mysql', label: 'MySQL' },
  { value: 'mariadb', label: 'MariaDB' },
  { value: 'db2', label: 'Db2' },
  { value: 'h2', label: 'H2' },
  { value: 'sqlite', label: 'SQLite' }
]

const TABS = [
  { id: 'general', label: 'General' },
  { id: 'analysis', label: 'Analysis' },
  { id: 'format', label: 'Formatting' },
  { id: 'ai', label: 'AI' },
  { id: 'connections', label: 'Connections' },
  { id: 'updates', label: 'Updates' }
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
    <Dialog open={open} onOpenChange={(o) => (o ? openDialog('settings') : closeDialog('settings'))} title="Settings" width={760}>
      <Tabs.Root value={tab} onValueChange={(t) => openDialog('settings', t)} orientation="vertical" className="flex min-h-[460px]">
        <Tabs.List className="flex w-40 shrink-0 flex-col gap-0.5 border-r border-line p-2" aria-label="Settings sections">
          {TABS.map((t) => (
            <Tabs.Trigger
              key={t.id}
              value={t.id}
              className="h-8 rounded-[5px] px-2.5 text-left text-sm text-ink-muted hover:bg-surface-hover hover:text-ink data-[state=active]:bg-amber-wash data-[state=active]:text-ink data-[state=active]:shadow-[inset_2px_0_0_var(--amber)]"
            >
              {t.label}
            </Tabs.Trigger>
          ))}
        </Tabs.List>
        <div className="min-w-0 flex-1 px-6 py-3">
          <Tabs.Content value="format">
            <FormatSettings settings={settings} update={update} />
          </Tabs.Content>
          <Tabs.Content value="general">
            <Field label="Theme">
              <Select
                label="Theme"
                value={settings.theme}
                onChange={(v) => update({ theme: v as ThemeId })}
                options={[
                  { value: 'system', label: 'System (follows your OS)' },
                  { value: 'amber-light', label: 'Amber Light' },
                  { value: 'amber-dark', label: 'Amber Dark' },
                  { value: 'resin', label: 'Resin (warm dark)' },
                  { value: 'high-contrast', label: 'High Contrast' }
                ]}
                className="w-64"
              />
            </Field>
            <Field label="Code font size" hint="Used by the SQL editor and code frames.">
              <Select
                label="Code font size"
                value={String(settings.fontSize)}
                onChange={(v) => update({ fontSize: Number(v) })}
                options={[11, 12, 13, 14, 15, 16, 18].map((n) => ({ value: String(n), label: `${n} px` }))}
                className="w-28"
              />
            </Field>
          </Tabs.Content>

          <Tabs.Content value="analysis">
            <Field label="Default dialect" hint="Used for new scripts. Change the dialect of an open script from the title bar.">
              <Select label="Default dialect" value={settings.defaultDialect} onChange={(v) => update({ defaultDialect: v })} options={DIALECTS} className="w-48" />
            </Field>
            <Field label="File encoding" hint="Automatic detection reads a byte-order mark, then tries UTF-8, then GB18030.">
              <Select
                label="File encoding"
                value={settings.encoding}
                onChange={(v) => update({ encoding: v })}
                options={[
                  { value: 'auto', label: 'Detect automatically' },
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
              label="Allow fallback parsing"
              hint="Off is strict: a script either parses completely or the analysis stops with precise errors. On keeps going and marks statements it could not fully parse."
            >
              <Switch checked={settings.allowFallback} onChange={(v) => update({ allowFallback: v })} label="Allow fallback parsing" />
            </Field>
          </Tabs.Content>

          <Tabs.Content value="ai">
            <AiSettings settings={settings} update={update} />
          </Tabs.Content>

          <Tabs.Content value="connections">
            <ConnectionsSettings settings={settings} />
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
  return `Connected${server ? ` to ${server}` : ''}${info.user ? ` as ${info.user}` : ''}.${info.readOnly ? ' Read-only.' : ' The account can write; Amber only reads metadata, but a read-only account is safer.'}`
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
    else setModelsError(`Could not reach Ollama at ${url}: ${r.error.message}`)
  }

  const loadApiModels = async (): Promise<void> => {
    setLoadingApiModels(true)
    setApiModelsError(null)
    const r = await api().ai.listOpenAiModels(openaiUrl)
    setLoadingApiModels(false)
    if (r.ok) {
      setApiModels(r.value)
      if (!r.value.length) setApiModelsError('The provider did not return any chat model. Type the model name.')
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
        AI is optional. It only proposes optimizations from Amber's analysis; it never parses, and its SQL is never run. Requests go straight from this
        app to the provider you choose.
      </p>
      <Field label="Use">
        <Select
          label="AI provider"
          value={settings.activeAiProvider ?? 'off'}
          onChange={(v) => update({ activeAiProvider: v === 'off' ? null : (v as AiProviderId) })}
          options={[
            { value: 'off', label: 'Off' },
            { value: 'ollama', label: 'Ollama (local)' },
            { value: 'openai', label: 'OpenAI-compatible API' }
          ]}
          className="w-64"
        />
      </Field>

      <fieldset className="rounded-[6px] border border-line px-4 py-2">
        <legend className="px-1 text-sm font-semibold">Ollama</legend>
        <Field label="Base URL" htmlFor="ollama-url">
          <input
            id="ollama-url"
            className={inputClass}
            value={ollamaUrl}
            onChange={(e) => setOllamaUrl(e.target.value)}
            onBlur={() => update({ ai: { ollama: { baseUrl: ollamaUrl } } })}
          />
        </Field>
        <Field
          label="Model"
          hint={
            modelsError ? (
              <span className="text-risk-high">{modelsError}</span>
            ) : models ? (
              `${models.length} installed on this server. Pick one, or type any model name.`
            ) : (
              'Pick a suggestion or type any model name. Installed models load from the server.'
            )
          }
        >
          <ModelCombobox
            label="Ollama model"
            value={ollama.model}
            onCommit={(m) => update({ ai: { ollama: { model: m } } })}
            options={ollamaOptions}
            availableWord="installed"
            missingHint={(id) => `not installed: run ollama pull ${id}`}
            loading={loadingModels}
            onLoad={() => void loadModels()}
            placeholder="e.g. qwen2.5-coder:7b"
          />
        </Field>
        <Field label="API key" hint="Optional. Only for an Ollama behind a proxy that asks for a bearer token. Stored encrypted.">
          <div className="flex gap-2">
            <input
              type="password"
              className={inputClass}
              placeholder={ollama.hasApiKey ? 'A key is saved' : 'None'}
              value={ollamaKey}
              autoComplete="off"
              aria-label="Ollama API key"
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
              Save key
            </Button>
            {ollama.hasApiKey ? (
              <Button variant="danger" onClick={() => void api().settings.setAiApiKey('ollama', null)}>
                Remove
              </Button>
            ) : null}
          </div>
        </Field>
        <Field label="Mask identifiers" hint="Replace table names, column names and literals with aliases before sending.">
          <Switch checked={ollama.maskIdentifiers} onChange={(v) => update({ ai: { ollama: { maskIdentifiers: v } } })} label="Mask identifiers for Ollama" />
        </Field>
        <div className="flex items-center gap-3 py-2">
          <Button onClick={() => void test('ollama')} disabled={testing !== null}>
            {testing === 'ollama' ? <Spinner size={12} /> : null} Test connection
          </Button>
          <TestResult result={results.ollama ?? null} />
        </div>
      </fieldset>

      <fieldset className="rounded-[6px] border border-line px-4 py-2">
        <legend className="px-1 text-sm font-semibold">OpenAI-compatible API</legend>
        <Field label="Base URL" htmlFor="openai-url">
          <input
            id="openai-url"
            className={inputClass}
            value={openaiUrl}
            onChange={(e) => setOpenaiUrl(e.target.value)}
            onBlur={() => update({ ai: { openai: { baseUrl: openaiUrl } } })}
          />
        </Field>
        <Field label="API key" hint="Encrypted with your system keychain and kept in the app’s main process. It is never shown again.">
          <div className="flex gap-2">
            <input
              type="password"
              className={inputClass}
              placeholder={openai.hasApiKey ? 'A key is saved' : 'Paste a key'}
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
              Save key
            </Button>
            {openai.hasApiKey ? (
              <Button variant="danger" onClick={() => void api().settings.setAiApiKey('openai', null)}>
                Remove
              </Button>
            ) : null}
          </div>
        </Field>
        <Field
          label="Model"
          hint={
            apiModelsError ? (
              <span className="text-risk-high">{apiModelsError}</span>
            ) : apiModels ? (
              `${apiModels.length} chat models offered by this provider. You can also type any name.`
            ) : (
              MODEL_NAMES_HINT
            )
          }
        >
          <ModelCombobox
            label="API model"
            value={openai.model}
            onCommit={(m) => update({ ai: { openai: { model: m } } })}
            options={apiOptions}
            availableWord="available"
            missingHint={() => 'not offered by this provider'}
            loading={loadingApiModels}
            onLoad={() => void loadApiModels()}
            placeholder={isOpenAiHost(openaiUrl) ? 'e.g. gpt-4.1-mini' : 'the provider’s model name'}
          />
        </Field>
        <Field label="Mask identifiers" hint="On by default for external providers.">
          <Switch checked={openai.maskIdentifiers} onChange={(v) => update({ ai: { openai: { maskIdentifiers: v } } })} label="Mask identifiers for the API" />
        </Field>
        <div className="flex items-center gap-3 py-2">
          <Button onClick={() => void test('openai')} disabled={testing !== null}>
            {testing === 'openai' ? <Spinner size={12} /> : null} Test connection
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

  const test = async (id: string): Promise<void> => {
    setTesting(id)
    const r = await api().db.test(id)
    setTesting(null)
    setResults({
      ...results,
      [id]: r.ok ? { ok: true, message: connectedMessage(r.value) } : { ok: false, message: describeError(r.error) }
    })
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-ink-muted">
        Online mode reads table structure, keys and row estimates through a read-only connection. Amber never sends your script to a database. Use a
        read-only account.
      </p>
      {settings.connections.length === 0 && !editing ? <p className="text-sm text-ink-muted">No connections yet.</p> : null}
      <ul className="divide-y divide-line rounded-[6px] border border-line empty:hidden">
        {settings.connections.map((c) => (
          <li key={c.id} className="px-3 py-2">
            <div className="flex items-center gap-3">
              <div className="min-w-0 flex-1">
                <div className="text-sm font-medium">{c.name}</div>
                <div className="code truncate text-[12px] text-ink-muted">
                  {c.dialect} · {c.url ? c.url : `${c.user}@${c.host}:${c.port}/${c.database}`} {c.hasPassword ? '· password saved' : ''}
                </div>
              </div>
              <Button size="sm" onClick={() => void test(c.id)} disabled={testing !== null}>
                {testing === c.id ? <Spinner size={11} /> : null} Test
              </Button>
              <Button size="sm" onClick={() => setEditing({ id: c.id, name: c.name, dialect: c.dialect, host: c.host, port: c.port, database: c.database, user: c.user, url: c.url ?? '' })}>
                Edit
              </Button>
              <Button size="sm" variant="danger" aria-label={`Delete ${c.name}`} onClick={() => void api().settings.deleteConnection(c.id)}>
                <Trash size={12} />
              </Button>
            </div>
            {results[c.id] ? (
              <div className="mt-2">
                <TestResult result={results[c.id]!} />
              </div>
            ) : null}
          </li>
        ))}
      </ul>
      {editing ? (
        <div className="rounded-[6px] border border-line px-4 py-2">
          <Field label="Name" htmlFor="c-name">
            <input id="c-name" className={inputClass} value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} placeholder="Warehouse (read-only)" />
          </Field>
          <Field label="Database type">
            <Select label="Database type" value={editing.dialect} onChange={(v) => setEditing({ ...editing, dialect: v, port: DEFAULT_PORTS[v] ?? editing.port })} options={DIALECTS} className="w-48" />
          </Field>
          <Field label="Host and port" htmlFor="c-host">
            <div className="flex gap-2">
              <input id="c-host" className={inputClass} value={editing.host} onChange={(e) => setEditing({ ...editing, host: e.target.value })} />
              <input
                aria-label="Port"
                className={`${inputClass} w-24`}
                inputMode="numeric"
                value={editing.port}
                onChange={(e) => setEditing({ ...editing, port: Number(e.target.value.replace(/\D/g, '')) || 0 })}
              />
            </div>
          </Field>
          <Field label="Database" htmlFor="c-db">
            <input id="c-db" className={inputClass} value={editing.database} onChange={(e) => setEditing({ ...editing, database: e.target.value })} />
          </Field>
          <Field label="JDBC URL" htmlFor="c-url" hint="Optional. When set, it is used instead of host, port and database, e.g. jdbc:sqlite:/path/to/file.db.">
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
          <Field label="User" htmlFor="c-user">
            <input id="c-user" className={inputClass} value={editing.user} autoComplete="off" onChange={(e) => setEditing({ ...editing, user: e.target.value })} />
          </Field>
          <Field label="Password" htmlFor="c-pw" hint="Encrypted with your system keychain. Leave empty to keep the saved one.">
            <input id="c-pw" type="password" className={inputClass} value={password} autoComplete="off" onChange={(e) => setPassword(e.target.value)} />
          </Field>
          {error ? <p className="py-1 text-sm text-risk-high">{error}</p> : null}
          <div className="flex justify-end gap-2 py-2">
            <Button variant="ghost" onClick={() => setEditing(null)}>
              Cancel
            </Button>
            <Button variant="primary" onClick={() => void save()}>
              Save connection
            </Button>
          </div>
        </div>
      ) : (
        <Button onClick={() => setEditing(EMPTY_PROFILE())}>
          <Plus size={13} /> Add connection
        </Button>
      )}
    </div>
  )
}

function UpdateSettings({ settings, update }: { settings: Settings; update: (p: SettingsPatch) => void }): React.JSX.Element {
  const [state, setState] = useState<{ checking: boolean; message: string | null; url?: string }>({ checking: false, message: null })
  useEffect(() => setState({ checking: false, message: null }), [settings.checkForUpdates])
  const check = async (): Promise<void> => {
    setState({ checking: true, message: null })
    const r = await api().app.checkForUpdates(true)
    if (!r.ok) setState({ checking: false, message: `Could not check: ${r.error.message}` })
    else if (r.value?.newer) setState({ checking: false, message: `Amber ${r.value.latest} is available.`, url: r.value.url })
    else setState({ checking: false, message: `You have the latest version (${r.value?.current ?? ''}).` })
  }
  return (
    <div>
      <Field
        label="Check on start-up"
        hint="Asks GitHub for the latest release when Amber starts. Nothing else is sent. Turn this off on networks without internet access."
      >
        <Switch checked={settings.checkForUpdates} onChange={(v) => update({ checkForUpdates: v })} label="Check for updates on start-up" />
      </Field>
      <Field label="Now">
        <div className="flex items-center gap-3">
          <Button onClick={() => void check()} disabled={state.checking}>
            {state.checking ? <Spinner size={12} /> : null} Check for updates
          </Button>
          {state.message ? <span className="text-sm">{state.message}</span> : null}
          {state.url ? (
            <Button size="sm" variant="primary" onClick={() => void api().app.openExternal(state.url!)}>
              Open download page
            </Button>
          ) : null}
        </div>
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
        Format script (Shift+Alt+F) and Format selection make a legacy script readable again. They change only whitespace and keyword case; a statement
        whose tokens could not be kept exactly is left as written. The result goes into the editor as one undoable step; the file is saved only by you.
      </p>
      <Field label="Indent">
        <Select
          label="Indent size"
          value={String(f.indentSize)}
          onChange={(v) => set({ indentSize: Number(v) })}
          options={[2, 3, 4, 8].map((n) => ({ value: String(n), label: `${n} spaces` }))}
          className="w-40"
        />
      </Field>
      <Field label="Keywords">
        <Select
          label="Keyword case"
          value={f.keywordCase}
          onChange={(v) => set({ keywordCase: v as Settings['format']['keywordCase'] })}
          options={[
            { value: 'UPPER', label: 'UPPER CASE' },
            { value: 'LOWER', label: 'lower case' },
            { value: 'PRESERVE', label: 'As written' }
          ]}
          className="w-40"
        />
      </Field>
      <Field label="Line width" htmlFor="fmt-width" hint="Longer lines are wrapped where the SQL allows it.">
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
      <Field label="Commas" hint="Where the comma goes when a list is split over several lines.">
        <Select
          label="Comma style"
          value={f.commaStyle}
          onChange={(v) => set({ commaStyle: v as Settings['format']['commaStyle'] })}
          options={[
            { value: 'TRAILING', label: 'At the end of the line' },
            { value: 'LEADING', label: 'At the start of the next line' }
          ]}
          className="w-64"
        />
      </Field>
    </div>
  )
}
