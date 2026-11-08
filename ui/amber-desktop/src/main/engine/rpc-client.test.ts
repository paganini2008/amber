import { describe, expect, it } from 'vitest'
import { RPC_ERROR } from '../../shared/protocol'
import { JsonRpcClient, LineSplitter, RpcError } from './rpc-client'

function setup(): { client: JsonRpcClient; sent: Record<string, unknown>[] } {
  const sent: Record<string, unknown>[] = []
  const client = new JsonRpcClient((line) => sent.push(JSON.parse(line) as Record<string, unknown>))
  return { client, sent }
}

describe('JsonRpcClient', () => {
  it('sends JSON-RPC 2.0 requests with increasing ids', () => {
    const { client, sent } = setup()
    void client.request('engine.info')
    void client.request('analyze', { dialect: 'h2', text: 'SELECT 1' })
    expect(sent).toEqual([
      { jsonrpc: '2.0', id: 1, method: 'engine.info' },
      { jsonrpc: '2.0', id: 2, method: 'analyze', params: { dialect: 'h2', text: 'SELECT 1' } }
    ])
  })

  it('matches out-of-order responses to their requests', async () => {
    const { client } = setup()
    const a = client.request<string>('a')
    const b = client.request<string>('b')
    client.handleLine('{"jsonrpc":"2.0","id":2,"result":"second"}')
    client.handleLine('{"jsonrpc":"2.0","id":1,"result":"first"}')
    await expect(a).resolves.toBe('first')
    await expect(b).resolves.toBe('second')
    expect(client.pendingCount).toBe(0)
  })

  it('rejects with the engine error code and remembers unsupported methods', async () => {
    const { client } = setup()
    const p = client.request('lineage')
    client.handleLine('{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Unknown method: lineage"}}')
    const error = await p.catch((e: unknown) => e)
    expect(error).toBeInstanceOf(RpcError)
    expect((error as RpcError).code).toBe(RPC_ERROR.METHOD_NOT_FOUND)
    expect((error as RpcError).isMethodNotFound).toBe(true)
    expect(client.isKnownUnsupported('lineage')).toBe(true)
    expect(client.isKnownUnsupported('analyze')).toBe(false)
  })

  it('sends $/cancel when aborted and rejects with -32800', async () => {
    const { client, sent } = setup()
    const controller = new AbortController()
    const p = client.request('analyze', {}, { signal: controller.signal })
    controller.abort()
    await expect(p).rejects.toMatchObject({ code: RPC_ERROR.REQUEST_CANCELLED })
    expect(sent[1]).toEqual({ jsonrpc: '2.0', method: '$/cancel', params: { id: 1 } })
    // A late response is ignored.
    client.handleLine('{"jsonrpc":"2.0","id":1,"result":{}}')
  })

  it('cancels on timeout', async () => {
    const { client, sent } = setup()
    const p = client.request('analyze', {}, { timeoutMs: 10 })
    await expect(p).rejects.toMatchObject({ code: RPC_ERROR.TIMEOUT })
    expect(sent.some((m) => m.method === '$/cancel')).toBe(true)
  })

  it('fails every pending request when the engine exits', async () => {
    const { client } = setup()
    const a = client.request('a')
    const b = client.request('b')
    client.failAll(new RpcError(RPC_ERROR.ENGINE_UNAVAILABLE, 'gone'))
    await expect(a).rejects.toMatchObject({ code: RPC_ERROR.ENGINE_UNAVAILABLE })
    await expect(b).rejects.toMatchObject({ code: RPC_ERROR.ENGINE_UNAVAILABLE })
  })

  it('rejects immediately when the transport is closed', async () => {
    const client = new JsonRpcClient(() => {
      throw new Error('closed')
    })
    await expect(client.request('a')).rejects.toMatchObject({ code: RPC_ERROR.ENGINE_UNAVAILABLE })
  })

  it('ignores non-JSON lines and forwards notifications', () => {
    const notes: string[] = []
    const client = new JsonRpcClient(() => undefined, (method) => notes.push(method))
    client.handleLine('not json')
    client.handleLine('{"jsonrpc":"2.0","method":"$/progress","params":{"id":1}}')
    expect(notes).toEqual(['$/progress'])
  })
})

describe('LineSplitter', () => {
  it('reassembles lines split across chunks, including multi-byte characters', () => {
    const lines: string[] = []
    const splitter = new LineSplitter((l) => lines.push(l))
    const bytes = Buffer.from('{"a":"é"}\r\n{"b":2}\n\n{"c"', 'utf8')
    for (let i = 0; i < bytes.length; i++) splitter.push(bytes.subarray(i, i + 1))
    splitter.push(':3}\n')
    expect(lines).toEqual(['{"a":"é"}', '{"b":2}', '{"c":3}'])
  })

  it('handles a very long line in many chunks in linear time', () => {
    // Regression: a 100 MB answer for a huge script was rescanned for every 64 KB chunk (minutes).
    const lines: string[] = []
    const splitter = new LineSplitter((l) => lines.push(l))
    const chunk = Buffer.from('x'.repeat(64 * 1024))
    const started = performance.now()
    for (let i = 0; i < 640; i++) splitter.push(chunk)
    splitter.push('\n{"next":1}\n')
    expect(performance.now() - started).toBeLessThan(2000)
    expect(lines.map((l) => l.length)).toEqual([640 * 64 * 1024, 10])
  })
})
