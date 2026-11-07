/**
 * Theme token sets (design.md §12.3). This is the single place themes are defined: the renderer
 * applies them as CSS custom properties, and the editor, graphs, exports and the native title bar
 * all derive from them. Add a theme by adding an entry here.
 */
export type ConcreteThemeId = 'amber-light' | 'amber-dark' | 'resin' | 'high-contrast'

export interface ThemeTokens {
  canvas: string
  surface: string
  ink: string
  inkMuted: string
  line: string
  amber: string
  amberInk: string
  riskHigh: string
  riskMedium: string
  riskLow: string
  /** App chrome (title bar, sidebar, tab bar, status bar): the deep second color. */
  chrome: string
  /** Text on chrome. */
  chromeInk: string
  /** Secondary text on chrome. */
  chromeMuted: string
  /** Amber used as text or marks on chrome. */
  chromeAmber: string
}

export interface ThemeDefinition {
  id: ConcreteThemeId
  label: string
  dark: boolean
  tokens: ThemeTokens
}

export const THEMES: Record<ConcreteThemeId, ThemeDefinition> = {
  'amber-light': {
    id: 'amber-light',
    label: 'Amber Light',
    dark: false,
    tokens: {
      canvas: '#F1ECE1',
      surface: '#FAF7F0',
      ink: '#17232A',
      inkMuted: '#52616A',
      line: '#DFD5C2',
      amber: '#E8920C',
      amberInk: '#8A4B00',
      riskHigh: '#BF2F2C',
      riskMedium: '#7438AD',
      riskLow: '#1C5FAE',
      chrome: '#0F2A33',
      chromeInk: '#E6EFEC',
      chromeMuted: '#9DB5B8',
      chromeAmber: '#FFB547'
    }
  },
  'amber-dark': {
    id: 'amber-dark',
    label: 'Amber Dark',
    dark: true,
    tokens: {
      canvas: '#0B1E25',
      surface: '#10272F',
      ink: '#E6EEEC',
      inkMuted: '#93AAAD',
      line: '#21404A',
      amber: '#FFA928',
      amberInk: '#FFC25E',
      riskHigh: '#F4676B',
      riskMedium: '#BC92F4',
      riskLow: '#74AEF4',
      chrome: '#071820',
      chromeInk: '#E6EEEC',
      chromeMuted: '#8EA6AA',
      chromeAmber: '#FFC25E'
    }
  },
  resin: {
    id: 'resin',
    label: 'Resin',
    dark: true,
    tokens: {
      canvas: '#1A140E',
      surface: '#231A12',
      ink: '#F2E8D9',
      inkMuted: '#B8A488',
      line: '#3A2C1E',
      amber: '#FFA51F',
      amberInk: '#FFBE55',
      riskHigh: '#F06A5E',
      riskMedium: '#C49AEE',
      riskLow: '#7FB0E8',
      chrome: '#160F09',
      chromeInk: '#F2E8D9',
      chromeMuted: '#B8A488',
      chromeAmber: '#FFBE55'
    }
  },
  'high-contrast': {
    id: 'high-contrast',
    label: 'High Contrast',
    dark: true,
    tokens: {
      canvas: '#000000',
      surface: '#0A0A0A',
      ink: '#FFFFFF',
      inkMuted: '#D0D0D0',
      line: '#7A7A7A',
      amber: '#FFC000',
      amberInk: '#FFD24D',
      riskHigh: '#FF5C5C',
      riskMedium: '#D6A8FF',
      riskLow: '#8CC4FF',
      chrome: '#000000',
      chromeInk: '#FFFFFF',
      chromeMuted: '#D0D0D0',
      chromeAmber: '#FFD24D'
    }
  }
}

export const TOKEN_CSS_NAMES: Record<keyof ThemeTokens, string> = {
  canvas: '--canvas',
  surface: '--surface',
  ink: '--ink',
  inkMuted: '--ink-muted',
  line: '--line',
  amber: '--amber',
  amberInk: '--amber-ink',
  riskHigh: '--risk-high',
  riskMedium: '--risk-medium',
  riskLow: '--risk-low',
  chrome: '--chrome',
  chromeInk: '--chrome-ink',
  chromeMuted: '--chrome-muted',
  chromeAmber: '--chrome-amber'
}

/** Resolves "system" to a concrete theme. */
export function resolveTheme(theme: string, systemDark: boolean): ThemeDefinition {
  if (theme in THEMES) return THEMES[theme as ConcreteThemeId]
  return systemDark ? THEMES['amber-dark'] : THEMES['amber-light']
}
