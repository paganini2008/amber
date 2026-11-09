import { mkdtempSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { SettingsStore, type SecretBox } from './store'

const box: SecretBox = {
  available: async () => true,
  encrypt: async (s) => Buffer.from(s.split('').reverse().join('')).toString('base64'),
  decrypt: async (s) => Buffer.from(s, 'base64').toString().split('').reverse().join('')
}

function makeStore(): { store: SettingsStore; dir: string } {
  const dir = mkdtempSync(join(tmpdir(), 'amber-settings-'))
  return { store: new SettingsStore(join(dir, 'settings.json'), join(dir, 'secrets.json'), box), dir }
}

describe('SettingsStore', () => {
  it('defaults to English and empty rule settings, and normalizes saved rules', () => {
    const { store, dir } = makeStore()
    expect(store.get().language).toBe('en')
    expect(store.get().rules).toEqual({ disabled: [], severity: {} })
    store.update({ language: 'zh-CN', rules: { disabled: ['amb-s009', 'AMB-S009', ' AMB-P001 '], severity: { 'amb-s008': 'HIGH', 'AMB-P004': 'BOGUS' as 'LOW' } } })
    const reloaded = new SettingsStore(join(dir, 'settings.json'), join(dir, 'secrets.json'), box)
    expect(reloaded.get().language).toBe('zh-CN')
    expect(reloaded.get().rules).toEqual({ disabled: ['AMB-P001', 'AMB-S009'], severity: { 'AMB-S008': 'HIGH' } })
    store.update({ language: 'fr' as 'en' })
    expect(store.get().language).toBe('en')
  })

  it('starts with defaults and persists patches', () => {
    const { store, dir } = makeStore()
    expect(store.get().theme).toBe('system')
    store.update({ theme: 'resin', ai: { ollama: { model: 'qwen' } } })
    const reloaded = new SettingsStore(join(dir, 'settings.json'), join(dir, 'secrets.json'), box)
    expect(reloaded.get().theme).toBe('resin')
    expect(reloaded.get().ai.ollama.model).toBe('qwen')
    expect(reloaded.get().ai.ollama.baseUrl).toBe('http://localhost:11434')
  })

  it('keeps secrets out of settings and the settings file', async () => {
    const { store, dir } = makeStore()
    const settings = await store.setAiKey('openai', 'sk-secret-123')
    expect(settings.ai.openai.hasApiKey).toBe(true)
    expect(JSON.stringify(settings)).not.toContain('sk-secret-123')
    expect(readFileSync(join(dir, 'settings.json'), 'utf8')).not.toContain('sk-secret-123')
    expect(readFileSync(join(dir, 'secrets.json'), 'utf8')).not.toContain('sk-secret-123')
    expect(await store.getAiKey('openai')).toBe('sk-secret-123')
    expect((await store.setAiKey('openai', null)).ai.openai.hasApiKey).toBe(false)
  })

  it('stores connection passwords separately', async () => {
    const { store } = makeStore()
    const profile = { id: 'c1', name: 'Dev', dialect: 'postgresql', host: 'localhost', port: 5432, database: 'dw', user: 'reader' }
    const settings = await store.saveConnection(profile, 'pw')
    expect(settings.connections[0]).toMatchObject({ id: 'c1', hasPassword: true })
    expect(await store.getDbPassword('c1')).toBe('pw')
    await store.saveConnection({ ...profile, name: 'Dev 2' })
    expect(await store.getDbPassword('c1')).toBe('pw')
    expect(store.deleteConnection('c1').connections).toHaveLength(0)
    expect(await store.getDbPassword('c1')).toBeNull()
  })

  it('keeps the Db2 driver jar path in settings, not in the secrets', async () => {
    const { store, dir } = makeStore()
    const jar = '/opt/drivers/jcc-12.1.4.0.jar'
    const profile = { id: 'd1', name: 'Db2', dialect: 'db2', host: 'db2.local', port: 50000, database: 'DEMO', user: 'reader', driverPath: jar }
    expect((await store.saveConnection(profile, 'pw')).connections[0]).toMatchObject({ id: 'd1', driverPath: jar, hasPassword: true })
    expect(JSON.parse(readFileSync(join(dir, 'settings.json'), 'utf8')).connections[0].driverPath).toBe(jar)
    expect(readFileSync(join(dir, 'secrets.json'), 'utf8')).not.toContain('jcc')
    const reopened = new SettingsStore(join(dir, 'settings.json'), join(dir, 'secrets.json'), box)
    expect(reopened.get().connections[0]).toMatchObject({ id: 'd1', dialect: 'db2', driverPath: jar, hasPassword: true })
  })

  it('refuses to save a secret when secure storage is unavailable, without blocking', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'amber-settings-'))
    const locked: SecretBox = { ...box, available: async () => false }
    const store = new SettingsStore(join(dir, 'settings.json'), join(dir, 'secrets.json'), locked)
    await expect(store.setAiKey('openai', 'sk-x')).rejects.toThrow(/Secure storage is not available/)
    expect(store.get().ai.openai.hasApiKey).toBe(false)
  })

  it('keeps the ten most recent files, newest first', () => {
    const { store } = makeStore()
    for (let i = 0; i < 12; i++) store.addRecentFile(`/f${i}.sql`)
    store.addRecentFile('/f5.sql')
    const recent = store.get().recentFiles
    expect(recent).toHaveLength(10)
    expect(recent[0]).toBe('/f5.sql')
    expect(recent.filter((p) => p === '/f5.sql')).toHaveLength(1)
  })

  it('keeps online mode off by default and forgets deleted connections', async () => {
    const { store, dir } = makeStore()
    expect(store.get().online).toEqual({ enabled: false, connectionIds: [] })
    await store.saveConnection({ id: 'a', name: 'A', dialect: 'sqlite', host: '', port: 0, database: '', user: '', url: 'jdbc:sqlite:/tmp/a.db' })
    await store.saveConnection({ id: 'b', name: 'B', dialect: 'postgresql', host: 'h', port: 5432, database: 'd', user: 'u' })
    store.update({ online: { enabled: true, connectionIds: ['b', 'a', 'b'] } })
    const reloaded = new SettingsStore(join(dir, 'settings.json'), join(dir, 'secrets.json'), box)
    expect(reloaded.get().online).toEqual({ enabled: true, connectionIds: ['b', 'a'] })
    expect(reloaded.get().connections.find((c) => c.id === 'a')?.url).toBe('jdbc:sqlite:/tmp/a.db')
    expect(reloaded.deleteConnection('b').online.connectionIds).toEqual(['a'])
  })

  it('keeps only an HTTPS driver repository; empty means the default', () => {
    const { store, dir } = makeStore()
    expect(store.get().driverRepository).toBe('')
    expect(store.update({ driverRepository: 'https://nexus.corp/repository/central/' }).driverRepository).toBe('https://nexus.corp/repository/central')
    expect(new SettingsStore(join(dir, 'settings.json'), join(dir, 'secrets.json'), box).get().driverRepository).toBe('https://nexus.corp/repository/central')
    expect(store.update({ driverRepository: 'http://mirror/m2' }).driverRepository).toBe('')
    expect(store.update({ driverRepository: 'https://u:p@mirror/m2' }).driverRepository).toBe('')
  })

  it('keeps format options within sensible bounds', () => {
    const { store } = makeStore()
    expect(store.get().format).toEqual({ indentSize: 4, keywordCase: 'UPPER', maxLineWidth: 100, commaStyle: 'TRAILING' })
    const f = store.update({ format: { indentSize: 99, keywordCase: 'LOWER', maxLineWidth: 10, commaStyle: 'LEADING' } }).format
    expect(f).toEqual({ indentSize: 8, keywordCase: 'LOWER', maxLineWidth: 40, commaStyle: 'LEADING' })
    expect(store.update({ format: { ...f, keywordCase: 'weird' as never } }).format.keywordCase).toBe('UPPER')
  })
})
