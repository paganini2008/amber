import { describe, expect, it } from 'vitest'
import { platformName, versionInfo } from './about'

describe('versionInfo', () => {
  it('lists app, engine, runtime and system on separate lines', () => {
    const app = { name: 'Amber', version: '1.0.0', electron: '45.2.3', chrome: '140.0', node: '22.19.0', platform: 'darwin', arch: 'arm64', license: 'Apache-2.0' }
    expect(versionInfo(app, { name: 'Amber', version: '1.0.0-SNAPSHOT', java: '25.0.1', dialects: [] })).toBe(
      'Amber 1.0.0\nEngine 1.0.0-SNAPSHOT on Java 25.0.1\nElectron 45.2.3 (Chromium 140.0, Node.js 22.19.0)\nmacOS arm64'
    )
    expect(versionInfo(null, undefined)).toBe('Amber unknown\nEngine not running\nElectron ? (Chromium ?, Node.js ?)\nunknown system')
    expect(platformName('win32')).toBe('Windows')
  })
})
