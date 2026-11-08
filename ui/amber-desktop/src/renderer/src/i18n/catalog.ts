import type { LanguageId } from '@shared/api'
import { formatMessage, lookup, type Params, type Paths } from './core'
import { en, type En } from './en'
import { es } from './es'
import { zhCN } from './zh-CN'

/** Every message key, checked against the English catalog. */
export type MessageKey = Paths<En>

export const CATALOGS: Record<LanguageId, En> = { en, 'zh-CN': zhCN, es }

/** The message for `key` in `language` (English when that language lacks it), formatted. */
export function translate(language: LanguageId, key: MessageKey, params?: Params): string {
  return translateOptional(language, key, params) ?? key
}

/** Like translate, for keys built at run time (rule ids, engine enums); undefined when unknown. */
export function translateOptional(language: LanguageId, key: string, params?: Params): string | undefined {
  const message = lookup(CATALOGS[language], key) ?? lookup(en, key)
  return message === undefined ? undefined : formatMessage(message, params, language)
}
