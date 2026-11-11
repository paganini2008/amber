import { describe, expect, it } from 'vitest'
import { checkLatestRelease, compareVersions, isPrerelease } from './check'

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

  it('asks for the latest stable release from a release build', async () => {
    const urls: string[] = []
    await checkLatestRelease(async (url) => {
      urls.push(url)
      return Response.json({ tag_name: 'v1.0.0' })
    }, '1.0.0')
    expect(urls).toEqual(['https://api.github.com/repos/paganini2008/amber/releases/latest'])
  })

  it('also offers newer pre-releases to a pre-release build, but never drafts', async () => {
    const urls: string[] = []
    const info = await checkLatestRelease(async (url) => {
      urls.push(url)
      return Response.json([
        { tag_name: 'v1.0.0-rc2', html_url: 'https://github.com/paganini2008/amber/releases/tag/v1.0.0-rc2' },
        { tag_name: 'v1.0.0-rc4', draft: true },
        { tag_name: 'v1.0.0-rc3', html_url: 'https://github.com/paganini2008/amber/releases/tag/v1.0.0-rc3' }
      ])
    }, '1.0.0-rc2')
    expect(urls).toEqual(['https://api.github.com/repos/paganini2008/amber/releases?per_page=20'])
    expect(info).toMatchObject({ latest: '1.0.0-rc3', newer: true, url: 'https://github.com/paganini2008/amber/releases/tag/v1.0.0-rc3' })
  })

  it('moves a pre-release build on to the final release', async () => {
    const info = await checkLatestRelease(async () => Response.json([{ tag_name: 'v1.0.0' }, { tag_name: 'v1.0.0-rc3' }]), '1.0.0-rc3')
    expect(info).toMatchObject({ latest: '1.0.0', newer: true })
  })
})

describe('isPrerelease', () => {
  it('recognizes pre-release and snapshot versions', () => {
    expect(isPrerelease('1.0.0-rc2')).toBe(true)
    expect(isPrerelease('v1.0.0-SNAPSHOT')).toBe(true)
    expect(isPrerelease('1.0.0')).toBe(false)
  })
})
