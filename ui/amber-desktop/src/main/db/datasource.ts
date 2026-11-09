import type { ConnectionProfile } from '../../shared/api'
import type { DataSource } from '../../shared/protocol'

/** The downloaded driver jar for a dialect, or null (see main/drivers/download.ts). */
export type DownloadedDriver = (dialect: string) => string | null

/**
 * Turns a saved connection into the engine's `DataSource`. The password comes from the encrypted
 * secret store and is added here, in the main process, right before the engine request. Amber bundles
 * no JDBC driver, so `driverPath` is the profile's own jar when it has one, else the driver downloaded
 * for its dialect, else null (the engine then reports the driver as not installed).
 */
export function toDataSource(profile: ConnectionProfile, password: string | null, downloaded?: DownloadedDriver): DataSource {
  const url = typeof profile.url === 'string' && profile.url.trim() ? profile.url.trim() : null
  const own = typeof profile.driverPath === 'string' && profile.driverPath.trim() ? profile.driverPath.trim() : null
  const driverPath = own ?? downloaded?.(profile.dialect) ?? null
  return {
    id: profile.id,
    dialect: profile.dialect,
    url,
    host: profile.host,
    port: profile.port,
    database: profile.database,
    user: profile.user,
    password: password ?? '',
    properties: {},
    driverPath
  }
}

/** The data sources for the given ids, in the given order; unknown ids are reported. */
export async function resolveDataSources(
  connections: ConnectionProfile[],
  ids: string[],
  password: (id: string) => Promise<string | null>,
  downloaded?: DownloadedDriver
): Promise<{ dataSources: DataSource[]; unknown: string[] }> {
  const byId = new Map(connections.map((c) => [c.id, c]))
  const dataSources: DataSource[] = []
  const unknown: string[] = []
  for (const id of [...new Set(ids)]) {
    const profile = byId.get(id)
    if (profile) dataSources.push(toDataSource(profile, await password(id), downloaded))
    else unknown.push(id)
  }
  return { dataSources, unknown }
}
