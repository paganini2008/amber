import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { applyDotEnv, DEFAULT_CONFIG, loadDevEnv, logs, parseDotEnv, readConfig } from './config'
import { resolveEngineCommand } from './engine/supervisor'
import { defaultSettings, SettingsStore } from './settings/store'
import { releaseUrls } from './updates/check'

describe('.env parsing', () => {
  it('reads keys, quotes, export and comments', () => {
    expect(
      parseDotEnv(
        [
          '# comment',
          'AMBER_ENGINE_JAR=/a/b/engine.jar',
          'export AMBER_LOG_LEVEL=debug  # trailing comment',
          'AMBER_JAVA="/opt/java 25/bin/java"',
          "AMBER_UPDATE_REPO='me/amber'",
          'EMPTY=',
          'not a line',
          '  SPACED  =  value  '
        ].join('\n')
      )
    ).toEqual({
      AMBER_ENGINE_JAR: '/a/b/engine.jar',
      AMBER_LOG_LEVEL: 'debug',
      AMBER_JAVA: '/opt/java 25/bin/java',
      AMBER_UPDATE_REPO: 'me/amber',
      EMPTY: '',
      SPACED: 'value'
    })
  })

  it('never overrides the process environment and skips empty values', () => {
    const env: NodeJS.ProcessEnv = { AMBER_LOG_LEVEL: 'error' }
    expect(applyDotEnv('AMBER_LOG_LEVEL=debug\nAMBER_JAVA=/j\nAMBER_ENGINE_JAR=', env)).toEqual(['AMBER_JAVA'])
    expect(env).toEqual({ AMBER_LOG_LEVEL: 'error', AMBER_JAVA: '/j' })
  })

  it('is read in development only', () => {
    const dir = mkdtempSync(join(tmpdir(), 'amber-env-'))
    const file = join(dir, '.env')
    writeFileSync(file, 'AMBER_DEV_CDP_PORT=9444\n')
    const packagedEnv: NodeJS.ProcessEnv = {}
    expect(loadDevEnv(file, true, packagedEnv)).toEqual([])
    expect(packagedEnv).toEqual({})
    const devEnv: NodeJS.ProcessEnv = {}
    expect(loadDevEnv(file, false, devEnv)).toEqual(['AMBER_DEV_CDP_PORT'])
    expect(loadDevEnv(join(dir, 'missing.env'), false, {})).toEqual([])
  })
})

describe('readConfig', () => {
  it('uses defaults and ignores invalid values', () => {
    expect(readConfig({}, false)).toEqual(DEFAULT_CONFIG)
    const c = readConfig(
      {
        AMBER_DEV_CDP_PORT: '80',
        AMBER_DEFAULT_OLLAMA_URL: 'ftp://x',
        AMBER_UPDATE_REPO: 'no slash',
        AMBER_LOG_LEVEL: 'loud',
        AMBER_ENGINE_MAX_RESTARTS: 'many',
        AMBER_MAX_FILE_MB: '0'
      },
      false
    )
    expect(c).toEqual(DEFAULT_CONFIG)
  })

  it('applies valid values, and drops dev wiring in a packaged app', () => {
    const env = {
      AMBER_ENGINE_JAR: '/b/engine.jar',
      AMBER_JAVA: '/j/java',
      AMBER_DEV_CDP_PORT: '9334',
      AMBER_DEFAULT_OLLAMA_URL: 'http://gpu-box:11434',
      AMBER_DEFAULT_OPENAI_URL: 'https://proxy.local/v1',
      AMBER_DEFAULT_AI_TIMEOUT_SEC: '300',
      AMBER_UPDATE_REPO: 'me/amber-fork',
      AMBER_LOG_LEVEL: 'DEBUG',
      AMBER_ENGINE_MAX_RESTARTS: '2',
      AMBER_ENGINE_RESTART_WINDOW_SEC: '30',
      AMBER_MAX_FILE_MB: '128',
      AMBER_DB_TIMEOUT_SEC: '20'
    }
    const dev = readConfig(env, false)
    expect(dev).toMatchObject({ engineJar: '/b/engine.jar', java: '/j/java', devCdpPort: 9334, defaultAiTimeoutSec: 300, updateRepo: 'me/amber-fork', logLevel: 'debug', maxFileMb: 128, dbTimeoutSec: 20 })
    const packaged = readConfig(env, true)
    expect(packaged).toMatchObject({ engineJar: null, java: null, devCdpPort: null, defaultOllamaUrl: 'http://gpu-box:11434' })
    expect(logs(dev, 'debug')).toBe(true)
    expect(logs({ ...dev, logLevel: 'error' }, 'info')).toBe(false)
    expect(logs({ ...dev, logLevel: 'off' }, 'error')).toBe(false)
  })
})

describe('defaults reach the app, user settings win', () => {
  it('seeds new settings from the configured defaults only', () => {
    const d = defaultSettings({ ollamaBaseUrl: 'http://gpu-box:11434', aiTimeoutSec: 300 })
    expect(d.ai.ollama.baseUrl).toBe('http://gpu-box:11434')
    expect(d.ai.openai.timeoutSec).toBe(300)
    expect(d.connections).toEqual([])
    const dir = mkdtempSync(join(tmpdir(), 'amber-settings-'))
    const box = { available: async () => true, encrypt: async (s: string) => s, decrypt: async (s: string) => s }
    const fresh = new SettingsStore(join(dir, 's.json'), join(dir, 'k.json'), box, { ollamaBaseUrl: 'http://gpu-box:11434' })
    expect(fresh.get().ai.ollama.baseUrl).toBe('http://gpu-box:11434')
    fresh.update({ ai: { ollama: { baseUrl: 'http://mine:11434' } } })
    const reopened = new SettingsStore(join(dir, 's.json'), join(dir, 'k.json'), box, { ollamaBaseUrl: 'http://gpu-box:11434' })
    expect(reopened.get().ai.ollama.baseUrl).toBe('http://mine:11434')
  })

  it('points the update check and the dev engine at the configured places', () => {
    expect(releaseUrls('me/amber-fork')).toEqual({
      api: 'https://api.github.com/repos/me/amber-fork/releases/latest',
      page: 'https://github.com/me/amber-fork/releases/latest'
    })
    const cmd = resolveEngineCommand({ packaged: false, resourcesPath: '/r', appRoot: '/a/ui/amber-desktop', platform: 'darwin', env: { AMBER_JAVA: '/j/java', AMBER_ENGINE_JAR: '/b/engine.jar' }, exists: () => true })
    expect(cmd.command).toBe('/j/java')
    expect(cmd.args).toEqual(['-jar', '/b/engine.jar', 'serve'])
    const relative = resolveEngineCommand({ packaged: false, resourcesPath: '/r', appRoot: '/a/ui/amber-desktop', platform: 'darwin', env: { AMBER_ENGINE_JAR: '../../backend/x/engine.jar' }, exists: () => false })
    expect(relative.jar).toBe('/a/backend/x/engine.jar')
    const packaged = resolveEngineCommand({ packaged: true, resourcesPath: '/r', appRoot: '/a', platform: 'darwin', env: { AMBER_JAVA: '/j/java' }, exists: () => false })
    expect(packaged.command).toBe('/r/runtime/bin/java')
  })
})
