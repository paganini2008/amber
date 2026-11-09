import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DEFAULT_MAVEN_REPO, type DriverArtifact } from '../../shared/drivers'
import {
  DriverDownloadError,
  downloadDriver,
  driverPath,
  effectiveRepository,
  installedDriver,
  installedDriverSync,
  isRequestId,
  removeDriver,
  type FetchLike
} from './download'

// A small fake driver; the real catalog entries are checked in shared/drivers.test.ts. No test here
// touches the network: every fetch is a stub.
const BYTES = Buffer.from('PK\u0003\u0004 not really a jar, but pinned all the same '.repeat(40))
const fake: DriverArtifact = {
  id: 'db2',
  name: 'IBM JCC',
  vendor: 'IBM',
  groupId: 'com.ibm.db2',
  artifactId: 'jcc',
  version: '12.1.4.0',
  file: 'jcc-12.1.4.0.jar',
  size: BYTES.length,
  sha256: createHash('sha256').update(BYTES).digest('hex'),
  license: { name: 'IPLA', url: 'https://example.com/license' }
}

let root: string
beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), 'amber-drivers-'))
})
afterEach(() => rmSync(root, { recursive: true, force: true }))

/** A response that streams `data` in `chunks` pieces. */
function streamed(data: Buffer, chunks = 4, headers: Record<string, string> = {}): Response {
  const size = Math.ceil(data.length / chunks)
  let i = 0
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (i >= data.length) return controller.close()
      controller.enqueue(new Uint8Array(data.subarray(i, i + size)))
      i += size
    }
  })
  return new Response(body, { status: 200, headers })
}

const stub = (make: () => Response): FetchLike & ReturnType<typeof vi.fn> => vi.fn(async () => make()) as never
const leftovers = (): string[] => (existsSync(join(root, fake.id)) ? readdirSync(join(root, fake.id)) : [])

describe('downloadDriver', () => {
  it('streams the jar from the Maven layout URL, reports progress and keeps it when size and hash match', async () => {
    const fetch = stub(() => streamed(BYTES, 4, { 'content-length': String(BYTES.length) }))
    const progress: [number, number][] = []
    const path = await downloadDriver(fake, { fetch, root, repository: 'https://repo.example.com/maven2/', progressIntervalMs: 0, onProgress: (r, t) => progress.push([r, t]) })
    expect(fetch).toHaveBeenCalledOnce()
    expect(fetch.mock.calls[0]![0]).toBe('https://repo.example.com/maven2/com/ibm/db2/jcc/12.1.4.0/jcc-12.1.4.0.jar')
    expect(path).toBe(join(root, 'db2', 'jcc-12.1.4.0.jar'))
    expect(readFileSync(path).equals(BYTES)).toBe(true)
    expect(leftovers()).toEqual(['jcc-12.1.4.0.jar'])
    // grows chunk by chunk to the full size, with the total each time
    expect(progress.length).toBeGreaterThanOrEqual(4)
    expect(progress.every(([, t]) => t === BYTES.length)).toBe(true)
    expect(progress.map(([r]) => r)).toEqual([...progress.map(([r]) => r)].sort((a, b) => a - b))
    expect(progress.at(-1)).toEqual([BYTES.length, BYTES.length])
    expect(await installedDriver(root, fake)).toBe(path)
    expect(installedDriverSync(root, fake)).toBe(path)
  })

  it('rejects a file with the wrong hash and leaves nothing behind', async () => {
    const tampered = Buffer.from(BYTES)
    tampered[10] = tampered[10]! ^ 0xff
    const error = await downloadDriver(fake, { fetch: stub(() => streamed(tampered)), root, repository: DEFAULT_MAVEN_REPO }).catch((e) => e)
    expect(error).toBeInstanceOf(DriverDownloadError)
    expect(error.reason).toBe('hash')
    expect(leftovers()).toEqual([])
    expect(await installedDriver(root, fake)).toBeNull()
  })

  it('rejects a file of the wrong size (shorter, longer, or announced wrong) and leaves nothing behind', async () => {
    for (const make of [
      () => streamed(BYTES.subarray(0, BYTES.length - 7)),
      () => streamed(Buffer.concat([BYTES, Buffer.from('extra')])),
      () => streamed(BYTES, 2, { 'content-length': String(BYTES.length + 1) })
    ]) {
      const error = await downloadDriver(fake, { fetch: stub(make), root, repository: DEFAULT_MAVEN_REPO }).catch((e) => e)
      expect(error.reason).toBe('size')
      expect(leftovers()).toEqual([])
    }
  })

  it('reports an HTTP error status and an unreachable repository', async () => {
    const http = await downloadDriver(fake, { fetch: stub(() => new Response('no', { status: 404 })), root, repository: DEFAULT_MAVEN_REPO }).catch((e) => e)
    expect(http).toMatchObject({ reason: 'http', status: 404 })
    const offline = await downloadDriver(fake, {
      fetch: vi.fn(async () => {
        throw new TypeError('net::ERR_INTERNET_DISCONNECTED')
      }),
      root,
      repository: DEFAULT_MAVEN_REPO
    }).catch((e) => e)
    expect(offline.reason).toBe('offline')
    expect(leftovers()).toEqual([])
  })

  it('cancels a running download and removes the temporary file', async () => {
    // Sends half the file, then stalls until cancelled.
    let sent = false
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (sent) return new Promise<void>(() => undefined)
        sent = true
        controller.enqueue(new Uint8Array(BYTES.subarray(0, 100)))
      }
    })
    const controller = new AbortController()
    let sawPart = false
    const done = downloadDriver(fake, {
      fetch: stub(() => new Response(body)),
      root,
      repository: DEFAULT_MAVEN_REPO,
      signal: controller.signal,
      progressIntervalMs: 0,
      onProgress: (received) => {
        if (received === 100) {
          sawPart = leftovers().some((f) => f.endsWith('.part'))
          controller.abort()
        }
      }
    })
    const error = await done.catch((e) => e)
    expect(sawPart).toBe(true)
    expect(error.reason).toBe('cancelled')
    expect(leftovers()).toEqual([])
    expect(existsSync(join(root, fake.id))).toBe(false)
  })

  it('gives up after the overall timeout', async () => {
    const body = new ReadableStream<Uint8Array>({ pull: () => new Promise<void>(() => undefined) })
    const error = await downloadDriver(fake, { fetch: stub(() => new Response(body)), root, repository: DEFAULT_MAVEN_REPO, timeoutMs: 30 }).catch((e) => e)
    expect(error.reason).toBe('timeout')
    expect(leftovers()).toEqual([])
  })

  it('reuses a valid file already there without downloading, and replaces a corrupt one', async () => {
    mkdirSync(join(root, fake.id), { recursive: true })
    writeFileSync(driverPath(root, fake), BYTES)
    const fetch = stub(() => streamed(BYTES))
    const progress: number[] = []
    expect(await downloadDriver(fake, { fetch, root, repository: DEFAULT_MAVEN_REPO, onProgress: (r) => progress.push(r) })).toBe(driverPath(root, fake))
    expect(fetch).not.toHaveBeenCalled()
    expect(progress).toEqual([BYTES.length])

    const corrupt = Buffer.from(BYTES)
    corrupt[0] = 0
    writeFileSync(driverPath(root, fake), corrupt)
    expect(await installedDriver(root, fake)).toBeNull()
    await downloadDriver(fake, { fetch, root, repository: DEFAULT_MAVEN_REPO })
    expect(fetch).toHaveBeenCalledOnce()
    expect(readFileSync(driverPath(root, fake)).equals(BYTES)).toBe(true)
  })

  it('rejects repositories that are not HTTPS, before any request', async () => {
    const fetch = stub(() => streamed(BYTES))
    for (const repository of ['http://repo1.maven.org/maven2', 'ftp://mirror/maven2', 'file:///tmp/m2', 'https://user:pw@mirror/m2', 'not a url', '']) {
      const error = await downloadDriver(fake, { fetch, root, repository }).catch((e) => e)
      expect(error.reason, repository).toBe('repository')
    }
    expect(fetch).not.toHaveBeenCalled()
  })

  it('removes a downloaded driver', async () => {
    await downloadDriver(fake, { fetch: stub(() => streamed(BYTES)), root, repository: DEFAULT_MAVEN_REPO })
    await removeDriver(root, fake)
    expect(existsSync(join(root, fake.id))).toBe(false)
    expect(installedDriverSync(root, fake)).toBeNull()
    await expect(removeDriver(root, fake)).resolves.toBeUndefined()
  })
})

describe('IPC arguments', () => {
  it('accepts only short request ids', () => {
    expect(isRequestId('driver-12')).toBe(true)
    for (const bad of ['', 'a b', 'x'.repeat(101), 12, null, undefined, {}, '../x/y']) expect(isRequestId(bad), String(bad)).toBe(false)
  })

  it('uses the user setting, then the configured default, then Maven Central; never a non-HTTPS one', () => {
    expect(effectiveRepository('https://mirror.example.com/m2/', 'https://cfg.example.com/m2')).toBe('https://mirror.example.com/m2')
    expect(effectiveRepository('', 'https://cfg.example.com/m2')).toBe('https://cfg.example.com/m2')
    expect(effectiveRepository('http://mirror/m2', 'http://cfg/m2')).toBe(DEFAULT_MAVEN_REPO)
    expect(effectiveRepository(undefined, undefined)).toBe(DEFAULT_MAVEN_REPO)
  })
})
