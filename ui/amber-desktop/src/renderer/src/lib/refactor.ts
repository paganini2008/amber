import { create } from 'zustand'
import type { AiCheckParams, AiCheckResult, FormatResult, OptimizeParams, OptimizeResult } from '@shared/protocol'
import { t } from '@/i18n'
import { analyzeParams, currentMetadata, useStore } from '@/store'
import { api, describeError, isNotAvailable, newRequestId } from './api'

/**
 * "Refactor script", locally and without AI: every verified rewrite that can be applied together, pass after
 * pass (removing a sort can make copies identical, so a CTE can follow), then the layout of Format script.
 * Only rewrites that keep the result the same for any data are used (the same rows and columns, proven from
 * the SQL or from metadata); one that can differ in an edge case is never part of a refactoring.
 * The result opens in Change impact against the current text; nothing reaches the editor until Apply.
 */

export interface RefactorOptions {
  /** Lay the result out like Format script. */
  format: boolean
}

export interface RefactorDone {
  before: string
  after: string
  /** Applied rewrites by title, with how often. */
  applied: { title: string; count: number }[]
  passes: number
  formatted: boolean
  /** The engine's logic check of the whole script, before against after; null when it could not run. */
  check: { logicKept: boolean; differences: string[] } | null
}

/** Differences that mean the business logic itself moved: conditions, values, sources, outputs, lineage, writes. */
const LOGIC = ['Conditions', 'Literal values', 'Source tables', 'Output columns', 'Lineage of', 'Written objects', 'Grouping columns']

/**
 * Whether a refactoring kept the logic, from the engine check (run in English so the prefixes match). Only the
 * rules' own proven changes may remain: ordering words (a removed sort or DISTINCT) and nothing else.
 */
export function logicKept(differences: string[]): boolean {
  return !differences.some((d) => LOGIC.some((p) => d.startsWith(p)))
}

type RefactorState = { status: 'idle' } | { status: 'running'; pass: number } | ({ status: 'ready' } & RefactorDone) | { status: 'error'; message: string }

interface RefactorStore {
  state: RefactorState
  run(options: RefactorOptions): Promise<void>
  /** Puts the refactored text into the editor as one undoable step. */
  apply(): void
  reset(): void
}

const MAX_PASSES = 4

/**
 * Rules whose rewrite writes a condition differently (NOT IN as NOT EXISTS, a function on a column as a range,
 * a correlated subquery as a join). Each is proven by its rule and can be applied on its own in Suggestions,
 * but a refactoring uses only rewrites the independent logic check confirms too: same conditions, same values.
 */
export const CONDITION_REWRITES = ['AMB-S004', 'AMB-S008', 'AMB-P003']

/** Merges what one pass applied into the running list. */
export function addApplied(list: { title: string; count: number }[], titles: string[]): { title: string; count: number }[] {
  const out = list.map((x) => ({ ...x }))
  for (const title of titles) {
    const hit = out.find((x) => x.title === title)
    if (hit) hit.count += 1
    else out.push({ title, count: 1 })
  }
  return out
}

let token = 0

export const useRefactor = create<RefactorStore>((set, get) => ({
  state: { status: 'idle' },

  async run(options) {
    const app = useStore.getState()
    const { doc, dialect, settings, usedFallback } = app
    if (!doc || doc.edited) return
    const mine = ++token
    const before = doc.text
    let text = before
    let applied: { title: string; count: number }[] = []
    let passes = 0
    for (let pass = 1; pass <= MAX_PASSES; pass++) {
      set({ state: { status: 'running', pass } })
      const base = analyzeParams({ ...doc, text, modified: true }, dialect, settings, usedFallback, currentMetadata(app))
      const rules = { disabled: [...new Set([...(base.rules?.disabled ?? []), ...CONDITION_REWRITES])], severity: base.rules?.severity ?? {} }
      const params: OptimizeParams = { ...base, rules, statements: [], includeEdgeCases: false }
      const r = await api().engine.call<OptimizeResult>('rewrite.optimize', params, newRequestId('refactor'))
      if (mine !== token) return
      if (!r.ok) {
        set({ state: { status: 'error', message: isNotAvailable(r.error) ? t('refactor.unavailable') : describeError(r.error) } })
        return
      }
      const next = r.value.optimizedText
      if (!next || next === text) break
      applied = addApplied(applied, r.value.statements.flatMap((s) => (s.optimized !== s.original ? s.applied.map((a) => a.title) : [])))
      text = next
      passes = pass
    }
    let formatted = false
    if (options.format) {
      const f = settings?.format
      const params = {
        dialect,
        text,
        ...(f ? { indentSize: f.indentSize, keywordCase: f.keywordCase, maxLineWidth: f.maxLineWidth, commaStyle: f.commaStyle } : {})
      }
      const r = await api().engine.call<FormatResult>('format', params, newRequestId('format'))
      if (mine !== token) return
      if (r.ok && r.value.changed && r.value.text !== text) {
        text = r.value.text
        formatted = true
      }
    }
    let check: RefactorDone['check'] = null
    if (text !== before) {
      const params: AiCheckParams = { dialect, original: before, rewritten: text, mask: {}, ...currentMetadata(app) }
      const c = await api().engine.call<AiCheckResult>('ai.check', { ...params, locale: 'en' }, newRequestId('check'))
      if (mine !== token) return
      if (c.ok) check = { logicKept: c.value.label !== 'INVALID_SQL' && logicKept(c.value.differences ?? []), differences: c.value.differences ?? [] }
    }
    const done: RefactorDone = { before, after: text, applied, passes, formatted, check }
    set({ state: { status: 'ready', ...done } })
    if (text !== before) {
      void useStore.getState().compare({ name: doc.name, text: before, path: doc.path ?? null }, { name: t('refactor.afterName', { name: doc.name }), text, path: null })
    }
  },

  apply() {
    const state = get().state
    const app = useStore.getState()
    if (state.status !== 'ready' || !app.doc || app.doc.text !== state.before) {
      app.toast(t('app.toast.fixesStale'), 'error')
      return
    }
    app.replaceScriptText(state.after, t('refactor.applied'))
    app.closeCompare()
    app.setView('suggestions')
    set({ state: { status: 'idle' } })
  },

  reset() {
    token += 1
    set({ state: { status: 'idle' } })
  }
}))

// Another script, or a new analysis of a changed one, makes a prepared refactoring stale.
useStore.subscribe((s, prev) => {
  if (s.doc?.token !== prev.doc?.token) useRefactor.getState().reset()
})

