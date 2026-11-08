import type { AiCheckLabel, Range } from '@shared/protocol'
import { t, tOr } from '../i18n'

/**
 * Helpers for the optional AI suggestions: reading the model's answer, restoring masked names for
 * display, and finding the source text a suggestion is about. Nothing here talks to a model.
 */

/** The shape `ai.buildContext` asks the model to answer with. Every field may be missing. */
export interface AiSuggestion {
  title?: string
  target?: { statement?: number | null } | null
  rationale?: string
  expectedEffect?: string
  risk?: string
  rewrittenSql?: string
}

/**
 * Reads `{"suggestions":[…]}` from the model output, tolerating code fences and text around
 * the JSON. Returns null when no such object can be found.
 */
export function parseSuggestions(raw: string): AiSuggestion[] | null {
  const output = raw.replace(/<think>[\s\S]*?(<\/think>|$)/gi, '') // reasoning models (qwen3, deepseek-r1)
  const candidates: string[] = []
  const fenced = /```(?:json)?\s*([\s\S]*?)```/i.exec(output)
  if (fenced?.[1]) candidates.push(fenced[1])
  candidates.push(output)
  const first = output.indexOf('{')
  const last = output.lastIndexOf('}')
  if (first >= 0 && last > first) candidates.push(output.slice(first, last + 1))
  // Models sometimes add a second object or prose after the answer: take each balanced object.
  candidates.push(...balancedObjects(output))
  for (const c of candidates) {
    try {
      const parsed = JSON.parse(c.trim()) as unknown
      // Small models often answer with the bare list instead of {"suggestions": […]}.
      const list = Array.isArray(parsed) ? parsed : parsed && typeof parsed === 'object' ? (parsed as { suggestions?: unknown }).suggestions : undefined
      if (Array.isArray(list)) {
        return (list.filter((s) => s && typeof s === 'object') as AiSuggestion[]).map(normalize)
      }
    } catch {
      // try the next candidate
    }
  }
  return null
}

/** Every top-level `{…}` in the text, string- and escape-aware. */
export function balancedObjects(text: string): string[] {
  const out: string[] = []
  let depth = 0
  let start = -1
  let inString = false
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (inString) {
      if (c === '\\') i++
      else if (c === '"') inString = false
      continue
    }
    if (c === '"') inString = true
    else if (c === '{') {
      if (depth++ === 0) start = i
    } else if (c === '}' && depth > 0 && --depth === 0) {
      out.push(text.slice(start, i + 1))
    }
  }
  return out
}

function normalize(s: AiSuggestion): AiSuggestion {
  const str = (v: unknown): string | undefined => (typeof v === 'string' && v.trim() ? v : undefined)
  const raw = s.target?.statement as unknown
  const id = typeof raw === 'number' ? raw : typeof raw === 'string' && /^\d+$/.test(raw.trim()) ? Number(raw.trim()) : null
  return {
    title: str(s.title),
    target: { statement: id },
    rationale: str(s.rationale),
    expectedEffect: str(s.expectedEffect),
    risk: str(s.risk),
    rewrittenSql: str(s.rewrittenSql)
  }
}

function escapeRegex(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * Restores masked names (`T1`, `C3`, `'S2'`) in text meant for display. Only whole tokens are
 * replaced, longest alias first, so `T12` is not read as `T1`. SQL to check goes through
 * `ai.check`, which unmasks with the dialect's lexer instead.
 */
export function unmaskText(text: string, mask: Record<string, string> | null | undefined): string {
  const aliases = Object.keys(mask ?? {}).filter(Boolean)
  if (!text || aliases.length === 0) return text
  aliases.sort((a, b) => b.length - a.length)
  const pattern = new RegExp(`(?<![A-Za-z0-9_$#])(?:${aliases.map(escapeRegex).join('|')})(?![A-Za-z0-9_$#])`, 'g')
  return text.replace(pattern, (m) => mask![m] ?? m)
}

/**
 * The statement a suggestion targets: its own `target.statement` when that id exists, else the
 * statement the context was built for (statement scope). Null when neither is known.
 */
export function targetStatement(suggestion: AiSuggestion, exists: (id: number) => boolean, scopeStatement: number | null): number | null {
  const id = suggestion.target?.statement
  if (typeof id === 'number' && exists(id)) return id
  return scopeStatement !== null && exists(scopeStatement) ? scopeStatement : null
}

/** The source text of a range (offsets are UTF-16 indexes, end exclusive). */
export function sliceRange(text: string, range: Range): string {
  return text.slice(range.start.offset, range.end.offset)
}

/** How a check result is worded. Never "equivalent" or "safe": a parser compared structure only. */
export function checkLabelText(label: AiCheckLabel | string): string {
  if (!label) return t('ai.check.none')
  return tOr(`ai.check.${label}`, label.charAt(0) + label.slice(1).toLowerCase().replace(/_/g, ' '))
}

/** One exchange with the model: what was sent as the user message, and what came back. */
export interface ChatTurn {
  /** The user message as sent (masked). The first turn is the engine's context. */
  prompt: string
  /** The model's raw answer (masked); empty while it streams. */
  answer: string
}

/**
 * The messages of the next request in the same conversation: the engine's system message, then
 * every earlier turn as user / assistant pairs, then the new (already masked) user message.
 * Turns without an answer (failed or cancelled) are left out, so the chat stays well formed.
 */
export function conversationMessages(system: string, turns: ChatTurn[], next: string): { role: 'system' | 'user' | 'assistant'; content: string }[] {
  const out: { role: 'system' | 'user' | 'assistant'; content: string }[] = [{ role: 'system', content: system }]
  for (const t of turns) {
    if (!t.answer.trim()) continue
    out.push({ role: 'user', content: t.prompt }, { role: 'assistant', content: t.answer })
  }
  out.push({ role: 'user', content: next })
  return out
}

/** Rough token count (about 4 characters per token), as the engine estimates. */
export function estimateTokens(messages: { content: string }[]): number {
  return Math.ceil(messages.reduce((n, m) => n + m.content.length, 0) / 4)
}

/**
 * Adds one streamed fragment to the answer so far. Call it in the stream listener, never inside a React
 * state updater: updaters may run twice (StrictMode), which once doubled every fragment.
 */
export function appendDelta(answer: string, fragment: string): string {
  return answer + fragment
}
