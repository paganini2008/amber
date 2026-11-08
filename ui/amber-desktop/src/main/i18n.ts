import type { LanguageId } from '../shared/api'
import { translate, type MessageKey } from '../renderer/src/i18n/catalog'
import type { Params } from '../renderer/src/i18n/core'

/**
 * Main-process messages (menus, dialogs, engine and AI errors) in the language chosen in Settings.
 * English until the settings are read; the OS locale is not used.
 */
let language: LanguageId = 'en'

export function setMainLanguage(value: LanguageId): void {
  language = value
}

export function mainLanguage(): LanguageId {
  return language
}

export function tm(key: MessageKey, params?: Params): string {
  return translate(language, key, params)
}
