import clsx from 'clsx'
import { Database, Lightbulb, RotateCcw, Search } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import type { Settings, SettingsPatch } from '@shared/api'
import type { FindingSeverity } from '@shared/protocol'
import { t, tOr, useLanguage } from '@/i18n'
import { findingTitle, ruleDescription, severityText } from '@/lib/kinds'
import { changedRuleCount, filterRules, groupRules, setRuleEnabled, setRuleSeverity } from '@/lib/rules'
import { useStore } from '@/store'
import { SeverityIcon } from './StatusParts'
import { Button, Select, Spinner, Switch, inputClass } from './ui'

const SEVERITIES: FindingSeverity[] = ['HIGH', 'MEDIUM', 'LOW', 'INFO']

/**
 * Settings → Rules: every finding rule the engine knows, grouped by category, with an on/off switch
 * and a severity override. Saved in settings and passed with every engine call that computes
 * findings; the script is analyzed again when they change.
 */
export function RulesSettings({ settings, update }: { settings: Settings; update: (patch: SettingsPatch) => void }): React.JSX.Element {
  const catalog = useStore((s) => s.ruleCatalog)
  const load = useStore((s) => s.loadRuleCatalog)
  const language = useLanguage()
  const [query, setQuery] = useState('')
  useEffect(() => {
    void load()
  }, [load])

  const rules = settings.rules
  // Titles and descriptions are searched in the UI language and in English (rule ids too).
  const groups = useMemo(() => {
    const text = (id: string, title: string, description: string): string =>
      [id, title, description, findingTitle({ rule: id, title }), ruleDescription({ id, description })].join(' ')
    return groupRules(filterRules(catalog.items, query, text))
    // eslint-disable-next-line react-hooks/exhaustive-deps -- the language changes the searchable text
  }, [catalog.items, query, language])
  const changed = changedRuleCount(catalog.items, rules)

  if ((catalog.status === 'loading' || catalog.status === 'idle') && catalog.items.length === 0) {
    return (
      <p className="flex items-center gap-2 py-2 text-sm text-ink-muted">
        <Spinner size={12} /> {t('rules.loading')}
      </p>
    )
  }
  if (catalog.status === 'unavailable') return <p className="py-2 text-sm text-ink-muted">{t('rules.unavailable')}</p>
  if (catalog.status === 'error') return <p className="py-2 text-sm text-risk-high">{t('rules.error', { message: catalog.error ?? '' })}</p>

  return (
    <div className="space-y-3">
      <p className="text-sm text-ink-muted">{t('rules.lead')}</p>
      <div className="flex flex-wrap items-center gap-2">
        <label className="relative min-w-[14rem] flex-1">
          <Search size={13} className="pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-ink-muted" aria-hidden />
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t('rules.search')}
            aria-label={t('rules.search')}
            className={clsx(inputClass, 'w-full pl-7')}
          />
        </label>
        <span className="text-xs text-ink-muted">{t('rules.changed', { count: changed })}</span>
        <Button size="sm" variant="ghost" disabled={changed === 0} onClick={() => update({ rules: { disabled: [], severity: {} } })}>
          <RotateCcw size={13} aria-hidden /> {t('rules.reset')}
        </Button>
      </div>
      {groups.length === 0 ? <p className="text-sm text-ink-muted">{t('rules.noMatch', { query })}</p> : null}
      <div className="max-h-[52vh] space-y-4 overflow-y-auto pr-1">
        {groups.map((g) => (
          <section key={g.category} aria-label={tOr(`rules.category.${g.category}`, g.category)}>
            <h3 className="sticky top-0 z-[1] bg-surface pb-1 text-xs font-semibold tracking-wide text-ink-muted uppercase">
              {tOr(`rules.category.${g.category}`, t('rules.category.other'))}
            </h3>
            <ul className="divide-y divide-line rounded-[6px] border border-line">
              {g.rules.map((r) => {
                const enabled = !rules.disabled.includes(r.id)
                const override = rules.severity[r.id] ?? ''
                return (
                  <li key={r.id} className={clsx('flex items-start gap-3 px-3 py-2', !enabled && 'opacity-60')}>
                    <div className="pt-0.5">
                      <Switch checked={enabled} onChange={(on) => update({ rules: setRuleEnabled(rules, r.id, on) })} label={t('rules.enable', { id: r.id })} />
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
                        <span className="code text-xs text-ink-faint">{r.id}</span>
                        <span className="text-sm font-medium text-ink">{findingTitle({ rule: r.id, title: r.title })}</span>
                        {!enabled ? <span className="text-xs text-ink-muted">{t('rules.off')}</span> : null}
                      </div>
                      <p className="mt-0.5 text-xs text-ink-muted">{ruleDescription(r)}</p>
                      <div className="mt-1 flex flex-wrap gap-1.5 empty:hidden">
                        {r.needsMetadata ? (
                          <span className="rule-tag" title={t('rules.needsMetadataTitle')}>
                            <Database size={11} aria-hidden /> {t('rules.needsMetadata')}
                          </span>
                        ) : null}
                        {r.hasQuickFix ? (
                          <span className="rule-tag" title={t('rules.hasQuickFixTitle')}>
                            <Lightbulb size={11} aria-hidden /> {t('rules.hasQuickFix')}
                          </span>
                        ) : null}
                      </div>
                    </div>
                    <div className="flex shrink-0 items-center gap-1.5">
                      <SeverityIcon severity={override || r.severity} size={12} />
                      <Select
                        label={t('rules.severity', { id: r.id })}
                        value={override || 'default'}
                        disabled={!enabled}
                        onChange={(v) => update({ rules: setRuleSeverity(rules, r.id, v === 'default' ? null : (v as FindingSeverity)) })}
                        options={[
                          { value: 'default', label: t('rules.severityDefault', { severity: severityText(r.severity) }) },
                          ...SEVERITIES.map((s) => ({ value: s, label: severityText(s) }))
                        ]}
                        className="w-44"
                      />
                    </div>
                  </li>
                )
              })}
            </ul>
          </section>
        ))}
      </div>
    </div>
  )
}
