import { useSyncExternalStore } from 'react'
import type { LanguageId } from '@shared/api'
import { translate, translateOptional, type MessageKey } from './catalog'
import type { Params } from './core'

/**
 * Renderer i18n: the current language (from Settings → General; English by default, never the OS
 * locale), `t` for messages, `useLanguage` for components that must re-render when it changes, and
 * Intl helpers for numbers, durations and dates.
 */

export type { MessageKey } from './catalog'
export type { Params } from './core'

let current: LanguageId = 'en'
const listeners = new Set<() => void>()

export function getLanguage(): LanguageId {
  return current
}

export function setLanguage(language: LanguageId): void {
  if (language === current) return
  current = language
  if (typeof document !== 'undefined') document.documentElement.lang = language
  for (const l of listeners) l()
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** The current language; the calling component re-renders when it changes. */
export function useLanguage(): LanguageId {
  return useSyncExternalStore(subscribe, getLanguage, getLanguage)
}

export function t(key: MessageKey, params?: Params): string {
  return translate(current, key, params)
}

/** For keys built at run time: the message, or `fallback` (usually the engine's own text). */
export function tOr(key: string, fallback: string, params?: Params): string {
  return translateOptional(current, key, params) ?? fallback
}

const cache = new Map<string, Intl.NumberFormat>()
function nf(options: Intl.NumberFormatOptions = {}): Intl.NumberFormat {
  const k = current + JSON.stringify(options)
  let f = cache.get(k)
  if (!f) cache.set(k, (f = new Intl.NumberFormat(current, options)))
  return f
}

/** Numbers, durations and dates for the current language. */
export const fmt = {
  number: (n: number, options?: Intl.NumberFormatOptions): string => nf(options).format(n),
  percent: (fraction: number): string => nf({ style: 'percent', maximumFractionDigits: 0 }).format(fraction),
  /** "15 ms", "1.2 s", "2 min" */
  duration: (ms: number): string => {
    if (ms < 1000) return nf({ style: 'unit', unit: 'millisecond', unitDisplay: 'short', maximumFractionDigits: 0 }).format(ms)
    if (ms < 60_000) return nf({ style: 'unit', unit: 'second', unitDisplay: 'short', maximumFractionDigits: 1 }).format(ms / 1000)
    return nf({ style: 'unit', unit: 'minute', unitDisplay: 'short', maximumFractionDigits: 1 }).format(ms / 60_000)
  },
  /** File sizes: "12 KB", "3.4 MB". */
  bytes: (n: number): string => {
    if (n < 1024) return nf({ style: 'unit', unit: 'byte', unitDisplay: 'short' }).format(n)
    if (n < 1024 * 1024) return nf({ style: 'unit', unit: 'kilobyte', unitDisplay: 'short', maximumFractionDigits: 0 }).format(n / 1024)
    return nf({ style: 'unit', unit: 'megabyte', unitDisplay: 'short', maximumFractionDigits: 1 }).format(n / 1024 / 1024)
  },
  date: (d: Date | number | string): string => new Intl.DateTimeFormat(current, { dateStyle: 'medium' }).format(new Date(d)),
  dateTime: (d: Date | number | string): string =>
    new Intl.DateTimeFormat(current, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(d)),
  time: (d: Date | number | string): string => new Intl.DateTimeFormat(current, { timeStyle: 'short' }).format(new Date(d)),
  /** "a, b and c" in English, "a, b y c" in Spanish, the enumeration comma in Chinese. */
  list: (items: string[], type: 'conjunction' | 'disjunction' = 'conjunction'): string =>
    new Intl.ListFormat(current, { style: 'long', type }).format(items)
}
