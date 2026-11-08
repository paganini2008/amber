/**
 * Model suggestions for the AI settings. Model names change often; this is the one place to
 * update them. "Load models" shows what a provider actually offers, and any name can be typed.
 */

export interface ModelPreset {
  id: string
  /** A few words on why to pick it. */
  note: string
  /** Shown first, under "Recommended for SQL review". */
  recommended?: boolean
}

/** For api.openai.com. Other OpenAI-compatible providers use their own names (use Load models). */
export const OPENAI_PRESETS: ModelPreset[] = [
  { id: 'gpt-4.1', note: 'reads long scripts well', recommended: true },
  { id: 'gpt-4.1-mini', note: 'faster and cheaper', recommended: true },
  { id: 'o4-mini', note: 'reasoning model', recommended: true },
  { id: 'gpt-4o', note: 'general purpose' },
  { id: 'gpt-4o-mini', note: 'small and fast' },
  { id: 'o3-mini', note: 'reasoning model' }
]

/** Tags from the Ollama library. Not installed ones can be pulled with `ollama pull <name>`. */
export const OLLAMA_PRESETS: ModelPreset[] = [
  { id: 'qwen2.5-coder:7b', note: 'code model, runs on a laptop', recommended: true },
  { id: 'qwen2.5-coder:14b', note: 'code model, more accurate', recommended: true },
  { id: 'qwen3:8b', note: 'general model with reasoning', recommended: true },
  { id: 'llama3.1:8b', note: 'general purpose' },
  { id: 'deepseek-coder-v2:16b', note: 'code model' },
  { id: 'codellama:13b', note: 'older code model' }
]

/** Model ids that cannot hold a chat: embeddings, speech, images, moderation, legacy completions. */
const NOT_CHAT = /(embed|^bge-|[-:/]bge|rerank|tts|whisper|transcribe|dall-e|gpt-image|moderation|davinci|babbage|realtime|audio|search-preview|computer-use)/i

export function isChatModel(id: string): boolean {
  return Boolean(id.trim()) && !NOT_CHAT.test(id)
}

/** True for OpenAI's own API, where the OpenAI presets apply. */
export function isOpenAiHost(baseUrl: string): boolean {
  try {
    return new URL(baseUrl).hostname === 'api.openai.com'
  } catch {
    return false
  }
}

export interface ModelOption {
  id: string
  note?: string
  group: 'recommended' | 'available' | 'more'
  /** Whether the provider lists it: true / false once loaded, null before. */
  available: boolean | null
}

/**
 * The combobox list: recommended presets, then what the provider lists (not already shown),
 * then the remaining presets. Without presets (another provider's host) only loaded ids appear.
 */
export function modelOptions(presets: ModelPreset[], loaded: string[] | null, showPresets = true): ModelOption[] {
  const chat = loaded ? [...new Set(loaded.filter(isChatModel))].sort((a, b) => a.localeCompare(b)) : null
  const has = (id: string): boolean | null => (chat ? chat.includes(id) : null)
  const shown = showPresets ? presets : []
  const out: ModelOption[] = []
  for (const p of shown.filter((p) => p.recommended)) out.push({ id: p.id, note: p.note, group: 'recommended', available: has(p.id) })
  const presetIds = new Set(shown.map((p) => p.id))
  for (const id of chat ?? []) if (!presetIds.has(id)) out.push({ id, group: 'available', available: true })
  for (const p of shown.filter((p) => !p.recommended)) out.push({ id: p.id, note: p.note, group: 'more', available: has(p.id) })
  return out
}

/** Reasoning models (o1, o3, o4…) only accept the default temperature. */
export function acceptsTemperature(model: string): boolean {
  return !/^o\d/i.test(model.trim())
}
