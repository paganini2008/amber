import type { AnalysisResult, AnalyzeParams, EngineInfo, EngineMethod, LoadMetadataResult, RpcErrorObject, RuleSettings, ServerInfo } from './protocol'

/** Results cross the IPC boundary as values so error codes survive (thrown errors lose them). */
export type Result<T> = { ok: true; value: T } | { ok: false; error: RpcErrorObject }

/**
 * How engine answers cross IPC: the value as JSON text. Electron clones plain objects field by
 * field (twice: main → preload, preload → page), which took over a minute for the 115 MB analysis
 * of a 100k-line script; one string is copied in milliseconds and parsed in the page.
 * `lib/api.ts` turns it back into a Result, so callers only ever see Result<T>.
 */
export type WireResult = { ok: true; json: string } | { ok: false; error: RpcErrorObject }

export type ThemeId = 'system' | 'amber-light' | 'amber-dark' | 'resin' | 'high-contrast'

/** UI languages. English is the default; Amber never follows the OS locale on its own. */
export const LANGUAGES = ['en', 'zh-CN', 'es'] as const
export type LanguageId = (typeof LANGUAGES)[number]

export interface OllamaProviderSettings {
  enabled: boolean
  baseUrl: string
  model: string
  temperature: number
  timeoutSec: number
  maskIdentifiers: boolean
  /** True when an optional API key (for an Ollama behind an authenticating proxy) is stored in main. */
  hasApiKey: boolean
}

export interface OpenAiProviderSettings {
  enabled: boolean
  baseUrl: string
  model: string
  temperature: number
  timeoutSec: number
  maskIdentifiers: boolean
  /** True when an API key is stored in the main process. The key itself never leaves main. */
  hasApiKey: boolean
}

export type AiProviderId = 'ollama' | 'openai'

export interface ConnectionProfile {
  id: string
  name: string
  dialect: string
  host: string
  port: number
  database: string
  user: string
  /** Optional JDBC URL; when set it is used instead of host, port and database. */
  url?: string
  /**
   * Optional absolute path of a JDBC driver .jar, sent to the engine as `driverPath`. Amber bundles no
   * driver: when this is empty, main uses the driver downloaded for the dialect (Settings → Connections →
   * Drivers), if any. Not a secret, so it is kept in settings.
   */
  driverPath?: string
  /** True when a password is stored in the main process. */
  hasPassword: boolean
}

/** Online mode: off by default. When on, metadata of referenced tables is read from these connections. */
export interface OnlineSettings {
  enabled: boolean
  /** Connection ids to look tables up in, in order; order breaks ties when a name is found in several. */
  connectionIds: string[]
}

/** `db.loadMetadata` as the renderer asks for it: connections by id, passwords added in main. */
export interface LoadMetadataRequest {
  dialect: string
  text?: string
  path?: string
  connectionIds: string[]
}

export interface PickFilesRequest {
  title: string
  filters: { name: string; extensions: string[] }[]
  multiple?: boolean
}

/** How "Format script" lays out SQL. Only whitespace and keyword case ever change. */
export interface FormatSettings {
  indentSize: number
  keywordCase: 'UPPER' | 'LOWER' | 'PRESERVE'
  maxLineWidth: number
  commaStyle: 'TRAILING' | 'LEADING'
}

export interface Settings {
  theme: ThemeId
  /** UI language; 'en' unless the user picks another one in Settings → General. */
  language: LanguageId
  fontSize: number
  defaultDialect: string
  encoding: string
  allowFallback: boolean
  checkForUpdates: boolean
  activeAiProvider: AiProviderId | null
  ai: {
    ollama: OllamaProviderSettings
    openai: OpenAiProviderSettings
    maxContextTokens: number
  }
  connections: ConnectionProfile[]
  online: OnlineSettings
  format: FormatSettings
  /** Settings → Rules: disabled rule ids and severity overrides, passed with every engine call that computes findings. */
  rules: RuleSettings
  /**
   * Settings → Connections → Driver repository: an HTTPS Maven repository (mirror) drivers are downloaded
   * from. Empty uses the default (Maven Central, or AMBER_MAVEN_REPO in development).
   */
  driverRepository: string
  recentFiles: string[]
}

export type SettingsPatch = Partial<Omit<Settings, 'ai' | 'connections' | 'recentFiles'>> & {
  ai?: {
    ollama?: Partial<Omit<OllamaProviderSettings, 'hasApiKey'>>
    openai?: Partial<Omit<OpenAiProviderSettings, 'hasApiKey'>>
    maxContextTokens?: number
  }
}

export interface OpenedFile {
  path: string | null
  name: string
  text: string
  encoding: string
}

export type EngineState = 'starting' | 'ready' | 'restarting' | 'failed' | 'stopped'

export interface EngineStatus {
  state: EngineState
  message?: string
  info?: EngineInfo
}

export interface SaveRequest {
  defaultName: string
  filters: { name: string; extensions: string[] }[]
  /** Text content, or base64 when `encoding` is "base64". */
  data: string
  encoding?: 'utf8' | 'base64'
}

export interface UpdateInfo {
  current: string
  latest: string
  newer: boolean
  url: string
}

export interface AppInfo {
  name: string
  version: string
  electron: string
  chrome: string
  node: string
  platform: string
  arch: string
  license: string
}

export interface AiMessage {
  role: 'system' | 'user' | 'assistant'
  content: string
}

export type AiStreamEvent =
  | { requestId: string; type: 'delta'; text: string }
  | { requestId: string; type: 'done' }
  | { requestId: string; type: 'error'; message: string }

/** A downloadable JDBC driver on this computer (see shared/drivers.ts). */
export interface DriverStatus {
  /** Catalog id, which is the dialect id. */
  id: string
  /** True when the pinned jar is downloaded and its checksum matches. */
  installed: boolean
  /** The downloaded jar, or null. */
  path: string | null
  /** The repository base URL a download would use. */
  repository: string
  /** The full URL of the jar in that repository. */
  url: string
}

export interface DriverProgress {
  requestId: string
  id: string
  received: number
  total: number
}

export type MenuCommand =
  | 'open-file'
  | 'paste-sql'
  | 'settings'
  | 'about'
  | 'export-json'
  | 'export-svg'
  | 'export-png'
  | 'export-mermaid'
  | 'export-handover'
  | 'compare-saved'
  | 'compare-file'
  | 'format-script'
  | 'format-selection'
  | 'reanalyze'

export interface AmberApi {
  engine: {
    status(): Promise<EngineStatus>
    onStatus(listener: (status: EngineStatus) => void): () => void
    analyze(params: AnalyzeParams, requestId: string): Promise<Result<AnalysisResult>>
    /** Generic call for methods added in later engine versions. -32601 means not available. */
    call<T = unknown>(method: EngineMethod, params: unknown, requestId?: string): Promise<Result<T>>
    cancel(requestId: string): Promise<void>
    restart(): Promise<void>
  }
  files: {
    openDialog(): Promise<OpenedFile | null>
    open(path: string): Promise<Result<OpenedFile>>
    pathForFile(file: File): string
    save(request: SaveRequest): Promise<string | null>
    /** Native open dialog that returns paths only (DDL files, metadata packs). */
    pickFiles(request: PickFilesRequest): Promise<string[]>
  }
  settings: {
    get(): Promise<Settings>
    update(patch: SettingsPatch): Promise<Settings>
    setAiApiKey(provider: AiProviderId, key: string | null): Promise<Settings>
    clearRecentFiles(): Promise<Settings>
    saveConnection(profile: Omit<ConnectionProfile, 'hasPassword'>, password?: string | null): Promise<Settings>
    deleteConnection(id: string): Promise<Settings>
    onChanged(listener: (settings: Settings) => void): () => void
  }
  ai: {
    listOllamaModels(baseUrl: string): Promise<Result<string[]>>
    /** Chat models at `GET {baseUrl}/models`, using the stored API key (which never leaves main). */
    listOpenAiModels(baseUrl: string): Promise<Result<string[]>>
    test(provider: AiProviderId): Promise<Result<string>>
    chat(provider: AiProviderId, messages: AiMessage[], requestId: string): Promise<Result<string>>
    cancel(requestId: string): Promise<void>
    onStream(listener: (event: AiStreamEvent) => void): () => void
  }
  db: {
    test(connectionId: string): Promise<Result<ServerInfo>>
    loadMetadata(request: LoadMetadataRequest, requestId: string): Promise<Result<LoadMetadataResult>>
  }
  /** On-demand JDBC drivers: downloaded in main, after the user accepted the vendor's license. */
  drivers: {
    /** Every catalog driver, installed or not. */
    list(): Promise<DriverStatus[]>
    status(id: string): Promise<Result<DriverStatus>>
    /** Downloads (or reuses a verified copy of) the driver; progress arrives through onProgress. */
    download(id: string, requestId: string): Promise<Result<DriverStatus>>
    cancel(requestId: string): Promise<void>
    /** Deletes the downloaded jar. */
    remove(id: string): Promise<Result<DriverStatus>>
    onProgress(listener: (event: DriverProgress) => void): () => void
  }
  app: {
    info(): Promise<AppInfo>
    checkForUpdates(force?: boolean): Promise<Result<UpdateInfo | null>>
    openExternal(url: string): Promise<void>
    /** Project, website, releases and issue links, and the author's profile (from the configured repo). */
    links(): Promise<{ project: string; website: string; releases: string; issue: string; author: { name: string; url: string } }>
    /** THIRD-PARTY-NOTICES.md bundled with the app (read from the repository in development). */
    thirdPartyNotices(): Promise<Result<string>>
    onMenu(listener: (command: MenuCommand) => void): () => void
  }
}
