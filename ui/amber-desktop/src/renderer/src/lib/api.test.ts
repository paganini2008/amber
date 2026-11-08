import { afterEach, describe, expect, it } from 'vitest'
import type { AmberApi } from '@shared/api'
import { setLanguage } from '../i18n'
import { api, LOCALIZED_METHODS, withLocale } from './api'

/** A fake bridge that records what reaches the preload. */
function fakeBridge(calls: { method: string; params: unknown }[]): AmberApi {
  return {
    engine: {
      analyze: async (params: unknown) => {
        calls.push({ method: 'analyze', params })
        return { ok: true, json: '{"statements":[]}' }
      },
      call: async (method: string, params: unknown) => {
        calls.push({ method, params })
        return { ok: true, json: 'null' }
      }
    }
  } as unknown as AmberApi
}

describe('engine calls carry the UI language', () => {
  afterEach(() => setLanguage('en'))

  it('for every method whose result has prose, never for ai.buildContext', async () => {
    const calls: { method: string; params: unknown }[] = []
    ;(globalThis as unknown as { window: unknown }).window = { amber: fakeBridge(calls) }
    setLanguage('zh-CN')
    const localized = ['analyze', 'doc.generate', 'diff', 'rewrite.suggest', 'rewrite.apply', 'rewrite.optimize', 'ai.check', 'references', 'er.model', 'engine.rules']
    expect([...LOCALIZED_METHODS].sort()).toEqual([...localized].sort())
    await api().engine.analyze({ dialect: 'oracle', text: 'select 1 from dual' }, 'r1')
    for (const m of localized.filter((x) => x !== 'analyze')) await api().engine.call(m as never, m === 'engine.rules' ? {} : { dialect: 'oracle' })
    await api().engine.call('ai.buildContext', { dialect: 'oracle', text: 'x' })
    await api().engine.call('format', { dialect: 'oracle', text: 'x' })
    for (const c of calls) {
      const locale = (c.params as { locale?: string }).locale
      if (LOCALIZED_METHODS.has(c.method)) expect(locale, c.method).toBe('zh-CN')
      else expect(locale, c.method).toBeUndefined()
    }
    expect(calls.map((c) => c.method)).toContain('engine.rules')
  })

  it('follows the current language and adds a params object when there is none', () => {
    setLanguage('es')
    expect(withLocale('engine.rules', undefined)).toEqual({ locale: 'es' })
    setLanguage('en')
    expect(withLocale('analyze', { dialect: 'h2' })).toEqual({ dialect: 'h2', locale: 'en' })
    expect(withLocale('ai.buildContext', { dialect: 'h2' })).toEqual({ dialect: 'h2' })
  })
})
