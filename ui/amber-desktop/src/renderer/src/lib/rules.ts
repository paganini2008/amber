import type { RuleInfo, RuleSettings, FindingSeverity } from '@shared/protocol'

/** Settings → Rules: pure helpers for grouping, searching and editing the rule settings. */

const CATEGORY_ORDER = ['risk', 'performance', 'maintainability', 'info']

/** Rules by category (risk, performance, maintainability, info, then anything new), ids in order. */
export function groupRules(rules: RuleInfo[]): { category: string; rules: RuleInfo[] }[] {
  const groups = new Map<string, RuleInfo[]>()
  for (const r of rules) groups.set(r.category, [...(groups.get(r.category) ?? []), r])
  const rank = (c: string): number => (CATEGORY_ORDER.includes(c) ? CATEGORY_ORDER.indexOf(c) : CATEGORY_ORDER.length)
  return [...groups.entries()]
    .sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b))
    .map(([category, list]) => ({ category, rules: [...list].sort((a, b) => a.id.localeCompare(b.id)) }))
}

/** Rules whose searchable text contains every word of the query (case-insensitive). */
export function filterRules(rules: RuleInfo[], query: string, text: (id: string, title: string, description: string) => string): RuleInfo[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean)
  if (!words.length) return rules
  return rules.filter((r) => {
    const hay = text(r.id, r.title, r.description).toLowerCase()
    return words.every((w) => hay.includes(w))
  })
}

export function setRuleEnabled(settings: RuleSettings, id: string, enabled: boolean): RuleSettings {
  const disabled = settings.disabled.filter((d) => d !== id)
  if (!enabled) disabled.push(id)
  return { ...settings, disabled: disabled.sort() }
}

/** A severity override, or null for the rule's default. */
export function setRuleSeverity(settings: RuleSettings, id: string, severity: FindingSeverity | null): RuleSettings {
  const next = { ...settings.severity }
  if (severity) next[id] = severity
  else delete next[id]
  return { ...settings, severity: next }
}

/** Rules that differ from their defaults (off, or with a severity other than the default). */
export function changedRuleCount(rules: RuleInfo[], settings: RuleSettings): number {
  const ids = new Set<string>(settings.disabled)
  for (const [id, severity] of Object.entries(settings.severity)) {
    const rule = rules.find((r) => r.id === id)
    if (!rule || rule.severity !== severity) ids.add(id)
  }
  return ids.size
}
