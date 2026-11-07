import type { ConnectionProfile } from '@shared/api'
import type { LoadMetadataResult, MetadataPack, TableMeta } from '@shared/protocol'

/**
 * Metadata the analysis can use: offline (DDL files, metadata packs) and online (tables read
 * through `db.loadMetadata`). Offline is the default; with neither, `analyze` gets no metadata.
 */

export interface MetadataFiles {
  ddlFiles: string[]
  metadataPacks: string[]
}

export type OnlineStatus = 'off' | 'idle' | 'loading' | 'ready' | 'error'

export interface OnlineState {
  status: OnlineStatus
  /** What the current (or last) load was for; a load runs only when this changes. */
  key: string | null
  /** Tables passed to `analyze` as `metadata`. Null when nothing was loaded. */
  tables: TableMeta[] | null
  result: LoadMetadataResult | null
  error: string | null
  requestId: string | null
}

export const OFFLINE: OnlineState = { status: 'off', key: null, tables: null, result: null, error: null, requestId: null }

/** FNV-1a over UTF-16 code units, as a short hex string. Enough to notice that a script changed. */
export function hashText(text: string): string {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return (h >>> 0).toString(16).padStart(8, '0')
}

/**
 * Identity of a metadata load: the same dialect, connections (in order, since the first match
 * wins) and script text give the same key, so re-analyzing with the loaded metadata does not
 * load again.
 */
export function metadataKey(dialect: string, connectionIds: string[], text: string): string {
  return `${dialect}|${connectionIds.join(',')}|${text.length}:${hashText(text)}`
}

/** The metadata params of `analyze`, leaving out what is empty. */
export function metadataInputs(files: MetadataFiles, onlineTables: TableMeta[] | null): {
  ddlFiles?: string[]
  metadataPacks?: string[]
  metadata?: TableMeta[]
} {
  const out: { ddlFiles?: string[]; metadataPacks?: string[]; metadata?: TableMeta[] } = {}
  if (files.ddlFiles.length) out.ddlFiles = [...files.ddlFiles]
  if (files.metadataPacks.length) out.metadataPacks = [...files.metadataPacks]
  if (onlineTables?.length) out.metadata = onlineTables
  return out
}

/** Adds paths to a list, once each, keeping the existing order. */
export function addPaths(list: string[], paths: string[]): string[] {
  const out = [...list]
  for (const p of paths) if (p && !out.includes(p)) out.push(p)
  return out
}

export function fileName(path: string): string {
  return path.split(/[\\/]/).pop() || path
}

export function tableName(t: Pick<TableMeta, 'catalog' | 'schema' | 'name'>): string {
  return [t.catalog, t.schema, t.name].filter(Boolean).join('.')
}

export interface SourceSummary {
  id: string
  name: string
  tables: number
  server: string | null
  error: string | null
}

/** Per connection: how many tables it supplied, its server, and its error. */
export function summarizeLoad(result: LoadMetadataResult, connectionIds: string[], connections: ConnectionProfile[]): SourceSummary[] {
  const names = new Map(connections.map((c) => [c.id, c.name]))
  const counts = new Map<string, number>()
  for (const t of result.pack?.tables ?? []) if (t.source) counts.set(t.source, (counts.get(t.source) ?? 0) + 1)
  const ids = [...connectionIds]
  for (const id of [...Object.keys(result.servers ?? {}), ...Object.keys(result.errors ?? {})]) if (!ids.includes(id)) ids.push(id)
  return ids.map((id) => {
    const s = result.servers?.[id]
    return {
      id,
      name: names.get(id) ?? id,
      tables: counts.get(id) ?? 0,
      server: s ? `${s.product} ${s.version}`.trim() : null,
      error: result.errors?.[id] ?? null
    }
  })
}

/** Short status-bar text, or null when there is nothing to show (offline without files). */
export function metadataStatusText(online: OnlineState, files: MetadataFiles): string | null {
  const offline = files.ddlFiles.length + files.metadataPacks.length
  const offlineText = offline ? `${offline} metadata ${offline === 1 ? 'file' : 'files'}` : null
  let onlineText: string | null = null
  switch (online.status) {
    case 'off':
      break
    case 'idle':
      onlineText = 'Online'
      break
    case 'loading':
      onlineText = 'Online · reading metadata'
      break
    case 'error':
      onlineText = 'Online · metadata failed'
      break
    case 'ready': {
      const r = online.result
      const n = r?.tables ?? online.tables?.length ?? 0
      const warn = r ? Object.keys(r.errors ?? {}).length + (r.ambiguous?.length ?? 0) : 0
      onlineText = `Online · ${n} ${n === 1 ? 'table' : 'tables'}${warn ? ' · check' : ''}`
      break
    }
  }
  const parts = [onlineText, offlineText].filter(Boolean)
  return parts.length ? parts.join(' · ') : null
}

/**
 * One pack from several: later tables with the same qualified name (case-insensitive) are
 * dropped, so the order of the inputs decides, as in online lookups.
 */
export function mergePacks(dialect: string, packs: (MetadataPack | TableMeta[] | null | undefined)[], created: string): MetadataPack {
  const seen = new Set<string>()
  const tables: TableMeta[] = []
  const sources: string[] = []
  for (const p of packs) {
    if (!p) continue
    const list = Array.isArray(p) ? p : p.tables
    if (!Array.isArray(p) && p.source) sources.push(p.source)
    for (const t of list ?? []) {
      const key = tableName(t).toUpperCase()
      if (seen.has(key)) continue
      seen.add(key)
      tables.push(t)
    }
  }
  return { format: 1, dialect, source: sources.join('; ') || 'Amber', created, tables }
}
