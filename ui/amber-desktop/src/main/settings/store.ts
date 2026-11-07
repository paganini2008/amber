import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import type { ConnectionProfile, Settings, SettingsPatch, ThemeId } from '../../shared/api'

export const DEFAULT_SETTINGS: Settings = {
  theme: 'system',
  fontSize: 13,
  defaultDialect: 'oracle',
  encoding: 'auto',
  allowFallback: false,
  checkForUpdates: true,
  activeAiProvider: null,
  ai: {
    ollama: {
      enabled: false,
      baseUrl: 'http://localhost:11434',
      model: '',
      temperature: 0.2,
      timeoutSec: 120,
      maskIdentifiers: false,
      hasApiKey: false
    },
    openai: {
      enabled: false,
      baseUrl: 'https://api.openai.com/v1',
      model: '',
      temperature: 0.2,
      timeoutSec: 120,
      maskIdentifiers: true,
      hasApiKey: false
    },
    maxContextTokens: 16000
  },
  connections: [],
  online: { enabled: false, connectionIds: [] },
  format: { indentSize: 4, keywordCase: 'UPPER', maxLineWidth: 100, commaStyle: 'TRAILING' },
  recentFiles: []
}

/** Defaults a development .env may change (see main/config.ts). Saved settings always win. */
export interface SettingsDefaults {
  ollamaBaseUrl?: string
  openaiBaseUrl?: string
  aiTimeoutSec?: number
}

export function defaultSettings(o: SettingsDefaults = {}): Settings {
  const d = structuredClone(DEFAULT_SETTINGS)
  if (o.ollamaBaseUrl) d.ai.ollama.baseUrl = o.ollamaBaseUrl
  if (o.openaiBaseUrl) d.ai.openai.baseUrl = o.openaiBaseUrl
  if (o.aiTimeoutSec) {
    d.ai.ollama.timeoutSec = o.aiTimeoutSec
    d.ai.openai.timeoutSec = o.aiTimeoutSec
  }
  return d
}

const THEMES: ThemeId[] = ['system', 'amber-light', 'amber-dark', 'resin', 'high-contrast']
const MAX_RECENT = 10

/**
 * Encrypts secrets; backed by Electron's async safeStorage in the app. Async on purpose: on macOS
 * the keychain may ask the user for permission, and a synchronous call would freeze the whole app
 * (every window and the engine traffic) until the prompt is answered.
 */
export interface SecretBox {
  available(): Promise<boolean>
  encrypt(plain: string): Promise<string>
  decrypt(cipher: string): Promise<string>
}

interface SecretFile {
  aiKeys: Record<string, string>
  dbPasswords: Record<string, string>
}

/**
 * Persists non-secret settings as JSON and secrets in a separate file, encrypted with SecretBox.
 * Secrets are never part of the Settings object handed to the renderer.
 */
export class SettingsStore {
  private settings: Settings
  private secrets: SecretFile
  private readonly defaults: Settings

  constructor(
    private readonly settingsPath: string,
    private readonly secretsPath: string,
    private readonly box: SecretBox,
    defaults: SettingsDefaults = {}
  ) {
    this.defaults = defaultSettings(defaults)
    this.settings = normalize(readJson(settingsPath), this.defaults)
    const s = readJson(secretsPath) as Partial<SecretFile> | null
    this.secrets = { aiKeys: s?.aiKeys ?? {}, dbPasswords: s?.dbPasswords ?? {} }
    this.syncFlags()
  }

  get(): Settings {
    return structuredClone(this.settings)
  }

  update(patch: SettingsPatch): Settings {
    const { ai, ...rest } = patch
    const next: Settings = { ...this.settings, ...stripUndefined(rest) }
    if (ai) {
      next.ai = {
        ollama: { ...this.settings.ai.ollama, ...stripUndefined(ai.ollama ?? {}) },
        openai: { ...this.settings.ai.openai, ...stripUndefined(ai.openai ?? {}) },
        maxContextTokens: ai.maxContextTokens ?? this.settings.ai.maxContextTokens
      }
    }
    this.settings = normalize(next, this.defaults)
    this.syncFlags()
    this.save()
    return this.get()
  }

  addRecentFile(path: string): Settings {
    const list = [path, ...this.settings.recentFiles.filter((p) => p !== path)].slice(0, MAX_RECENT)
    this.settings = { ...this.settings, recentFiles: list }
    this.save()
    return this.get()
  }

  clearRecentFiles(): Settings {
    this.settings = { ...this.settings, recentFiles: [] }
    this.save()
    return this.get()
  }

  async setAiKey(provider: string, key: string | null): Promise<Settings> {
    if (key) {
      await this.requireEncryption()
      this.secrets.aiKeys[provider] = await this.box.encrypt(key)
    } else {
      delete this.secrets.aiKeys[provider]
    }
    this.saveSecrets()
    this.syncFlags()
    this.save()
    return this.get()
  }

  async getAiKey(provider: string): Promise<string | null> {
    const cipher = this.secrets.aiKeys[provider]
    return cipher ? this.box.decrypt(cipher) : null
  }

  async saveConnection(profile: Omit<ConnectionProfile, 'hasPassword'>, password?: string | null): Promise<Settings> {
    const connections = this.settings.connections.filter((c) => c.id !== profile.id)
    const index = this.settings.connections.findIndex((c) => c.id === profile.id)
    const entry: ConnectionProfile = { ...profile, hasPassword: false }
    if (index >= 0) connections.splice(index, 0, entry)
    else connections.push(entry)
    if (password !== undefined) {
      if (password) {
        await this.requireEncryption()
        this.secrets.dbPasswords[profile.id] = await this.box.encrypt(password)
      } else {
        delete this.secrets.dbPasswords[profile.id]
      }
      this.saveSecrets()
    }
    this.settings = { ...this.settings, connections }
    this.syncFlags()
    this.save()
    return this.get()
  }

  deleteConnection(id: string): Settings {
    delete this.secrets.dbPasswords[id]
    this.saveSecrets()
    this.settings = {
      ...this.settings,
      connections: this.settings.connections.filter((c) => c.id !== id),
      online: { ...this.settings.online, connectionIds: this.settings.online.connectionIds.filter((c) => c !== id) }
    }
    this.save()
    return this.get()
  }

  async getDbPassword(id: string): Promise<string | null> {
    const cipher = this.secrets.dbPasswords[id]
    return cipher ? this.box.decrypt(cipher) : null
  }

  private async requireEncryption(): Promise<void> {
    if (!(await this.box.available())) {
      throw new Error('Secure storage is not available on this system, so secrets cannot be saved.')
    }
  }

  private syncFlags(): void {
    this.settings.ai.openai.hasApiKey = Boolean(this.secrets.aiKeys.openai)
    this.settings.ai.ollama.hasApiKey = Boolean(this.secrets.aiKeys.ollama)
    this.settings.connections = this.settings.connections.map((c) => ({
      ...c,
      hasPassword: Boolean(this.secrets.dbPasswords[c.id])
    }))
  }

  private save(): void {
    writeJsonAtomic(this.settingsPath, this.settings)
  }

  private saveSecrets(): void {
    writeJsonAtomic(this.secretsPath, this.secrets)
  }
}

function normalize(raw: unknown, d: Settings = DEFAULT_SETTINGS): Settings {
  const r = (raw && typeof raw === 'object' ? raw : {}) as Partial<Settings>
  const ai = (r.ai ?? {}) as Partial<Settings['ai']>
  return {
    theme: THEMES.includes(r.theme as ThemeId) ? (r.theme as ThemeId) : d.theme,
    fontSize: clampNumber(r.fontSize, 11, 20, d.fontSize),
    defaultDialect: typeof r.defaultDialect === 'string' ? r.defaultDialect : d.defaultDialect,
    encoding: typeof r.encoding === 'string' && r.encoding ? r.encoding : d.encoding,
    allowFallback: typeof r.allowFallback === 'boolean' ? r.allowFallback : d.allowFallback,
    checkForUpdates: typeof r.checkForUpdates === 'boolean' ? r.checkForUpdates : d.checkForUpdates,
    activeAiProvider: r.activeAiProvider === 'ollama' || r.activeAiProvider === 'openai' ? r.activeAiProvider : null,
    ai: {
      ollama: { ...d.ai.ollama, ...(ai.ollama ?? {}) },
      openai: { ...d.ai.openai, ...(ai.openai ?? {}) },
      maxContextTokens: clampNumber(ai.maxContextTokens, 1000, 1_000_000, d.ai.maxContextTokens)
    },
    connections: Array.isArray(r.connections) ? r.connections.filter(isConnection) : [],
    online: {
      enabled: typeof r.online?.enabled === 'boolean' ? r.online.enabled : d.online.enabled,
      connectionIds: Array.isArray(r.online?.connectionIds)
        ? [...new Set(r.online.connectionIds.filter((id): id is string => typeof id === 'string'))]
        : []
    },
    format: normalizeFormat(r.format),
    recentFiles: Array.isArray(r.recentFiles)
      ? r.recentFiles.filter((p): p is string => typeof p === 'string').slice(0, MAX_RECENT)
      : []
  }
}

function normalizeFormat(raw: unknown): Settings['format'] {
  const f = (raw && typeof raw === 'object' ? raw : {}) as Partial<Settings['format']>
  const d = DEFAULT_SETTINGS.format
  return {
    indentSize: clampNumber(f.indentSize, 1, 8, d.indentSize),
    keywordCase: f.keywordCase === 'LOWER' || f.keywordCase === 'PRESERVE' || f.keywordCase === 'UPPER' ? f.keywordCase : d.keywordCase,
    maxLineWidth: clampNumber(f.maxLineWidth, 40, 400, d.maxLineWidth),
    commaStyle: f.commaStyle === 'LEADING' || f.commaStyle === 'TRAILING' ? f.commaStyle : d.commaStyle
  }
}

function isConnection(c: unknown): c is ConnectionProfile {
  const x = c as ConnectionProfile
  return Boolean(x) && typeof x.id === 'string' && typeof x.name === 'string'
}

function clampNumber(value: unknown, min: number, max: number, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? Math.min(max, Math.max(min, value)) : fallback
}

function stripUndefined<T extends object>(value: T): Partial<T> {
  return Object.fromEntries(Object.entries(value).filter(([, v]) => v !== undefined)) as Partial<T>
}

function readJson(path: string): unknown {
  try {
    return JSON.parse(readFileSync(path, 'utf8'))
  } catch {
    return null
  }
}

function writeJsonAtomic(path: string, value: unknown): void {
  mkdirSync(dirname(path), { recursive: true })
  const tmp = `${path}.tmp`
  writeFileSync(tmp, JSON.stringify(value, null, 2), { encoding: 'utf8', mode: 0o600 })
  renameSync(tmp, path)
}
