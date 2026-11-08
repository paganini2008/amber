import { afterEach, describe, expect, it, vi } from 'vitest'
import { LANGUAGES } from '@shared/api'
import { CATALOGS, translate, translateOptional } from './catalog'
import { formatMessage, leafKeys, lookup, placeholders, resolveLanguage, type Tree } from './core'

const en = CATALOGS.en as unknown as Tree
const sampleParams = (message: string): Record<string, string | number> =>
  Object.fromEntries(placeholders(message).map((name) => [name, 3]))

describe('locale files', () => {
  it('have exactly the English keys', () => {
    const keys = leafKeys(en)
    expect(keys.length).toBeGreaterThan(100)
    for (const l of LANGUAGES) expect(leafKeys(CATALOGS[l] as unknown as Tree), l).toEqual(keys)
  })

  it('use the same placeholders as English', () => {
    for (const key of leafKeys(en)) {
      const expected = placeholders(lookup(en, key)!)
      for (const l of LANGUAGES) expect(placeholders(lookup(CATALOGS[l] as unknown as Tree, key)!), `${l} ${key}`).toEqual(expected)
    }
  })

  it('have no empty messages and format without leftover braces', () => {
    for (const l of LANGUAGES) {
      for (const key of leafKeys(en)) {
        const message = lookup(CATALOGS[l] as unknown as Tree, key)!
        expect(message.trim(), `${l} ${key}`).not.toBe('')
        const out = formatMessage(message, sampleParams(message), l)
        expect(out, `${l} ${key}`).not.toMatch(/[{}]/)
      }
    }
  })

  it('keep plural messages complete for every language', () => {
    for (const l of LANGUAGES) {
      for (const key of leafKeys(en)) {
        const message = lookup(CATALOGS[l] as unknown as Tree, key)!
        if (!/,\s*plural\s*,/.test(message)) continue
        expect(message, `${l} ${key} needs an "other" branch`).toMatch(/other\s*\{/)
      }
    }
  })

  it('survive a pseudo-locale: longer text keeps every placeholder working', () => {
    // Pseudo-localization: each text run 40 % longer, wrapped in brackets. Placeholders and ICU
    // syntax must stay intact, which proves no message relies on string concatenation of parts.
    const pseudo = (message: string): string => {
      let out = ''
      let depth = 0
      for (const c of message) {
        if (c === '{') depth++
        if (c === '}') depth--
        out += c
        if (depth === 0 && /[a-z]/i.test(c) && Math.random() < 0.4) out += '~'
      }
      return `[${out}]`
    }
    for (const key of leafKeys(en)) {
      const message = pseudo(lookup(en, key)!)
      const out = formatMessage(message, sampleParams(message), 'en')
      expect(out.startsWith('[') && out.endsWith(']'), key).toBe(true)
      expect(out, key).not.toMatch(/[{}]/)
    }
  })

  it('keep menu and kind labels short enough for menus and badges', () => {
    // Long-string check for the places with the least room. Layout of the rest is checked at
    // 960×600 by scripts/ui-smoke.mjs in every language.
    for (const l of LANGUAGES) {
      for (const key of leafKeys(en).filter((k) => /^(menu|kinds)\./.test(k))) {
        const text = lookup(CATALOGS[l] as unknown as Tree, key)!
        expect(text.length, `${l} ${key}: "${text}"`).toBeLessThanOrEqual(40)
      }
    }
  })
})

describe('formatMessage', () => {
  it('interpolates and pluralizes per language', () => {
    expect(formatMessage('Hello {name}', { name: 'Ana' })).toBe('Hello Ana')
    const tables = '{count, plural, =0 {no tables} one {# table} other {# tables}}'
    expect(formatMessage(tables, { count: 0 })).toBe('no tables')
    expect(formatMessage(tables, { count: 1 })).toBe('1 table')
    expect(formatMessage(tables, { count: 1234 })).toBe('1,234 tables')
    expect(formatMessage('{count, plural, one {# tabla} other {# tablas}}', { count: 12345 }, 'es')).toBe('12.345 tablas')
    expect(formatMessage('{count, plural, other {# x}}', { count: 2 }, 'zh-CN')).toBe('2 x')
  })

  it('supports select, number and nested arguments', () => {
    const m = '{kind, select, view {the view {name}} other {the table {name}}}'
    expect(formatMessage(m, { kind: 'view', name: 'v1' })).toBe('the view v1')
    expect(formatMessage(m, { kind: 'x', name: 't1' })).toBe('the table t1')
    expect(formatMessage('{n, number} rows', { n: 1500 }, 'en')).toBe('1,500 rows')
    expect(formatMessage('Line {line}', { line: 1500 })).toBe('Line 1500')
    expect(formatMessage('Missing {x}')).toBe('Missing {x}')
    expect(placeholders('{a} {n, plural, one {# {b}} other {# {c}}}')).toEqual(['a', 'b', 'c', 'n'])
  })

  it('falls back to English, then to the key or the engine text', () => {
    expect(translate('es', 'common.cancel')).toBe('Cancelar')
    expect(translate('en', 'common.tables', { count: 2 })).toBe('2 tables')
    expect(translateOptional('zh-CN', 'findings.rules.AMB-X999.title')).toBeUndefined()
    expect(translateOptional('es', 'findings.rules.AMB-S001.title')).toBe('Producto cartesiano')
  })
})

describe('resolveLanguage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
  })

  it('is English by default and never follows the OS locale', () => {
    vi.stubGlobal('navigator', { language: 'zh-CN', languages: ['zh-CN', 'es-ES'] })
    vi.stubEnv('LANG', 'es_ES.UTF-8')
    expect(resolveLanguage(undefined)).toBe('en')
    expect(resolveLanguage(null)).toBe('en')
    expect(resolveLanguage('')).toBe('en')
    expect(resolveLanguage('fr')).toBe('en')
    expect(resolveLanguage('zh')).toBe('en')
  })

  it('uses the saved choice', () => {
    expect(resolveLanguage('en')).toBe('en')
    expect(resolveLanguage('zh-CN')).toBe('zh-CN')
    expect(resolveLanguage('es')).toBe('es')
  })
})
