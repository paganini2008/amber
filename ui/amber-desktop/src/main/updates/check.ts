import type { UpdateInfo } from '../../shared/api'
import type { FetchLike } from '../ai/gateway'
import { tm } from '../i18n'

/** Where the update check looks; `repo` is GitHub owner/name (AMBER_UPDATE_REPO in development). */
export function releaseUrls(repo = 'paganini2008/amber'): { api: string; page: string } {
  return { api: `https://api.github.com/repos/${repo}/releases/latest`, page: `https://github.com/${repo}/releases/latest` }
}

/** Where About Amber points: project, website (GitHub Pages), releases, new issue, author profile. */
export interface ProjectLinks {
  project: string
  website: string
  releases: string
  issue: string
  author: { name: string; url: string }
}

export function projectLinks(repo = 'paganini2008/amber'): ProjectLinks {
  const [owner = 'paganini2008', name = 'amber'] = repo.split('/')
  return {
    project: `https://github.com/${owner}/${name}`,
    website: `https://${owner.toLowerCase()}.github.io/${name}/`,
    releases: `https://github.com/${owner}/${name}/releases/latest`,
    issue: `https://github.com/${owner}/${name}/issues/new`,
    // The author's public profile only; no e-mail address is shown anywhere.
    author: { name: 'Fred Feng', url: `https://github.com/${owner}` }
  }
}

export const LATEST_RELEASE_API = releaseUrls().api
export const RELEASES_PAGE = releaseUrls().page

/** Compares dotted versions; a pre-release (`-rc.1`, `-SNAPSHOT`) sorts before its release. */
export function compareVersions(a: string, b: string): number {
  const parse = (v: string): { nums: number[]; pre: string } => {
    const clean = v.trim().replace(/^v/i, '')
    const [core = '', ...pre] = clean.split('-')
    return { nums: core.split('.').map((n) => Number.parseInt(n, 10) || 0), pre: pre.join('-') }
  }
  const x = parse(a)
  const y = parse(b)
  for (let i = 0; i < Math.max(x.nums.length, y.nums.length); i++) {
    const d = (x.nums[i] ?? 0) - (y.nums[i] ?? 0)
    if (d !== 0) return Math.sign(d)
  }
  if (x.pre === y.pre) return 0
  if (!x.pre) return 1
  if (!y.pre) return -1
  return x.pre < y.pre ? -1 : 1
}

/** Asks GitHub for the latest release. Sends no identifying data beyond a generic user agent. */
export async function checkLatestRelease(fetchFn: FetchLike, current: string, repo?: string, timeoutSec = 10): Promise<UpdateInfo> {
  const { api, page } = releaseUrls(repo)
  const response = await fetchFn(api, {
    headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'Amber' },
    signal: AbortSignal.timeout(timeoutSec * 1000)
  })
  if (response.status === 404) return { current, latest: current, newer: false, url: page }
  if (!response.ok) throw new Error(tm('main.updates.githubAnswered', { status: response.status }))
  const body = (await response.json()) as { tag_name?: string; html_url?: string }
  const latest = (body.tag_name ?? '').replace(/^v/i, '')
  const url = body.html_url && body.html_url.startsWith('https://github.com/') ? body.html_url : page
  return { current, latest: latest || current, newer: latest ? compareVersions(latest, current) > 0 : false, url }
}
