import { create } from 'zustand'
import type { AiProviderId } from '@shared/api'
import type { AiBuildContextParams, AiCheckParams, AiCheckResult, AiContext } from '@shared/protocol'
import { t } from '@/i18n'
import { analyzeParams, currentMetadata, metadataParams, useStore } from '@/store'
import { conversationMessages, parseSuggestions, sliceRange, type AiSuggestion } from './ai'
import { api, describeError, isNotAvailable, newRequestId } from './api'
import { aiFixNotes, aiRefactorNotes, type SuggestionEntry } from './suggestions'

/**
 * "Fix with AI" in the Suggestions view: per statement, the engine builds a masked context with the
 * problems to fix as notes, the provider answers, and every rewritten statement goes through `ai.check`
 * before it can be applied. An external provider shows what will be sent and waits for "Send".
 */

export type AiFixCheck = { ok: true; result: AiCheckResult } | { ok: false; message: string }

export interface AiFixSuggestion {
  suggestion: AiSuggestion
  check: AiFixCheck | null
}

interface Prepared {
  context: Omit<AiContext, 'mask'>
  mask: Record<string, string>
  params: AiBuildContextParams
  /** The text the statement range points into. */
  text: string
  provider: AiProviderId
  /** Fix the listed problems, or refactor the whole statement. */
  mode: AiFixMode
}

export type AiFixMode = 'fix' | 'refactor'

export type AiFixState =
  | { status: 'building' }
  | ({ status: 'confirm' } & Prepared)
  | ({ status: 'waiting'; requestId: string } & Prepared)
  | ({ status: 'checking' } & Prepared)
  | ({ status: 'done'; answer: string; suggestions: AiFixSuggestion[] } & Prepared)
  | { status: 'error'; message: string }
  | { status: 'unavailable' }

interface AiFixesStore {
  /** By statement id; cleared when the script is analyzed again. */
  fixes: Record<number, AiFixState>
  start(statement: number, entries: SuggestionEntry[], mode?: AiFixMode): Promise<void>
  send(statement: number): Promise<void>
  cancel(statement: number): void
  reset(): void
}

/** Bumped on every reset, so answers to an older analysis are dropped. */
let generation = 0

export const useAiFixes = create<AiFixesStore>((set, get) => {
  const put = (statement: number, state: AiFixState | null): void => {
    const fixes = { ...get().fixes }
    if (state) fixes[statement] = state
    else delete fixes[statement]
    set({ fixes })
  }

  return {
    fixes: {},

    async start(statement, entries, mode = 'fix') {
      const app = useStore.getState()
      const provider = app.settings?.activeAiProvider ?? null
      if (!provider) {
        app.openDialog('settings', 'ai')
        return
      }
      const { doc, index, dialect, settings, usedFallback } = app
      if (!doc || !index || doc.edited || !index.byId.has(statement)) return
      const gen = generation
      put(statement, { status: 'building' })
      const params: AiBuildContextParams = {
        ...analyzeParams(doc, dialect, settings, usedFallback, currentMetadata(app)),
        statements: [statement],
        tokenBudget: settings?.ai.maxContextTokens,
        mask: settings?.ai[provider].maskIdentifiers ?? true,
        notes: mode === 'refactor' ? aiRefactorNotes(entries) : aiFixNotes(entries)
      }
      const r = await api().engine.call<AiContext>('ai.buildContext', params, newRequestId('ai'))
      if (gen !== generation) return
      if (!r.ok) {
        put(statement, isNotAvailable(r.error) ? { status: 'unavailable' } : { status: 'error', message: describeError(r.error) })
        return
      }
      const { mask, ...context } = r.value
      const prepared: Prepared = {
        context: { ...context, trimmed: context.trimmed ?? [], statements: context.statements ?? [] },
        mask: mask ?? {},
        params,
        text: doc.text,
        provider,
        mode
      }
      // A local model (Ollama) gets the context right away; an external API waits for the developer.
      put(statement, { status: 'confirm', ...prepared })
      if (provider === 'ollama') await get().send(statement)
    },

    async send(statement) {
      const state = get().fixes[statement]
      if (!state || state.status !== 'confirm') return
      const prepared: Prepared = { context: state.context, mask: state.mask, params: state.params, text: state.text, provider: state.provider, mode: state.mode }
      const gen = generation
      const requestId = newRequestId('chat')
      put(statement, { status: 'waiting', requestId, ...prepared })
      const r = await api().ai.chat(prepared.provider, conversationMessages(prepared.context.system, [], prepared.context.user), requestId)
      if (gen !== generation || get().fixes[statement]?.status !== 'waiting') return
      if (!r.ok) {
        put(statement, { status: 'error', message: r.error.message })
        return
      }
      const answer = typeof r.value === 'string' ? r.value : ''
      const parsed = parseSuggestions(answer) ?? []
      put(statement, { status: 'checking', ...prepared })
      const suggestions = await Promise.all(parsed.map((s) => checkSuggestion(statement, s, prepared)))
      if (gen !== generation) return
      put(statement, { status: 'done', answer, suggestions, ...prepared })
    },

    cancel(statement) {
      const state = get().fixes[statement]
      if (state?.status === 'waiting') void api().ai.cancel(state.requestId)
      put(statement, null)
    },

    reset() {
      generation += 1
      for (const state of Object.values(get().fixes)) if (state.status === 'waiting') void api().ai.cancel(state.requestId)
      set({ fixes: {} })
    }
  }
})

/** Every rewrite is checked against the statement it replaces; names come back unmasked from the engine. */
async function checkSuggestion(statement: number, suggestion: AiSuggestion, prepared: Prepared): Promise<AiFixSuggestion> {
  if (!suggestion.rewrittenSql) return { suggestion, check: null }
  const range = useStore.getState().index?.byId.get(statement)?.range
  if (!range) return { suggestion, check: { ok: false, message: t('ai.notChecked.noStatement') } }
  const params: AiCheckParams = {
    dialect: prepared.params.dialect,
    original: sliceRange(prepared.text, range),
    rewritten: suggestion.rewrittenSql,
    mask: prepared.mask,
    ...metadataParams(prepared.params)
  }
  const c = await api().engine.call<AiCheckResult>('ai.check', params, newRequestId('check'))
  if (c.ok) return { suggestion, check: { ok: true, result: { ...c.value, differences: c.value.differences ?? [], diagnostics: c.value.diagnostics ?? [] } } }
  return { suggestion, check: { ok: false, message: isNotAvailable(c.error) ? t('ai.notChecked.unavailable') : t('ai.notChecked.error', { reason: describeError(c.error) }) } }
}

/**
 * The only AI rewrite "Apply" accepts: the engine found no difference at all (same outputs, sources, grouping,
 * lineage, conditions and values). Anything else may change the business logic and is shown, never applied.
 */
export function applicableSql(s: AiFixSuggestion): string | null {
  return s.check?.ok && s.check.result.label === 'STRUCTURE_PRESERVED' && s.check.result.sql ? s.check.result.sql : null
}

// A new analysis (or another script) makes every statement id and range stale.
useStore.subscribe((state, previous) => {
  if (state.analysis !== previous.analysis) useAiFixes.getState().reset()
})
