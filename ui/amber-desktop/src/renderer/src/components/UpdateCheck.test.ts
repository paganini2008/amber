import { afterEach, describe, expect, it } from 'vitest'
import type { UpdateInfo } from '@shared/api'
import { setLanguage } from '@/i18n'
import { dialogs as zh } from '@/i18n/zh-CN/dialogs'
import { describeUpdate } from './UpdateCheck'

const URL = 'https://github.com/paganini2008/amber/releases/tag/v1.1.0'
const info = (patch: Partial<UpdateInfo>): UpdateInfo => ({ current: '1.0.0', latest: '1.1.0', newer: true, url: URL, autoInstall: false, ...patch })

describe('describeUpdate', () => {
  afterEach(() => setLanguage('en'))

  it('links to the download page on macOS', () => {
    expect(describeUpdate({ state: 'idle' }, { kind: 'info', info: info({}) })).toEqual({
      message: 'Amber 1.1.0 is available.',
      action: { kind: 'download', url: URL, primary: true }
    })
  })

  it('shows the download, then offers a restart, on Windows and Linux', () => {
    const checked = { kind: 'info' as const, info: info({ autoInstall: true }) }
    expect(describeUpdate({ state: 'downloading', version: '1.1.0', percent: 42 }, checked)).toEqual({ message: 'Downloading Amber 1.1.0… 42%', action: null })
    expect(describeUpdate({ state: 'ready', version: '1.1.0' }, null)).toEqual({ message: 'Amber 1.1.0 is ready to install.', action: { kind: 'restart' } })
  })

  it('falls back to the download page when the download fails', () => {
    const view = describeUpdate({ state: 'error', message: 'sha512 checksum mismatch' }, { kind: 'info', info: info({ autoInstall: true }) })
    expect(view.message).toBe('Could not download the update: sha512 checksum mismatch')
    expect(view.action).toEqual({ kind: 'download', url: URL, primary: false })
  })

  it('reports a failed check and an up-to-date version', () => {
    expect(describeUpdate({ state: 'idle' }, { kind: 'failed', reason: 'GitHub answered 503.' }).message).toBe('Could not check: GitHub answered 503.')
    expect(describeUpdate({ state: 'idle' }, { kind: 'info', info: info({ newer: false, latest: '1.0.0' }) }).message).toBe('You have the latest version (1.0.0).')
    expect(describeUpdate({ state: 'idle' }, null)).toEqual({ message: null, action: null })
  })

  it('uses the selected language', () => {
    setLanguage('zh-CN')
    expect(describeUpdate({ state: 'ready', version: '1.1.0' }, null).message).toBe(zh.about.ready.replace('{version}', '1.1.0'))
  })
})
