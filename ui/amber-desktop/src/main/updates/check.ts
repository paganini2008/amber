import type { UpdateInfo } from '../../shared/api'
import type { FetchLike } from '../ai/gateway'
import { tm } from '../i18n'

/** Where the update check looks; `repo` is GitHub owner/name (AMBER_UPDATE_REPO in development). */
export function releaseUrls(repo = 'paganini2008/amber'): { api: string; list: string; page: string } {
  return {
    api: `https://api.github.com/repos/${repo}/releases/latest`,
    list: `https://api.github.com/repos/${repo}/releases?per_page=20`,
    page: `https://github.com/${repo}/releases/latest`
  }
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

/** The update check's answer, before the main process adds whether it can install the update itself. */
export type ReleaseCheck = Omit<UpdateInfo, 'autoInstall'>

/** Asks GitHub for the newest release. Sends no identifying data beyond a generic user agent. */
export async function checkLatestRelease(fetchFn: FetchLike, current: string, repo?: string, timeoutSec = 10): Promise<ReleaseCheck> {
  const { page } = releaseUrls(repo)
  const tag = isPrerelease(current) ? await newestRelease(fetchFn, repo, timeoutSec) : await latestStable(fetchFn, repo, timeoutSec)
  if (!tag) return { current, latest: current, newer: false, url: page }
  const latest = tag.tag.replace(/^v/i, '')
  const url = tag.url && tag.url.startsWith('https://github.com/') ? tag.url : page
  return { current, latest: latest || current, newer: latest ? compareVersions(latest, current) > 0 : false, url }
}

/** `1.0.0-rc2`, `1.0.0-SNAPSHOT`: a build that also wants to hear about newer pre-releases. */
export function isPrerelease(version: string): boolean {
  return version.replace(/^v/i, '').includes('-')
}

interface ReleaseTag {
  tag: string
  url?: string
}

function githubRequest(timeoutSec: number): RequestInit {
  return { headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'Amber' }, signal: AbortSignal.timeout(timeoutSec * 1000) }
}

/** GitHub's "latest" release: the newest one that is neither a draft nor a pre-release. */
async function latestStable(fetchFn: FetchLike, repo: string | undefined, timeoutSec: number): Promise<ReleaseTag | null> {
  const response = await fetchFn(releaseUrls(repo).api, githubRequest(timeoutSec))
  if (response.status === 404) return null
  if (!response.ok) throw new Error(tm('main.updates.githubAnswered', { status: response.status }))
  const body = (await response.json()) as { tag_name?: string; html_url?: string }
  return body.tag_name ? { tag: body.tag_name, url: body.html_url } : null
}

/** The highest version among the recent published releases, pre-releases included (drafts are never listed to others). */
async function newestRelease(fetchFn: FetchLike, repo: string | undefined, timeoutSec: number): Promise<ReleaseTag | null> {
  const response = await fetchFn(releaseUrls(repo).list, githubRequest(timeoutSec))
  if (response.status === 404) return null
  if (!response.ok) throw new Error(tm('main.updates.githubAnswered', { status: response.status }))
  const body = (await response.json()) as { tag_name?: string; html_url?: string; draft?: boolean }[]
  if (!Array.isArray(body)) return null
  let best: ReleaseTag | null = null
  for (const r of body) {
    if (r.draft || !r.tag_name) continue
    if (!best || compareVersions(r.tag_name, best.tag) > 0) best = { tag: r.tag_name, url: r.html_url }
  }
  return best
}
