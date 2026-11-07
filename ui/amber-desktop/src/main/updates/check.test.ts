import { describe, expect, it } from 'vitest'
import { checkLatestRelease, compareVersions } from './check'

describe('compareVersions', () => {
  it('orders releases and pre-releases', () => {
    expect(compareVersions('1.0.1', '1.0.0')).toBe(1)
    expect(compareVersions('1.0.0', '1.0.0-SNAPSHOT')).toBe(1)
    expect(compareVersions('v1.2.0', '1.10.0')).toBe(-1)
    expect(compareVersions('1.0.0-rc.1', '1.0.0-rc.2')).toBe(-1)
    expect(compareVersions('2.0', '2.0.0')).toBe(0)
  })
})

describe('checkLatestRelease', () => {
  it('reports a newer release', async () => {
    const info = await checkLatestRelease(
      async () => Response.json({ tag_name: 'v1.1.0', html_url: 'https://github.com/paganini2008/amber/releases/tag/v1.1.0' }),
      '1.0.0'
    )
    expect(info).toMatchObject({ latest: '1.1.0', newer: true })
  })

  it('treats a repository without releases as up to date', async () => {
    const info = await checkLatestRelease(async () => new Response('', { status: 404 }), '1.0.0-SNAPSHOT')
    expect(info.newer).toBe(false)
  })
})
