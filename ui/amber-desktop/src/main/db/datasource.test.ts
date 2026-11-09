import { describe, expect, it } from 'vitest'
import type { ConnectionProfile } from '../../shared/api'
import { resolveDataSources, toDataSource } from './datasource'

const pg: ConnectionProfile = { id: 'pg', name: 'PG', dialect: 'postgresql', host: 'db', port: 5432, database: 'demo', user: 'reader', hasPassword: true }
const lite: ConnectionProfile = { id: 'lite', name: 'Lite', dialect: 'sqlite', host: '', port: 0, database: '', user: '', url: ' jdbc:sqlite:/tmp/a.db ', hasPassword: false }

describe('toDataSource', () => {
  it('maps a profile to the engine DataSource with the password', () => {
    expect(toDataSource(pg, 'secret')).toEqual({
      id: 'pg',
      dialect: 'postgresql',
      url: null,
      host: 'db',
      port: 5432,
      database: 'demo',
      user: 'reader',
      password: 'secret',
      properties: {},
      driverPath: null
    })
    const ds = toDataSource(lite, null)
    expect(ds.url).toBe('jdbc:sqlite:/tmp/a.db')
    expect(ds.password).toBe('')
    expect(ds).not.toHaveProperty('hasPassword')
    expect(ds).not.toHaveProperty('name')
  })

  it('forwards the driver jar path to the engine as driverPath, trimmed; blank means none', async () => {
    const db2: ConnectionProfile = { id: 'db2', name: 'Db2', dialect: 'db2', host: 'h', port: 50000, database: 'D', user: 'u', driverPath: ' /opt/jcc.jar ', hasPassword: true }
    expect(toDataSource(db2, 'pw')).toMatchObject({ id: 'db2', dialect: 'db2', driverPath: '/opt/jcc.jar', password: 'pw' })
    expect(toDataSource({ ...db2, driverPath: '  ' }, null).driverPath).toBeNull()
    expect(toDataSource({ ...db2, driverPath: undefined }, null).driverPath).toBeNull()
    const r = await resolveDataSources([pg, db2], ['db2'], async () => 'pw')
    expect(r.dataSources[0]!.driverPath).toBe('/opt/jcc.jar')
    // the JSON the engine receives
    expect(JSON.parse(JSON.stringify({ dataSource: toDataSource(db2, 'pw') })).dataSource.driverPath).toBe('/opt/jcc.jar')
  })

  it('resolves ids in order, once each, and reports unknown ones', async () => {
    const r = await resolveDataSources([pg, lite], ['lite', 'x', 'pg', 'lite'], async (id) => (id === 'pg' ? 'pw' : null))
    expect(r.dataSources.map((d) => d.id)).toEqual(['lite', 'pg'])
    expect(r.dataSources[1]!.password).toBe('pw')
    expect(r.unknown).toEqual(['x'])
  })

  it('uses the driver downloaded for the dialect when the profile has no jar of its own; an own jar wins', async () => {
    const db2: ConnectionProfile = { id: 'db2', name: 'Db2', dialect: 'db2', host: 'h', port: 50000, database: 'D', user: 'u', hasPassword: false }
    const downloaded = (dialect: string): string | null => (dialect === 'postgresql' ? '/data/drivers/postgresql/postgresql-42.7.13.jar' : null)
    expect(toDataSource(pg, null, downloaded).driverPath).toBe('/data/drivers/postgresql/postgresql-42.7.13.jar')
    expect(toDataSource({ ...pg, driverPath: ' /opt/my-pg.jar ' }, null, downloaded).driverPath).toBe('/opt/my-pg.jar')
    expect(toDataSource({ ...pg, driverPath: '  ' }, null, downloaded).driverPath).toBe('/data/drivers/postgresql/postgresql-42.7.13.jar')
    // nothing downloaded for Db2: the engine reports the driver as not installed
    expect(toDataSource(db2, null, downloaded).driverPath).toBeNull()
    const r = await resolveDataSources([pg, db2, { ...pg, id: 'pg2', driverPath: '/opt/my-pg.jar' }], ['pg', 'db2', 'pg2'], async () => null, downloaded)
    expect(r.dataSources.map((d) => d.driverPath)).toEqual(['/data/drivers/postgresql/postgresql-42.7.13.jar', null, '/opt/my-pg.jar'])
  })
})
