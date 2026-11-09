import { readFile, stat, writeFile } from 'node:fs/promises'
import { basename, join } from 'node:path'
import { app, dialog, ipcMain, nativeTheme, net, shell, type BrowserWindow } from 'electron'
import type {
  AiMessage,
  AiProviderId,
  AppInfo,
  ConnectionProfile,
  DriverProgress,
  DriverStatus,
  LoadMetadataRequest,
  OpenedFile,
  PickFilesRequest,
  Result,
  SaveRequest,
  Settings,
  SettingsPatch,
  WireResult
} from '../shared/api'
import { RPC_ERROR, type AnalyzeParams, type EngineMethod } from '../shared/protocol'
import { artifactUrl, driverById, driverForDialect, DRIVERS, type DriverArtifact } from '../shared/drivers'
import { resolveDataSources, toDataSource, type DownloadedDriver } from './db/datasource'
import {
  DriverDownloadError,
  downloadDriver,
  effectiveRepository,
  installedDriver,
  installedDriverSync,
  isRequestId,
  removeDriver
} from './drivers/download'
import { listOllamaModels, listOpenAiModels, streamChat, testProvider, type ProviderConfig } from './ai/gateway'
import type { EngineSupervisor } from './engine/supervisor'
import { RpcError, errorMessage, toRpcError } from './engine/rpc-client'
import { decodeScript } from './files/decode'
import { normalizeRules, type SettingsStore } from './settings/store'
import type { AppConfig } from './config'
import { checkLatestRelease, projectLinks } from './updates/check'
import { tm } from './i18n'

const KNOWN_METHODS: EngineMethod[] = [
  'engine.info',
  'engine.rules',
  'er.model',
  'analyze',
  'lineage',
  'impact',
  'findings',
  'diff',
  'rewrite.suggest',
  'rewrite.optimize',
  'format',
  'export',
  'metadata.import',
  'metadata.export',
  'db.test',
  'db.loadMetadata',
  'ai.buildContext',
  'ai.check',
  'ai.maskText',
  'references',
  'doc.generate',
  'rewrite.apply'
]

function ok<T>(value: T): Result<T> {
  return { ok: true, value }
}

/** Engine answers go to the page as JSON text (see WireResult). */
function toWire<T>(result: Result<T>): WireResult {
  return result.ok ? { ok: true, json: JSON.stringify(result.value ?? null) } : result
}

function fail(error: unknown): Result<never> {
  return { ok: false, error: toRpcError(error).toObject() }
}

export interface IpcContext {
  config: AppConfig
  engine: EngineSupervisor
  settings: SettingsStore
  getWindow: () => BrowserWindow | null
  applyTheme: (settings: Settings) => void
  /** Switches main-process messages and rebuilds the native menu when the language changes. */
  applyLanguage: (settings: Settings) => void
}

export function registerIpc(ctx: IpcContext): void {
  const { engine, settings } = ctx
  const engineRequests = new Map<string, AbortController>()
  const aiRequests = new Map<string, AbortController>()
  const driverRequests = new Map<string, AbortController>()
  // Downloaded JDBC drivers, one folder per dialect. userData already honours AMBER_DEV_USER_DATA.
  const driversRoot = join(app.getPath('userData'), 'drivers')
  const downloaded: DownloadedDriver = (dialect) => {
    const d = driverForDialect(dialect)
    return d ? installedDriverSync(driversRoot, d) : null
  }

  const broadcastSettings = (value: Settings): Settings => {
    ctx.applyTheme(value)
    ctx.applyLanguage(value)
    ctx.getWindow()?.webContents.send('settings:changed', value)
    return value
  }

  const runEngine = async <T>(method: string, params: unknown, requestId?: string, timeoutMs?: number): Promise<Result<T>> => {
    if (!KNOWN_METHODS.includes(method as EngineMethod)) {
      return fail(new RpcError(RPC_ERROR.INVALID_REQUEST, tm('main.engine.unknownMethod', { method })))
    }
    const controller = new AbortController()
    if (requestId) engineRequests.set(requestId, controller)
    try {
      return ok(await engine.request<T>(method, params, { signal: controller.signal, timeoutMs }))
    } catch (error) {
      if (timeoutMs && error instanceof RpcError && error.code === RPC_ERROR.TIMEOUT) {
        return fail(new RpcError(RPC_ERROR.TIMEOUT, tm('main.db.timeout', { seconds: Math.round(timeoutMs / 1000) })))
      }
      return fail(error)
    } finally {
      if (requestId) engineRequests.delete(requestId)
    }
  }

  // Engine
  ipcMain.handle('engine:status', () => engine.getStatus())
  ipcMain.handle('engine:analyze', async (_e, params: AnalyzeParams, requestId: string) =>
    toWire(await runEngine('analyze', sanitizeAnalyze(params), requestId))
  )
  ipcMain.handle('engine:call', async (_e, method: string, params: unknown, requestId?: string) =>
    toWire(await runEngine(method, params, requestId))
  )
  ipcMain.handle('engine:cancel', (_e, requestId: string) => {
    engineRequests.get(requestId)?.abort()
  })
  ipcMain.handle('engine:restart', () => engine.restart().catch(() => undefined))

  // Files
  const openPath = async (path: string): Promise<OpenedFile> => {
    const info = await stat(path)
    if (!info.isFile()) throw new Error(tm('main.files.notAFile'))
    if (info.size > ctx.config.maxFileMb * 1024 * 1024) throw new Error(tm('main.files.tooLarge', { mb: ctx.config.maxFileMb }))
    const bytes = await readFile(path)
    const decoded = decodeScript(bytes, settings.get().encoding)
    broadcastSettings(settings.addRecentFile(path))
    return { path, name: basename(path), text: decoded.text, encoding: decoded.encoding }
  }

  ipcMain.handle('files:openDialog', async () => {
    const win = ctx.getWindow()
    const options: Electron.OpenDialogOptions = {
      title: tm('main.files.openTitle'),
      properties: ['openFile'],
      filters: [
        { name: tm('main.files.sqlScripts'), extensions: ['sql', 'pls', 'pkb', 'pks', 'prc', 'fnc', 'trg', 'ddl', 'dml', 'txt'] },
        { name: tm('main.files.allFiles'), extensions: ['*'] }
      ]
    }
    const result = win ? await dialog.showOpenDialog(win, options) : await dialog.showOpenDialog(options)
    const path = result.filePaths[0]
    if (result.canceled || !path) return null
    return openPath(path)
  })
  ipcMain.handle('files:open', async (_e, path: string) => {
    try {
      return ok(await openPath(String(path)))
    } catch (error) {
      return fail(error)
    }
  })
  ipcMain.handle('files:save', async (_e, request: SaveRequest) => {
    const win = ctx.getWindow()
    const options: Electron.SaveDialogOptions = { defaultPath: request.defaultName, filters: request.filters }
    const result = win ? await dialog.showSaveDialog(win, options) : await dialog.showSaveDialog(options)
    if (result.canceled || !result.filePath) return null
    const data = request.encoding === 'base64' ? Buffer.from(request.data, 'base64') : request.data
    await writeFile(result.filePath, data)
    return result.filePath
  })

  ipcMain.handle('files:pickFiles', async (_e, request: PickFilesRequest) => {
    const win = ctx.getWindow()
    const options: Electron.OpenDialogOptions = {
      title: String(request?.title ?? tm('main.files.chooseFiles')),
      properties: request?.multiple === false ? ['openFile'] : ['openFile', 'multiSelections'],
      filters: Array.isArray(request?.filters) ? request.filters : undefined
    }
    const result = win ? await dialog.showOpenDialog(win, options) : await dialog.showOpenDialog(options)
    return result.canceled ? [] : result.filePaths
  })

  // Settings
  ipcMain.handle('settings:get', () => settings.get())
  ipcMain.handle('settings:update', (_e, patch: SettingsPatch) => broadcastSettings(settings.update(patch)))
  ipcMain.handle('settings:setAiApiKey', (_e, provider: AiProviderId, key: string | null) =>
    settings.setAiKey(provider, key).then(broadcastSettings)
  )
  ipcMain.handle('settings:clearRecentFiles', () => broadcastSettings(settings.clearRecentFiles()))
  ipcMain.handle(
    'settings:saveConnection',
    (_e, profile: Omit<ConnectionProfile, 'hasPassword'>, password?: string | null) =>
      settings.saveConnection(profile, password).then(broadcastSettings)
  )
  ipcMain.handle('settings:deleteConnection', (_e, id: string) => broadcastSettings(settings.deleteConnection(id)))

  // AI (all provider traffic happens here, in the main process)
  const providerConfig = async (provider: AiProviderId): Promise<ProviderConfig> => {
    const s = settings.get().ai
    if (provider === 'ollama') return { kind: 'ollama', ...s.ollama, apiKey: await settings.getAiKey('ollama') }
    return { kind: 'openai', ...s.openai, apiKey: await settings.getAiKey('openai') }
  }
  ipcMain.handle('ai:listOllamaModels', async (_e, baseUrl: string) => {
    try {
      return ok(await listOllamaModels(fetch, baseUrl, 15, await settings.getAiKey('ollama')))
    } catch (error) {
      return fail(error)
    }
  })
  // The key stays here: the renderer only learns the model names.
  ipcMain.handle('ai:listOpenAiModels', async (_e, baseUrl: string) => {
    try {
      return ok(await listOpenAiModels(fetch, String(baseUrl), await settings.getAiKey('openai')))
    } catch (error) {
      return fail(error)
    }
  })
  ipcMain.handle('ai:test', async (_e, provider: AiProviderId) => {
    try {
      return ok(await testProvider(fetch, await providerConfig(provider)))
    } catch (error) {
      return fail(error)
    }
  })
  ipcMain.handle('ai:chat', async (event, provider: AiProviderId, messages: AiMessage[], requestId: string) => {
    const controller = new AbortController()
    aiRequests.set(requestId, controller)
    const send = (payload: unknown): void => {
      if (!event.sender.isDestroyed()) event.sender.send('ai:stream', payload)
    }
    try {
      // The whole answer is also returned: stream events and the invoke reply travel on different IPC
      // channels, so the last fragments may arrive after the reply; the reply is authoritative.
      let full = ''
      await streamChat(fetch, await providerConfig(provider), messages, (text) => {
        full += text
        send({ requestId, type: 'delta', text })
      }, controller.signal)
      send({ requestId, type: 'done' })
      return ok(full)
    } catch (error) {
      const message = controller.signal.aborted ? tm('main.ai.cancelled') : errorMessage(error)
      send({ requestId, type: 'error', message })
      return fail(new Error(message))
    } finally {
      aiRequests.delete(requestId)
    }
  })
  ipcMain.handle('ai:cancel', (_e, requestId: string) => {
    aiRequests.get(requestId)?.abort()
  })

  // Online mode: passwords are added here and never reach the renderer or any log.
  ipcMain.handle('db:test', async (_e, connectionId: string) => {
    const profile = settings.get().connections.find((c) => c.id === connectionId)
    if (!profile) return fail(new RpcError(RPC_ERROR.INVALID_PARAMS, tm('main.db.unknownConnection')))
    return runEngine('db.test', { dataSource: toDataSource(profile, await settings.getDbPassword(connectionId), downloaded) }, undefined, ctx.config.dbTimeoutSec * 1000)
  })
  ipcMain.handle('db:loadMetadata', async (_e, request: LoadMetadataRequest, requestId: string) => {
    const ids = Array.isArray(request?.connectionIds) ? request.connectionIds.map(String) : []
    const { dataSources } = await resolveDataSources(settings.get().connections, ids, (id) => settings.getDbPassword(id), downloaded)
    if (dataSources.length === 0) return fail(new RpcError(RPC_ERROR.INVALID_PARAMS, tm('main.db.noConnection')))
    // locale: the engine words its own errors (e.g. a missing Db2 driver jar) in the app's language.
    const params: Record<string, unknown> = { dialect: String(request.dialect), dataSources, locale: settings.get().language }
    if (typeof request.path === 'string') params.path = request.path
    else params.text = typeof request.text === 'string' ? request.text : ''
    // A database that accepts the connection but never answers must not keep online mode waiting forever.
    return runEngine('db.loadMetadata', params, requestId, ctx.config.dbTimeoutSec * 1000)
  })

  // JDBC drivers: downloaded on demand, only after the user accepted the vendor's license in the page.
  const repository = (): string => effectiveRepository(settings.get().driverRepository, ctx.config.mavenRepo)
  const driverStatus = async (d: DriverArtifact): Promise<DriverStatus> => {
    const repo = repository()
    const path = await installedDriver(driversRoot, d)
    return { id: d.id, installed: path !== null, path, repository: repo, url: artifactUrl(repo, d) }
  }
  const unknownDriver = (): Result<never> => fail(new RpcError(RPC_ERROR.INVALID_PARAMS, tm('main.drivers.unknown')))
  ipcMain.handle('drivers:list', () => Promise.all(DRIVERS.map(driverStatus)))
  ipcMain.handle('drivers:status', async (_e, id: unknown) => {
    const d = driverById(id)
    return d ? ok(await driverStatus(d)) : unknownDriver()
  })
  ipcMain.handle('drivers:download', async (event, id: unknown, requestId: unknown) => {
    const d = driverById(id)
    if (!d) return unknownDriver()
    if (!isRequestId(requestId) || driverRequests.has(requestId)) return fail(new RpcError(RPC_ERROR.INVALID_PARAMS, tm('main.drivers.badRequest')))
    const controller = new AbortController()
    driverRequests.set(requestId, controller)
    try {
      await downloadDriver(d, {
        fetch: (url, init) => net.fetch(url, init),
        root: driversRoot,
        repository: repository(),
        signal: controller.signal,
        onProgress: (received, total) => {
          const progress: DriverProgress = { requestId, id: d.id, received, total }
          if (!event.sender.isDestroyed()) event.sender.send('drivers:progress', progress)
        }
      })
      return ok(await driverStatus(d))
    } catch (error) {
      return fail(driverError(error))
    } finally {
      driverRequests.delete(requestId)
    }
  })
  ipcMain.handle('drivers:cancel', (_e, requestId: unknown) => {
    if (isRequestId(requestId)) driverRequests.get(requestId)?.abort()
  })
  ipcMain.handle('drivers:remove', async (_e, id: unknown) => {
    const d = driverById(id)
    if (!d) return unknownDriver()
    try {
      await removeDriver(driversRoot, d)
      return ok(await driverStatus(d))
    } catch (error) {
      return fail(error)
    }
  })

  // App
  ipcMain.handle(
    'app:info',
    (): AppInfo => ({
      name: 'Amber',
      version: app.getVersion(),
      electron: process.versions.electron,
      chrome: process.versions.chrome,
      node: process.versions.node,
      platform: process.platform,
      arch: process.arch,
      license: 'Apache-2.0'
    })
  )
  ipcMain.handle('app:checkForUpdates', async (_e, force?: boolean) => {
    if (!force && !settings.get().checkForUpdates) return ok(null)
    try {
      return ok(await checkLatestRelease(fetch, app.getVersion(), ctx.config.updateRepo, ctx.config.updateTimeoutSec))
    } catch (error) {
      return fail(error)
    }
  })
  ipcMain.handle('app:links', () => projectLinks(ctx.config.updateRepo))
  ipcMain.handle('app:thirdPartyNotices', async () => {
    // Packaged: bundled next to the app by electron-builder (extraResources). Development: the repository copy.
    const path = app.isPackaged ? join(process.resourcesPath, 'THIRD-PARTY-NOTICES.md') : join(app.getAppPath(), '..', '..', 'THIRD-PARTY-NOTICES.md')
    try {
      return ok(await readFile(path, 'utf8'))
    } catch (error) {
      return fail(error)
    }
  })
  ipcMain.handle('app:openExternal', async (_e, url: string) => {
    if (isSafeExternalUrl(url)) await shell.openExternal(url)
  })

  nativeTheme.on('updated', () => ctx.applyTheme(settings.get()))
}

/** A failed driver download as an RpcError: a localized message, and the reason in `data` for the page. */
export function driverError(error: unknown): RpcError {
  if (!(error instanceof DriverDownloadError)) return toRpcError(error)
  const data = { reason: error.reason }
  switch (error.reason) {
    case 'cancelled':
      return new RpcError(RPC_ERROR.REQUEST_CANCELLED, tm('main.drivers.cancelled'), data)
    case 'timeout':
      return new RpcError(RPC_ERROR.TIMEOUT, tm('main.drivers.timeout'), data)
    case 'repository':
      return new RpcError(RPC_ERROR.INVALID_PARAMS, tm('main.drivers.repository'), data)
    case 'http':
      return new RpcError(RPC_ERROR.INTERNAL_ERROR, tm('main.drivers.http', { status: error.status ?? 0 }), data)
    case 'size':
      return new RpcError(RPC_ERROR.INTERNAL_ERROR, tm('main.drivers.size'), data)
    case 'hash':
      return new RpcError(RPC_ERROR.INTERNAL_ERROR, tm('main.drivers.hash'), data)
    case 'io':
      return new RpcError(RPC_ERROR.INTERNAL_ERROR, tm('main.drivers.io', { reason: error.message }), data)
    default:
      return new RpcError(RPC_ERROR.INTERNAL_ERROR, tm('main.drivers.offline', { reason: error.message }), data)
  }
}

export function isSafeExternalUrl(url: string): boolean {
  try {
    const u = new URL(url)
    return u.protocol === 'https:'
  } catch {
    return false
  }
}

export function sanitizeAnalyze(params: AnalyzeParams): AnalyzeParams {
  const out: AnalyzeParams = { dialect: String(params.dialect) }
  if (typeof params.text === 'string') out.text = params.text
  if (typeof params.path === 'string') out.path = params.path
  if (typeof params.encoding === 'string' && params.encoding && params.encoding !== 'auto') out.encoding = params.encoding
  if (typeof params.name === 'string') out.name = params.name
  if (typeof params.allowFallback === 'boolean') out.allowFallback = params.allowFallback
  const strings = (v: unknown): string[] | undefined =>
    Array.isArray(v) && v.length ? v.filter((x): x is string => typeof x === 'string') : undefined
  const packs = strings(params.metadataPacks)
  const ddl = strings(params.ddlFiles)
  if (packs) out.metadataPacks = packs
  if (ddl) out.ddlFiles = ddl
  if (Array.isArray(params.metadata) && params.metadata.length) out.metadata = params.metadata
  if (params.locale === 'en' || params.locale === 'zh-CN' || params.locale === 'es') out.locale = params.locale
  // Settings → Rules; normalized like the saved settings (the engine rejects unknown severities).
  if (params.rules && typeof params.rules === 'object') {
    const rules = normalizeRules(params.rules)
    if (rules.disabled.length || Object.keys(rules.severity).length) out.rules = rules
  }
  return out
}
