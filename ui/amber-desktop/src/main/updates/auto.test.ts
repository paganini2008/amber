import { EventEmitter } from 'node:events'
import { describe, expect, it } from 'vitest'
import type { UpdateStatus } from '../../shared/api'
import { AutoUpdates, canInstallUpdates, type Updater } from './auto'

class FakeUpdater extends EventEmitter {
  autoDownload = false
  autoInstallOnAppQuit = false
  allowPrerelease = false
  checks = 0
  installs: [boolean | undefined, boolean | undefined][] = []
  failWith: Error | null = null
  async checkForUpdates(): Promise<unknown> {
    this.checks++
    if (this.failWith) throw this.failWith
    return null
  }
  quitAndInstall(isSilent?: boolean, isForceRunAfter?: boolean): void {
    this.installs.push([isSilent, isForceRunAfter])
  }
}

function setup(prerelease = false): { fake: FakeUpdater; updates: AutoUpdates; seen: UpdateStatus[] } {
  const fake = new FakeUpdater()
  const seen: UpdateStatus[] = []
  const updates = new AutoUpdates(fake as unknown as Updater, (s) => seen.push(s), prerelease)
  return { fake, updates, seen }
}

describe('canInstallUpdates', () => {
  it('installs on Windows and on Linux .deb/.rpm, never on macOS or in development', () => {
    expect(canInstallUpdates('win32', true, null, true)).toBe(true)
    expect(canInstallUpdates('linux', true, 'deb', true)).toBe(true)
    expect(canInstallUpdates('linux', true, 'rpm', true)).toBe(true)
    expect(canInstallUpdates('linux', true, null, true)).toBe(false)
    expect(canInstallUpdates('darwin', true, null, true)).toBe(false)
    expect(canInstallUpdates('win32', false, null, true)).toBe(false)
    expect(canInstallUpdates('win32', true, null, false)).toBe(false)
  })
})

describe('AutoUpdates', () => {
  it('downloads in the background, reports progress and installs on quit', async () => {
    const { fake, updates, seen } = setup()
    expect(fake.autoDownload).toBe(true)
    expect(fake.autoInstallOnAppQuit).toBe(true)
    expect(fake.allowPrerelease).toBe(false)

    await updates.download('1.1.0')
    fake.emit('download-progress', { percent: 41.6 })
    fake.emit('update-downloaded', { version: '1.1.0' })
    expect(seen).toEqual([
      { state: 'downloading', version: '1.1.0', percent: 0 },
      { state: 'downloading', version: '1.1.0', percent: 42 },
      { state: 'ready', version: '1.1.0' }
    ])
    expect(updates.current()).toEqual({ state: 'ready', version: '1.1.0' })
  })

  it('follows pre-releases only from a pre-release build', () => {
    expect(setup(true).fake.allowPrerelease).toBe(true)
  })

  it('does not start the same download twice', async () => {
    const { fake, updates } = setup()
    await updates.download('1.1.0')
    await updates.download('1.1.0')
    fake.emit('update-downloaded', { version: '1.1.0' })
    await updates.download('1.1.0')
    expect(fake.checks).toBe(1)
  })

  it('installs only a downloaded update', async () => {
    const { fake, updates } = setup()
    expect(updates.install()).toBe(false)
    await updates.download('1.1.0')
    expect(updates.install()).toBe(false)
    fake.emit('update-downloaded', { version: '1.1.0' })
    expect(updates.install()).toBe(true)
    expect(fake.installs).toEqual([[false, true]])
  })

  it('reports a failed download and allows another try', async () => {
    const { fake, updates } = setup()
    fake.failWith = new Error('net::ERR_INTERNET_DISCONNECTED')
    await updates.download('1.1.0')
    expect(updates.current()).toEqual({ state: 'error', message: 'net::ERR_INTERNET_DISCONNECTED' })
    fake.failWith = null
    await updates.download('1.1.0')
    expect(fake.checks).toBe(2)
    expect(updates.current().state).toBe('downloading')
    fake.emit('error', new Error('sha512 checksum mismatch'))
    expect(updates.current()).toEqual({ state: 'error', message: 'sha512 checksum mismatch' })
  })
})
