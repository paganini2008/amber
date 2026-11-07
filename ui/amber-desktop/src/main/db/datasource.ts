import type { ConnectionProfile } from '../../shared/api'
import type { DataSource } from '../../shared/protocol'

/**
 * Turns a saved connection into the engine's `DataSource`. The password comes from the encrypted
 * secret store and is added here, in the main process, right before the engine request.
 */
export function toDataSource(profile: ConnectionProfile, password: string | null): DataSource {
  const url = typeof profile.url === 'string' && profile.url.trim() ? profile.url.trim() : null
  return {
    id: profile.id,
    dialect: profile.dialect,
    url,
    host: profile.host,
    port: profile.port,
    database: profile.database,
    user: profile.user,
    password: password ?? '',
    properties: {}
  }
}

/** The data sources for the given ids, in the given order; unknown ids are reported. */
export async function resolveDataSources(
  connections: ConnectionProfile[],
  ids: string[],
  password: (id: string) => Promise<string | null>
): Promise<{ dataSources: DataSource[]; unknown: string[] }> {
  const byId = new Map(connections.map((c) => [c.id, c]))
  const dataSources: DataSource[] = []
  const unknown: string[] = []
  for (const id of [...new Set(ids)]) {
    const profile = byId.get(id)
    if (profile) dataSources.push(toDataSource(profile, await password(id)))
    else unknown.push(id)
  }
  return { dataSources, unknown }
}
