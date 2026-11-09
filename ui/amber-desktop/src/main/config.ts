import { existsSync, readFileSync } from 'node:fs'

/**
 * Development and build-time configuration of the desktop app: dev wiring (engine jar, java,
 * debugging port) and DEFAULTS for user settings. User settings (Settings dialog) always win;
 * database connections and API keys are never configured here (they are entered in Settings and
 * stored encrypted).
 *
 * In development, `ui/amber-desktop/.env` is read first (see `.env.example`); variables already set
 * in the process environment are not overridden. A packaged app never reads a .env file.
 */

/** Parses KEY=VALUE lines: `#` comments, optional `export`, single or double quotes. */
export function parseDotEnv(text: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const m = /^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line)
    if (!m) continue
    let value = m[2]!
    if ((value.startsWith('"') && value.endsWith('"') && value.length >= 2) || (value.startsWith("'") && value.endsWith("'") && value.length >= 2)) {
      const quote = value[0]
      value = value.slice(1, -1)
      if (quote === '"') value = value.replace(/\\n/g, '\n').replace(/\\"/g, '"')
    } else {
      // An unquoted value ends at a comment.
      value = value.replace(/\s+#.*$/, '').trim()
    }
    out[m[1]!] = value
  }
  return out
}

/** Copies .env values into `env` without overriding what is already set. Returns the keys applied. */
export function applyDotEnv(text: string, env: NodeJS.ProcessEnv): string[] {
  const applied: string[] = []
  for (const [k, v] of Object.entries(parseDotEnv(text))) {
    if (env[k] !== undefined && env[k] !== '') continue
    if (v === '') continue
    env[k] = v
    applied.push(k)
  }
  return applied
}

/** Loads the .env file in development only. */
export function loadDevEnv(path: string, packaged: boolean, env: NodeJS.ProcessEnv = process.env): string[] {
  if (packaged || !existsSync(path)) return []
  try {
    return applyDotEnv(readFileSync(path, 'utf8'), env)
  } catch {
    return []
  }
}

export type LogLevel = 'off' | 'error' | 'info' | 'debug'

export interface AppConfig {
  /** Dev: the engine jar (default: backend/amber-core/target/engine/engine.jar). */
  engineJar: string | null
  /** Dev: the java executable (default: $JAVA_HOME/bin/java, else java on the PATH). */
  java: string | null
  /** Dev: Chrome DevTools Protocol port for the renderer, or null. */
  devCdpPort: number | null
  /** Dev: a separate settings folder (Electron userData), e.g. a clean profile for recordings, or null. */
  devUserData: string | null
  /** Defaults for new settings; a value saved in Settings always wins. */
  defaultOllamaUrl: string
  defaultOpenAiUrl: string
  defaultAiTimeoutSec: number
  /** GitHub owner/repo whose releases the update check reads. */
  updateRepo: string
  updateTimeoutSec: number
  /** How much the main process logs (engine stderr lines are "info"). */
  logLevel: LogLevel
  /** Engine restarts allowed within the window before giving up. */
  engineMaxRestarts: number
  engineRestartWindowSec: number
  /** Largest script the app opens. */
  maxFileMb: number
  /** Longest wait for a connection test or a metadata load before giving up. */
  dbTimeoutSec: number
  /** HTTPS Maven repository JDBC drivers are downloaded from; Settings → Connections → Driver repository wins. */
  mavenRepo: string
}

export const DEFAULT_CONFIG: AppConfig = {
  engineJar: null,
  java: null,
  devCdpPort: null,
  devUserData: null,
  defaultOllamaUrl: 'http://localhost:11434',
  defaultOpenAiUrl: 'https://api.openai.com/v1',
  defaultAiTimeoutSec: 120,
  updateRepo: 'paganini2008/amber',
  updateTimeoutSec: 10,
  logLevel: 'info',
  engineMaxRestarts: 5,
  engineRestartWindowSec: 60,
  maxFileMb: 64,
  dbTimeoutSec: 60,
  mavenRepo: 'https://repo1.maven.org/maven2'
}

function int(value: string | undefined, min: number, max: number, fallback: number): number {
  if (value === undefined || value.trim() === '') return fallback
  const n = Number(value)
  return Number.isInteger(n) && n >= min && n <= max ? n : fallback
}

function url(value: string | undefined, fallback: string): string {
  if (!value) return fallback
  try {
    const u = new URL(value)
    return u.protocol === 'http:' || u.protocol === 'https:' ? value : fallback
  } catch {
    return fallback
  }
}

/** HTTPS only, without a trailing slash (driver downloads; a jar is code the engine runs). */
function httpsUrl(value: string | undefined, fallback: string): string {
  if (!value) return fallback
  try {
    const u = new URL(value)
    return u.protocol === 'https:' && !u.username && !u.password && !u.search && !u.hash ? value.replace(/\/+$/, '') : fallback
  } catch {
    return fallback
  }
}

/** Reads the configuration from the environment; invalid values fall back to the defaults. */
export function readConfig(env: NodeJS.ProcessEnv, packaged: boolean): AppConfig {
  const d = DEFAULT_CONFIG
  const level = (env.AMBER_LOG_LEVEL ?? '').toLowerCase()
  const repo = env.AMBER_UPDATE_REPO ?? ''
  return {
    // Dev wiring has no effect in a packaged app, which runs its bundled runtime and engine.
    engineJar: packaged ? null : env.AMBER_ENGINE_JAR || null,
    java: packaged ? null : env.AMBER_JAVA || null,
    devCdpPort: packaged ? null : int(env.AMBER_DEV_CDP_PORT, 1024, 65535, 0) || null,
    devUserData: packaged ? null : env.AMBER_DEV_USER_DATA || null,
    defaultOllamaUrl: url(env.AMBER_DEFAULT_OLLAMA_URL, d.defaultOllamaUrl),
    defaultOpenAiUrl: url(env.AMBER_DEFAULT_OPENAI_URL, d.defaultOpenAiUrl),
    defaultAiTimeoutSec: int(env.AMBER_DEFAULT_AI_TIMEOUT_SEC, 5, 3600, d.defaultAiTimeoutSec),
    updateRepo: /^[\w.-]+\/[\w.-]+$/.test(repo) ? repo : d.updateRepo,
    updateTimeoutSec: int(env.AMBER_UPDATE_TIMEOUT_SEC, 1, 120, d.updateTimeoutSec),
    logLevel: level === 'off' || level === 'error' || level === 'info' || level === 'debug' ? level : d.logLevel,
    engineMaxRestarts: int(env.AMBER_ENGINE_MAX_RESTARTS, 0, 100, d.engineMaxRestarts),
    engineRestartWindowSec: int(env.AMBER_ENGINE_RESTART_WINDOW_SEC, 1, 3600, d.engineRestartWindowSec),
    maxFileMb: int(env.AMBER_MAX_FILE_MB, 1, 1024, d.maxFileMb),
    dbTimeoutSec: int(env.AMBER_DB_TIMEOUT_SEC, 5, 3600, d.dbTimeoutSec),
    mavenRepo: httpsUrl(env.AMBER_MAVEN_REPO, d.mavenRepo)
  }
}

export function logs(config: AppConfig, level: Exclude<LogLevel, 'off'>): boolean {
  const order: LogLevel[] = ['off', 'error', 'info', 'debug']
  return order.indexOf(config.logLevel) >= order.indexOf(level)
}
