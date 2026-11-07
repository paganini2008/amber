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
      properties: {}
    })
    const ds = toDataSource(lite, null)
    expect(ds.url).toBe('jdbc:sqlite:/tmp/a.db')
    expect(ds.password).toBe('')
    expect(ds).not.toHaveProperty('hasPassword')
    expect(ds).not.toHaveProperty('name')
  })

  it('resolves ids in order, once each, and reports unknown ones', async () => {
    const r = await resolveDataSources([pg, lite], ['lite', 'x', 'pg', 'lite'], async (id) => (id === 'pg' ? 'pw' : null))
    expect(r.dataSources.map((d) => d.id)).toEqual(['lite', 'pg'])
    expect(r.dataSources[1]!.password).toBe('pw')
    expect(r.unknown).toEqual(['x'])
  })
})
