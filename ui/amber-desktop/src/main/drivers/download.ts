import { createHash, randomBytes } from 'node:crypto'
import { createReadStream, statSync } from 'node:fs'
import { mkdir, open, rename, rm, rmdir, stat, type FileHandle } from 'node:fs/promises'
import { join } from 'node:path'
import { artifactUrl, DEFAULT_MAVEN_REPO, normalizeRepositoryUrl, type DriverArtifact, type DriverFailure } from '../../shared/drivers'

/**
 * On-demand JDBC drivers (see shared/drivers.ts). A driver is downloaded only after the user accepted
 * the vendor's license in the page; this module fetches the pinned jar from a Maven repository,
 * streams it to a temporary file next to its destination while hashing it, and keeps it only when
 * both the size and the SHA-256 match. The finished file is renamed into place in one step, so a
 * half-written jar is never visible as `<root>/<id>/<file>`.
 *
 * No Electron import here: the caller passes the fetch to use (Electron's net.fetch in the app, so
 * the system proxy applies; a stub in tests).
 */

export type FetchLike = (url: string, init: { signal: AbortSignal }) => Promise<Response>

export class DriverDownloadError extends Error {
  constructor(
    readonly reason: DriverFailure,
    message: string,
    readonly status?: number
  ) {
    super(message)
    this.name = 'DriverDownloadError'
  }
}

/** Overall limit for one download: slow links still finish 15 MB, a stalled one gives up. */
export const DEFAULT_DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000

export function driverPath(root: string, d: DriverArtifact): string {
  return join(root, d.id, d.file)
}

/** The repository to use: the user's setting, else the configured default, else Maven Central. */
export function effectiveRepository(setting: string | undefined, configured: string | undefined): string {
  return normalizeRepositoryUrl(setting) ?? normalizeRepositoryUrl(configured) ?? DEFAULT_MAVEN_REPO
}

/** IPC request ids: short tokens made by the page (`newRequestId`). */
export function isRequestId(value: unknown): value is string {
  return typeof value === 'string' && /^[\w.:-]{1,100}$/.test(value)
}

export async function sha256File(path: string): Promise<string> {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(path)) hash.update(chunk as Buffer)
  return hash.digest('hex')
}

/** Files whose checksum was verified, by path, with the size and mtime seen then. */
const verified = new Map<string, string>()
const stamp = (size: number, mtimeMs: number): string => `${size}:${mtimeMs}`

/** The downloaded jar when it is there and its checksum matches (hashed once per file version). */
export async function installedDriver(root: string, d: DriverArtifact): Promise<string | null> {
  const path = driverPath(root, d)
  try {
    const info = await stat(path)
    if (!info.isFile() || info.size !== d.size) return null
    const s = stamp(info.size, info.mtimeMs)
    if (verified.get(path) === s) return path
    if ((await sha256File(path)) !== d.sha256) return null
    verified.set(path, s)
    return path
  } catch {
    return null
  }
}

/**
 * Cheap check used for every engine request: the jar exists with the pinned size. The checksum was
 * verified when it was downloaded (and is again whenever the page asks for the status).
 */
export function installedDriverSync(root: string, d: DriverArtifact): string | null {
  const path = driverPath(root, d)
  try {
    const info = statSync(path)
    return info.isFile() && info.size === d.size ? path : null
  } catch {
    return null
  }
}

/** Deletes the downloaded driver (its folder, which holds nothing else). */
export async function removeDriver(root: string, d: DriverArtifact): Promise<void> {
  verified.delete(driverPath(root, d))
  await rm(join(root, d.id), { recursive: true, force: true })
}

export interface DownloadOptions {
  fetch: FetchLike
  /** `<userData>/drivers` */
  root: string
  /** Repository base URL; must be HTTPS. */
  repository: string
  timeoutMs?: number
  signal?: AbortSignal
  /** Bytes received so far and the expected total; throttled, and always called once at the end. */
  onProgress?: (received: number, total: number) => void
  progressIntervalMs?: number
}

const IO_CODES = new Set(['EACCES', 'EPERM', 'ENOSPC', 'EROFS', 'EEXIST', 'EISDIR', 'ENOTDIR', 'EMFILE', 'EBUSY'])

/** Downloads the pinned jar, or returns the verified copy already there. Resolves to its path. */
export async function downloadDriver(d: DriverArtifact, o: DownloadOptions): Promise<string> {
  const repository = normalizeRepositoryUrl(o.repository)
  if (!repository) throw new DriverDownloadError('repository', `The driver repository must be an HTTPS URL: ${o.repository}`)
  const url = artifactUrl(repository, d)
  const existing = await installedDriver(o.root, d)
  if (existing) {
    o.onProgress?.(d.size, d.size)
    return existing
  }

  const dir = join(o.root, d.id)
  const target = join(dir, d.file)
  const tmp = join(dir, `.${d.file}.${randomBytes(6).toString('hex')}.part`)
  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, o.timeoutMs ?? DEFAULT_DOWNLOAD_TIMEOUT_MS)
  const onAbort = (): void => controller.abort()
  if (o.signal?.aborted) controller.abort()
  o.signal?.addEventListener('abort', onAbort)
  let file: FileHandle | null = null
  let reader: ReadableStreamDefaultReader<Uint8Array> | null = null
  // A pending read of a stream that ignores the signal must still end when the user cancels.
  const stopReading = (): void => void reader?.cancel().catch(() => undefined)
  controller.signal.addEventListener('abort', stopReading)
  try {
    if (controller.signal.aborted) throw new Error('aborted')
    await mkdir(dir, { recursive: true })
    const res = await o.fetch(url, { signal: controller.signal })
    if (!res.ok) throw new DriverDownloadError('http', `${url} answered ${res.status}`, res.status)
    const declared = Number(res.headers.get('content-length') ?? '')
    if (declared && declared !== d.size) throw new DriverDownloadError('size', `${url} is ${declared} bytes, expected ${d.size}`)
    if (!res.body) throw new DriverDownloadError('size', `${url} returned no content`)

    file = await open(tmp, 'wx', 0o644)
    const hash = createHash('sha256')
    const interval = o.progressIntervalMs ?? 100
    let received = 0
    let last = 0
    reader = res.body.getReader()
    for (;;) {
      const { done, value } = await reader.read()
      if (controller.signal.aborted) throw new Error('aborted')
      if (done) break
      received += value.byteLength
      if (received > d.size) throw new DriverDownloadError('size', `${url} is larger than ${d.size} bytes`)
      hash.update(value)
      await file.write(value)
      const now = Date.now()
      if (o.onProgress && now - last >= interval) {
        last = now
        o.onProgress(received, d.size)
      }
    }
    await file.close()
    file = null
    if (received !== d.size) throw new DriverDownloadError('size', `${url} is ${received} bytes, expected ${d.size}`)
    const digest = hash.digest('hex')
    if (digest !== d.sha256) throw new DriverDownloadError('hash', `${url} has SHA-256 ${digest}, expected ${d.sha256}`)
    await rename(tmp, target)
    const info = await stat(target)
    verified.set(target, stamp(info.size, info.mtimeMs))
    o.onProgress?.(d.size, d.size)
    return target
  } catch (error) {
    if (controller.signal.aborted) {
      throw timedOut
        ? new DriverDownloadError('timeout', `The download of ${d.file} did not finish in time`)
        : new DriverDownloadError('cancelled', 'Download cancelled')
    }
    if (error instanceof DriverDownloadError) throw error
    const code = (error as NodeJS.ErrnoException | null)?.code
    if (code && IO_CODES.has(code)) throw new DriverDownloadError('io', (error as Error).message)
    throw new DriverDownloadError('offline', (error as Error)?.message || String(error))
  } finally {
    clearTimeout(timer)
    o.signal?.removeEventListener('abort', onAbort)
    controller.signal.removeEventListener('abort', stopReading)
    await file?.close().catch(() => undefined)
    await rm(tmp, { force: true }).catch(() => undefined)
    // A failed first download leaves no empty folder behind (rmdir refuses a folder that holds a jar).
    await rmdir(dir).catch(() => undefined)
  }
}
