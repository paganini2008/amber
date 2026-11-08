import { describe, expect, it } from 'vitest'
import { listOllamaModels, streamChat, testProvider, validateBaseUrl, type FetchLike } from './gateway'

function streamResponse(chunks: string[]): Response {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const c of chunks) controller.enqueue(encoder.encode(c))
      controller.close()
    }
  })
  return new Response(body, { status: 200 })
}


/** A response whose chunks arrive with the given delays (ms); it fails when the request's signal aborts. */
function slowResponse(chunks: [number, string][], signal: AbortSignal | null | undefined): Response {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    async start(controller) {
      signal?.addEventListener('abort', () => controller.error(signal.reason))
      for (const [delay, text] of chunks) {
        await new Promise((r) => setTimeout(r, delay))
        if (signal?.aborted) return
        controller.enqueue(encoder.encode(text))
      }
      controller.close()
    }
  })
  return new Response(body, { status: 200 })
}

describe('AI gateway', () => {
  it('lists Ollama models from /api/tags', async () => {
    const fetchFn: FetchLike = async (url) => {
      expect(url).toBe('http://localhost:11434/api/tags')
      return Response.json({ models: [{ name: 'qwen2.5-coder:7b' }, { name: 'llama3:8b' }] })
    }
    expect(await listOllamaModels(fetchFn, 'http://localhost:11434/')).toEqual(['llama3:8b', 'qwen2.5-coder:7b'])
  })

  it('streams Ollama NDJSON across chunk boundaries', async () => {
    const fetchFn: FetchLike = async () =>
      streamResponse(['{"message":{"content":"Hel"}}\n{"mess', 'age":{"content":"lo"}}\n{"done":true}\n'])
    const parts: string[] = []
    await streamChat(
      fetchFn,
      { kind: 'ollama', baseUrl: 'http://x', model: 'm', temperature: 0, timeoutSec: 5 },
      [{ role: 'user', content: 'hi' }],
      (t) => parts.push(t)
    )
    expect(parts.join('')).toBe('Hello')
  })

  it('streams OpenAI-compatible server-sent events with the key in the header only', async () => {
    let auth = ''
    let sentBody = ''
    const fetchFn: FetchLike = async (url, init) => {
      expect(url).toBe('https://api.example.com/v1/chat/completions')
      auth = (init?.headers as Record<string, string>).Authorization ?? ''
      sentBody = String(init?.body)
      return streamResponse([
        'data: {"choices":[{"delta":{"content":"A"}}]}\n\n',
        'data: {"choices":[{"delta":{"content":"B"}}]}\n\ndata: [DONE]\n\n'
      ])
    }
    const parts: string[] = []
    await streamChat(
      fetchFn,
      { kind: 'openai', baseUrl: 'https://api.example.com/v1', model: 'gpt', temperature: 0.2, timeoutSec: 5, apiKey: 'k' },
      [{ role: 'user', content: 'hi' }],
      (t) => parts.push(t)
    )
    expect(parts.join('')).toBe('AB')
    expect(auth).toBe('Bearer k')
    expect(sentBody).not.toContain('"k"')
  })

  it('reports a missing Ollama model', async () => {
    const fetchFn: FetchLike = async () => Response.json({ models: [{ name: 'a' }] })
    await expect(
      testProvider(fetchFn, { kind: 'ollama', baseUrl: 'http://x', model: 'b', temperature: 0, timeoutSec: 5 })
    ).rejects.toThrow(/not installed/)
  })

  it('rejects non-http URLs', () => {
    expect(() => validateBaseUrl('file:///etc/passwd')).toThrow(/http/)
  })
})

describe('ollama behind an authenticating proxy', () => {
  it('sends the optional bearer token', async () => {
    const seen: string[] = []
    const fetchFn = (async (_url: string, init?: RequestInit) => {
      seen.push((init?.headers as Record<string, string> | undefined)?.Authorization ?? '')
      return new Response(JSON.stringify({ models: [{ name: 'qwen3:8b' }] }), { status: 200 })
    }) as FetchLike
    expect(await listOllamaModels(fetchFn, 'http://ollama.local:11434', 5, 'tok')).toEqual(['qwen3:8b'])
    expect(await listOllamaModels(fetchFn, 'http://ollama.local:11434', 5)).toEqual(['qwen3:8b'])
    expect(seen).toEqual(['Bearer tok', ''])
  })
})

describe('OpenAI-compatible model list', () => {
  it('lists chat models from GET {baseUrl}/models with the key, for any compatible provider', async () => {
    const seen: { url: string; auth: string | null }[] = []
    const fetchFn: FetchLike = async (url, init) => {
      seen.push({ url, auth: new Headers(init?.headers).get('Authorization') })
      return Response.json({
        object: 'list',
        data: [
          { id: 'gpt-4o-mini' },
          { id: 'text-embedding-3-small' },
          { id: 'whisper-1' },
          { id: 'tts-1' },
          { id: 'dall-e-3' },
          { id: 'omni-moderation-latest' },
          { id: 'gpt-4.1' },
          { id: 'gpt-4.1' },
          { id: 'deepseek-chat' }
        ]
      })
    }
    const { listOpenAiModels } = await import('./gateway')
    expect(await listOpenAiModels(fetchFn, 'https://api.deepseek.com/v1/', 'sk-test')).toEqual(['deepseek-chat', 'gpt-4.1', 'gpt-4o-mini'])
    expect(seen[0]).toEqual({ url: 'https://api.deepseek.com/v1/models', auth: 'Bearer sk-test' })
  })

  it('explains a missing key, a missing list and HTTP errors', async () => {
    const { listOpenAiModels } = await import('./gateway')
    const never: FetchLike = async () => {
      throw new Error('should not be called')
    }
    await expect(listOpenAiModels(never, 'https://api.openai.com/v1', null)).rejects.toThrow('Add an API key first.')
    const noList: FetchLike = async () => Response.json({ models: 'nope' })
    await expect(listOpenAiModels(noList, 'https://proxy.local/v1', 'k')).rejects.toThrow(/did not return a model list/)
    const html: FetchLike = async () => new Response('<html>', { status: 200 })
    await expect(listOpenAiModels(html, 'https://proxy.local/v1', 'k')).rejects.toThrow(/did not return a model list/)
    const denied: FetchLike = async () => new Response('bad key', { status: 401, statusText: 'Unauthorized' })
    await expect(listOpenAiModels(denied, 'https://api.openai.com/v1', 'k')).rejects.toThrow(/401/)
  })

  it('leaves out temperature for reasoning models', async () => {
    const bodies: Record<string, unknown>[] = []
    const fetchFn: FetchLike = async (_url, init) => {
      bodies.push(JSON.parse(String(init?.body)) as Record<string, unknown>)
      return streamResponse(['data: [DONE]\n\n'])
    }
    const base = { kind: 'openai' as const, baseUrl: 'https://api.openai.com/v1', temperature: 0.2, timeoutSec: 5, apiKey: 'k' }
    await streamChat(fetchFn, { ...base, model: 'o4-mini' }, [{ role: 'user', content: 'hi' }], () => {})
    await streamChat(fetchFn, { ...base, model: 'gpt-4.1' }, [{ role: 'user', content: 'hi' }], () => {})
    expect(bodies[0]).not.toHaveProperty('temperature')
    expect(bodies[1]).toMatchObject({ temperature: 0.2 })
  })
  it('times out on silence, not on the total length of a streamed answer', async () => {
    const ollama = { kind: 'ollama' as const, baseUrl: 'http://x', model: 'm', temperature: 0, timeoutSec: 1 }
    // About 2.4 s in total, never silent for 1 s: thinking chunks count as progress but are not answer text.
    const steady: [number, string][] = [
      [400, '{"message":{"thinking":"Let me"}}\n'],
      [400, '{"message":{"thinking":" think."}}\n'],
      [400, '{"message":{"content":"A"}}\n'],
      [400, '{"message":{"content":"B"}}\n'],
      [400, '{"message":{"content":"C"}}\n'],
      [400, '{"done":true}\n']
    ]
    const parts: string[] = []
    await streamChat(async (_url, init) => slowResponse(steady, init?.signal), ollama, [{ role: 'user', content: 'hi' }], (t) => parts.push(t))
    expect(parts.join('')).toBe('ABC')

    // Silent for 1.6 s after the first chunk: aborted with a timeout.
    const stalled: [number, string][] = [
      [100, '{"message":{"content":"A"}}\n'],
      [1600, '{"message":{"content":"B"}}\n']
    ]
    await expect(
      streamChat(async (_url, init) => slowResponse(stalled, init?.signal), ollama, [{ role: 'user', content: 'hi' }], () => {})
    ).rejects.toThrow(/timeout/i)
  }, 10000)
})
