import { LANGUAGES, type LanguageId } from '@shared/api'

/**
 * Message formatting shared by the renderer and the main process. Messages use a small subset of
 * ICU MessageFormat:
 *
 *   {name}                                   interpolation
 *   {count, number}                          a number formatted with Intl for the language
 *   {count, plural, =0 {none} one {# table} other {# tables}}
 *   {kind, select, view {a view} other {a table}}
 *
 * `#` inside a plural branch is the number, formatted with Intl. Plural categories come from
 * Intl.PluralRules; a missing category falls back to `other`. No apostrophe escaping: text may
 * contain apostrophes freely, and messages never need literal braces.
 */

export type Params = Record<string, string | number>

/** A message tree: namespaces of nested objects with string leaves. */
export interface Tree {
  readonly [key: string]: string | Tree
}

/** Every dotted path to a string leaf, e.g. "outline.empty.title". */
export type Paths<T, P extends string = ''> = {
  [K in keyof T & string]: T[K] extends string ? `${P}${K}` : Paths<T[K], `${P}${K}.`>
}[keyof T & string]

/** The saved language, or English. The OS locale is deliberately not consulted. */
export function resolveLanguage(saved: unknown): LanguageId {
  return LANGUAGES.includes(saved as LanguageId) ? (saved as LanguageId) : 'en'
}

export function lookup(tree: Tree, key: string): string | undefined {
  let node: string | Tree | undefined = tree
  for (const part of key.split('.')) {
    if (!node || typeof node === 'string') return undefined
    node = node[part]
  }
  return typeof node === 'string' ? node : undefined
}

/** All leaf keys of a tree, sorted. */
export function leafKeys(tree: Tree, prefix = ''): string[] {
  const out: string[] = []
  for (const [k, v] of Object.entries(tree)) {
    if (typeof v === 'string') out.push(prefix + k)
    else out.push(...leafKeys(v, `${prefix}${k}.`))
  }
  return out.sort()
}

const numberFormats = new Map<string, Intl.NumberFormat>()
const pluralRules = new Map<string, Intl.PluralRules>()

function numberFormat(locale: string): Intl.NumberFormat {
  let f = numberFormats.get(locale)
  if (!f) numberFormats.set(locale, (f = new Intl.NumberFormat(locale)))
  return f
}

function pluralRule(locale: string): Intl.PluralRules {
  let r = pluralRules.get(locale)
  if (!r) pluralRules.set(locale, (r = new Intl.PluralRules(locale)))
  return r
}

/** Index of the brace closing the one at `open`, or -1. */
function closing(text: string, open: number): number {
  let depth = 0
  for (let i = open; i < text.length; i++) {
    if (text[i] === '{') depth++
    else if (text[i] === '}' && --depth === 0) return i
  }
  return -1
}

/** "=0 {none} one {# item} other {# items}" as a map. */
function options(text: string): Map<string, string> {
  const out = new Map<string, string>()
  let i = 0
  while (i < text.length) {
    while (i < text.length && /\s/.test(text[i]!)) i++
    const start = i
    while (i < text.length && !/[\s{]/.test(text[i]!)) i++
    const key = text.slice(start, i)
    while (i < text.length && /\s/.test(text[i]!)) i++
    if (!key || text[i] !== '{') break
    const end = closing(text, i)
    if (end < 0) break
    out.set(key, text.slice(i + 1, end))
    i = end + 1
  }
  return out
}

function argument(body: string, params: Params, locale: string, hash: number | null): string {
  const parts = body.split(',')
  const name = parts[0]!.trim()
  const value = params[name]
  if (parts.length === 1) return value === undefined ? `{${name}}` : String(value)
  const type = parts[1]!.trim()
  if (type === 'number') return typeof value === 'number' ? numberFormat(locale).format(value) : String(value ?? `{${name}}`)
  const rest = body.slice(body.indexOf(',', body.indexOf(',') + 1) + 1)
  const opts = options(rest)
  if (type === 'plural') {
    const n = Number(value ?? 0)
    const branch = opts.get(`=${n}`) ?? opts.get(pluralRule(locale).select(n)) ?? opts.get('other') ?? ''
    return render(branch, params, locale, n)
  }
  if (type === 'select') return render(opts.get(String(value)) ?? opts.get('other') ?? '', params, locale, hash)
  return value === undefined ? `{${name}}` : String(value)
}

function render(message: string, params: Params, locale: string, hash: number | null): string {
  let out = ''
  let i = 0
  while (i < message.length) {
    const c = message[i]!
    if (c === '{') {
      const end = closing(message, i)
      if (end < 0) return out + message.slice(i)
      out += argument(message.slice(i + 1, end), params, locale, hash)
      i = end + 1
    } else if (c === '#' && hash !== null) {
      out += numberFormat(locale).format(hash)
      i++
    } else {
      out += c
      i++
    }
  }
  return out
}

export function formatMessage(message: string, params: Params = {}, locale = 'en'): string {
  return message.includes('{') ? render(message, params, locale, null) : message
}

/** The argument names a message uses (at any depth), sorted; used to compare locales. */
export function placeholders(message: string): string[] {
  const names = new Set<string>()
  const walk = (text: string): void => {
    let i = 0
    while (i < text.length) {
      if (text[i] !== '{') {
        i++
        continue
      }
      const end = closing(text, i)
      if (end < 0) return
      const body = text.slice(i + 1, end)
      const parts = body.split(',')
      names.add(parts[0]!.trim())
      if (parts.length > 2) for (const v of options(body.slice(body.indexOf(',', body.indexOf(',') + 1) + 1)).values()) walk(v)
      i = end + 1
    }
  }
  walk(message)
  return [...names].sort()
}
