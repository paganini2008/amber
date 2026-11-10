import type { AiMessage } from '../../shared/api'
import { acceptsTemperature, isChatModel } from '../../shared/aiModels'
import { tm } from '../i18n'

/** Network access is injected so the gateway can be tested without a server. */
export type FetchLike = (url: string, init?: RequestInit) => Promise<Response>

export interface ProviderConfig {
  kind: 'ollama' | 'openai'
  baseUrl: string
  model: string
  temperature: number
  timeoutSec: number
  apiKey?: string | null
}

export function validateBaseUrl(baseUrl: string): URL {
  let url: URL
  try {
    url = new URL(baseUrl)
  } catch {
    throw new Error(`"${baseUrl}" is not a valid URL.`)
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error(tm('main.ai.httpOnly'))
  }
  return url
}

function join(baseUrl: string, path: string): string {
  validateBaseUrl(baseUrl)
  return baseUrl.replace(/\/+$/, '') + path
}

function withTimeout(signal: AbortSignal | undefined, timeoutSec: number): AbortSignal {
  const timeout = AbortSignal.timeout(Math.max(1, timeoutSec) * 1000)
  return signal ? AbortSignal.any([signal, timeout]) : timeout
}

/**
 * A timeout that restarts whenever the stream shows progress: a streamed answer may take far longer than
 * the timeout in total (thinking models reason for minutes), but it must never go silent that long.
 */
function idleTimeout(signal: AbortSignal | undefined, timeoutSec: number): { signal: AbortSignal; bump: () => void; done: () => void } {
  const idle = new AbortController()
  const ms = Math.max(1, timeoutSec) * 1000
  const fire = (): void => idle.abort(new DOMException('The operation was aborted due to timeout', 'TimeoutError'))
  let timer = setTimeout(fire, ms)
  return {
    signal: signal ? AbortSignal.any([signal, idle.signal]) : idle.signal,
    bump: () => {
      clearTimeout(timer)
      timer = setTimeout(fire, ms)
    },
    done: () => clearTimeout(timer)
  }
}

async function ensureOk(response: Response): Promise<void> {
  if (response.ok) return
  let detail = ''
  try {
    detail = (await response.text()).slice(0, 300)
  } catch {
    // ignore
  }
  throw new Error(`${tm('main.ai.providerAnswered', { status: `${response.status} ${response.statusText}`.trim() })}${detail ? `: ${detail}` : ''}`)
}

/** Ollama needs no key, but one behind an authenticating proxy takes a bearer token. */
function bearer(apiKey?: string | null): Record<string, string> {
  return apiKey ? { Authorization: `Bearer ${apiKey}` } : {}
}

export async function listOllamaModels(fetchFn: FetchLike, baseUrl: string, timeoutSec = 15, apiKey?: string | null): Promise<string[]> {
  const response = await fetchFn(join(baseUrl, '/api/tags'), { headers: bearer(apiKey), signal: withTimeout(undefined, timeoutSec) })
  await ensureOk(response)
  const body = (await response.json()) as { models?: { name?: string; model?: string }[] }
  return (body.models ?? []).map((m) => m.name ?? m.model ?? '').filter(Boolean).sort()
}

/**
 * The chat models an OpenAI-compatible provider lists at `GET {baseUrl}/models` (OpenAI,
 * DeepSeek, DashScope compatible mode, proxies, Ollama's /v1…), sorted, without embedding,
 * speech, image and moderation models.
 */
export async function listOpenAiModels(fetchFn: FetchLike, baseUrl: string, apiKey: string | null | undefined, timeoutSec = 15): Promise<string[]> {
  if (!apiKey) throw new Error(tm('main.ai.addKey'))
  const response = await fetchFn(join(baseUrl, '/models'), { headers: { Authorization: `Bearer ${apiKey}` }, signal: withTimeout(undefined, timeoutSec) })
  await ensureOk(response)
  let body: { data?: { id?: unknown }[] } | null
  try {
    body = (await response.json()) as { data?: { id?: unknown }[] }
  } catch {
    body = null
  }
  if (!body || !Array.isArray(body.data)) throw new Error(tm('main.ai.noModelList'))
  const ids = body.data.map((m) => (typeof m.id === 'string' ? m.id : '')).filter(isChatModel)
  return [...new Set(ids)].sort((a, b) => a.localeCompare(b))
}

/** Checks that the provider answers and the model exists. Returns a short description. */
export async function testProvider(fetchFn: FetchLike, config: ProviderConfig): Promise<string> {
  if (config.kind === 'ollama') {
    const models = await listOllamaModels(fetchFn, config.baseUrl, Math.min(config.timeoutSec, 15), config.apiKey)
    if (!config.model) return tm('main.ai.connectedPick', { count: models.length })
    if (!models.includes(config.model)) throw new Error(tm('main.ai.modelMissing', { model: config.model }))
    return tm('main.ai.modelAvailable', { model: config.model })
  }
  if (!config.apiKey) throw new Error(tm('main.ai.addKey'))
  const response = await fetchFn(join(config.baseUrl, '/models'), {
    headers: { Authorization: `Bearer ${config.apiKey}` },
    signal: withTimeout(undefined, Math.min(config.timeoutSec, 15))
  })
  await ensureOk(response)
  const body = (await response.json()) as { data?: { id?: string }[] }
  const ids = (body.data ?? []).map((m) => m.id ?? '')
  if (config.model && ids.length > 0 && !ids.includes(config.model)) {
    return tm('main.ai.modelNotListed', { model: config.model })
  }
  return tm('main.ai.connected')
}

/**
 * Streams a chat completion. Calls `onDelta` with each text fragment. Resolves when the stream ends.
 */
export async function streamChat(
  fetchFn: FetchLike,
  config: ProviderConfig,
  messages: AiMessage[],
  onDelta: (text: string) => void,
  signal?: AbortSignal
): Promise<void> {
  if (!config.model) throw new Error(tm('main.ai.pickModel'))
  const isOllama = config.kind === 'ollama'
  const url = isOllama ? join(config.baseUrl, '/api/chat') : join(config.baseUrl, '/chat/completions')
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...(isOllama ? bearer(config.apiKey) : {}) }
  if (!isOllama) {
    if (!config.apiKey) throw new Error(tm('main.ai.addKey'))
    headers.Authorization = `Bearer ${config.apiKey}`
  }
  const body = isOllama
    ? { model: config.model, messages, stream: true, options: { temperature: config.temperature } }
    : { model: config.model, messages, stream: true, ...(acceptsTemperature(config.model) ? { temperature: config.temperature } : {}) }
  // The timeout counts silence, not the length of the answer: every chunk (thinking included) restarts it.
  const timeout = idleTimeout(signal, config.timeoutSec)
  try {
    try {
      await readStream(fetchFn, url, headers, body, isOllama, onDelta, timeout)
    } catch (err) {
      // A model we did not know refuses a custom temperature (HTTP 400 before any text): retry once without it.
      if (!isOllama && 'temperature' in body && refusesTemperature(err)) {
        const withoutTemperature: Record<string, unknown> = { ...body }
        delete withoutTemperature.temperature
        await readStream(fetchFn, url, headers, withoutTemperature, isOllama, onDelta, timeout)
      } else {
        throw err
      }
    }
  } finally {
    timeout.done()
  }
}

function refusesTemperature(err: unknown): boolean {
  const message = err instanceof Error ? err.message : String(err)
  return /\b400\b/.test(message) && /temperature/i.test(message)
}

async function readStream(
  fetchFn: FetchLike,
  url: string,
  headers: Record<string, string>,
  body: unknown,
  isOllama: boolean,
  onDelta: (text: string) => void,
  timeout: { signal: AbortSignal; bump: () => void }
): Promise<void> {
  const response = await fetchFn(url, { method: 'POST', headers, body: JSON.stringify(body), signal: timeout.signal })
  await ensureOk(response)
  if (!response.body) throw new Error(tm('main.ai.emptyResponse'))

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const handle = (line: string): boolean => {
    const trimmed = line.trim()
    if (!trimmed) return false
    if (isOllama) {
      const chunk = JSON.parse(trimmed) as { message?: { content?: string; thinking?: string }; done?: boolean; error?: string }
      if (chunk.error) throw new Error(chunk.error)
      if (chunk.message?.content) onDelta(chunk.message.content)
      return chunk.done === true
    }
    if (!trimmed.startsWith('data:')) return false
    const data = trimmed.slice(5).trim()
    if (data === '[DONE]') return true
    const chunk = JSON.parse(data) as { choices?: { delta?: { content?: string } }[]; error?: { message?: string } }
    if (chunk.error) throw new Error(chunk.error.message ?? tm('main.ai.providerError'))
    const text = chunk.choices?.[0]?.delta?.content
    if (text) onDelta(text)
    return false
  }
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    timeout.bump()
    buffer += decoder.decode(value, { stream: true })
    let index: number
    while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, index)
      buffer = buffer.slice(index + 1)
      if (handle(line)) {
        await reader.cancel()
        return
      }
    }
  }
  if (buffer.trim()) handle(buffer)
}
