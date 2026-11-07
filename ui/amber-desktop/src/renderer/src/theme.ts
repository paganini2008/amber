import type { Settings } from '@shared/api'
import { TOKEN_CSS_NAMES, resolveTheme, type ThemeDefinition, type ThemeTokens } from '@shared/themes'
import { structurePalette } from './lib/structure'

let active: ThemeDefinition = resolveTheme('system', window.matchMedia('(prefers-color-scheme: dark)').matches)

/** Applies a theme's tokens as CSS custom properties on the root element. */
export function applyTheme(settings: Pick<Settings, 'theme' | 'fontSize'>, systemDark: boolean): ThemeDefinition {
  active = resolveTheme(settings.theme, systemDark)
  const root = document.documentElement
  for (const [key, cssName] of Object.entries(TOKEN_CSS_NAMES)) {
    root.style.setProperty(cssName, active.tokens[key as keyof ThemeTokens])
  }
  // Accents of the Structure view's block kinds (shared with its SVG export).
  for (const [kind, color] of Object.entries(structurePalette(active.tokens))) root.style.setProperty(`--qb-${kind}`, color)
  root.dataset.theme = active.id
  root.dataset.dark = String(active.dark)
  root.style.setProperty('--code-size', `${settings.fontSize}px`)
  return active
}

export function activeTokens(): ThemeTokens {
  return active.tokens
}
