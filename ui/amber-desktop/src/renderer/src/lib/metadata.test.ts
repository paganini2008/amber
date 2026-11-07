import { describe, expect, it } from 'vitest'
import type { ConnectionProfile } from '@shared/api'
import type { LoadMetadataResult, TableMeta } from '@shared/protocol'
import { addPaths, fileName, hashText, mergePacks, metadataInputs, metadataKey, metadataStatusText, OFFLINE, summarizeLoad } from './metadata'

const t = (name: string, source: string, schema: string | null = null): TableMeta => ({ catalog: null, schema, name, kind: 'TABLE', columns: [], source })

/** As returned by the engine for two SQLite files where `customer` exists in both. */
const result: LoadMetadataResult = {
  tables: 2,
  missing: ['region', 'nowhere'],
  ambiguous: [{ name: 'customer', dataSources: ['a', 'b'] }],
  errors: { c: 'Connection refused' },
  servers: {
    a: { product: 'SQLite', version: '3.50.3', majorVersion: 3, minorVersion: 50, user: null, readOnly: false },
    b: { product: 'SQLite', version: '3.50.3', majorVersion: 3, minorVersion: 50, user: null, readOnly: false }
  },
  saved: null,
  pack: { format: 1, dialect: 'sqlite', source: 'a, b', created: '2026-11-07T00:00:00Z', tables: [t('customer', 'a'), t('orders', 'a')] }
}

const conn = (id: string, name: string): ConnectionProfile => ({ id, name, dialect: 'sqlite', host: '', port: 0, database: '', user: '', hasPassword: false })

describe('metadata keys', () => {
  it('changes with the dialect, the connections (and their order) and the text', () => {
    const k = metadataKey('sqlite', ['a', 'b'], 'select 1')
    expect(metadataKey('sqlite', ['a', 'b'], 'select 1')).toBe(k)
    expect(metadataKey('oracle', ['a', 'b'], 'select 1')).not.toBe(k)
    expect(metadataKey('sqlite', ['b', 'a'], 'select 1')).not.toBe(k)
    expect(metadataKey('sqlite', ['a', 'b'], 'select 2')).not.toBe(k)
    expect(hashText('')).toBe('811c9dc5')
    expect(hashText('abc')).not.toBe(hashText('acb'))
  })
})

describe('metadata inputs', () => {
  it('passes only what is present', () => {
    expect(metadataInputs({ ddlFiles: [], metadataPacks: [] }, null)).toEqual({})
    expect(metadataInputs({ ddlFiles: [], metadataPacks: [] }, [])).toEqual({})
    const tables = [t('orders', 'a')]
    expect(metadataInputs({ ddlFiles: ['/x/a.sql'], metadataPacks: ['/x/p.json'] }, tables)).toEqual({
      ddlFiles: ['/x/a.sql'],
      metadataPacks: ['/x/p.json'],
      metadata: tables
    })
  })

  it('adds paths once and names files', () => {
    expect(addPaths(['/a.sql'], ['/b.sql', '/a.sql', '', '/b.sql'])).toEqual(['/a.sql', '/b.sql'])
    expect(fileName('C:\\ddl\\shop.sql')).toBe('shop.sql')
    expect(fileName('/tmp/pack.json')).toBe('pack.json')
  })
})

describe('online results', () => {
  it('summarizes tables, servers and errors per connection in lookup order', () => {
    const s = summarizeLoad(result, ['b', 'a', 'c'], [conn('a', 'Main'), conn('b', 'Archive')])
    expect(s).toEqual([
      { id: 'b', name: 'Archive', tables: 0, server: 'SQLite 3.50.3', error: null },
      { id: 'a', name: 'Main', tables: 2, server: 'SQLite 3.50.3', error: null },
      { id: 'c', name: 'c', tables: 0, server: null, error: 'Connection refused' }
    ])
  })

  it('words the status bar, and says nothing when offline without files', () => {
    const none = { ddlFiles: [], metadataPacks: [] }
    expect(metadataStatusText(OFFLINE, none)).toBeNull()
    expect(metadataStatusText(OFFLINE, { ddlFiles: ['a'], metadataPacks: [] })).toBe('1 metadata file')
    expect(metadataStatusText({ ...OFFLINE, status: 'loading' }, none)).toBe('Online · reading metadata')
    expect(metadataStatusText({ ...OFFLINE, status: 'ready', result }, { ddlFiles: ['a'], metadataPacks: ['b'] })).toBe('Online · 2 tables · check · 2 metadata files')
    expect(metadataStatusText({ ...OFFLINE, status: 'ready', result: { ...result, errors: {}, ambiguous: [] } }, none)).toBe('Online · 2 tables')
    expect(metadataStatusText({ ...OFFLINE, status: 'error', error: 'x' }, none)).toBe('Online · metadata failed')
  })
})

describe('mergePacks', () => {
  it('keeps the first table of each qualified name', () => {
    const offline = { format: 1, dialect: 'sqlite', source: 'DDL: a.sql', created: 'x', tables: [t('Orders', 'script DDL'), t('region', 'script DDL')] }
    const pack = mergePacks('sqlite', [offline, result.pack, null, [t('orders', 'z', 'main')]], '2026-11-07T00:00:00Z')
    expect(pack.format).toBe(1)
    expect(pack.created).toBe('2026-11-07T00:00:00Z')
    expect(pack.source).toBe('DDL: a.sql; a, b')
    expect(pack.tables.map((x) => `${x.schema ?? ''}.${x.name}@${x.source}`)).toEqual([
      '.Orders@script DDL',
      '.region@script DDL',
      '.customer@a',
      'main.orders@z'
    ])
  })
})
