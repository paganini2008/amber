import { describe, expect, it } from 'vitest'
import { artifactUrl, coordinates, DEFAULT_MAVEN_REPO, driverById, driverForDialect, DRIVERS, normalizeRepositoryUrl } from './drivers'

const DIALECTS = ['oracle', 'sqlserver', 'postgresql', 'mysql', 'mariadb', 'db2', 'h2', 'sqlite']

describe('driver catalog', () => {
  it('has one valid entry per dialect', () => {
    expect(DRIVERS.map((d) => d.id).sort()).toEqual([...DIALECTS].sort())
    for (const d of DRIVERS) {
      expect(d.sha256, d.id).toMatch(/^[0-9a-f]{64}$/)
      expect(Number.isInteger(d.size) && d.size > 0, d.id).toBe(true)
      expect(d.file, d.id).toBe(`${d.artifactId}-${d.version}.jar`)
      expect(d.name.trim() && d.vendor.trim() && d.license.name.trim(), d.id).toBeTruthy()
      expect(new URL(d.license.url).protocol, d.id).toBe('https:')
      const url = new URL(artifactUrl(DEFAULT_MAVEN_REPO, d))
      expect(url.protocol, d.id).toBe('https:')
      expect(url.host).toBe('repo1.maven.org')
    }
    expect(new Set(DRIVERS.map((d) => d.sha256)).size).toBe(DRIVERS.length)
  })

  it('pins the IBM Db2 driver', () => {
    const db2 = driverForDialect('db2')!
    expect(coordinates(db2)).toBe('com.ibm.db2:jcc:12.1.4.0')
    expect(db2.file).toBe('jcc-12.1.4.0.jar')
    expect(db2.size).toBe(6629661)
    expect(db2.sha256).toBe('9864979950be6550d63d4ceb6e3defa159e850f5f70d35c3dc44b4403606c0b3')
    expect(db2.license.name).toBe('International Program License Agreement (IPLA)')
    expect(artifactUrl(DEFAULT_MAVEN_REPO, db2)).toBe('https://repo1.maven.org/maven2/com/ibm/db2/jcc/12.1.4.0/jcc-12.1.4.0.jar')
  })

  it('finds entries only by a known string id', () => {
    expect(driverById('postgresql')?.version).toBe('42.7.13')
    for (const bad of ['', 'DB2', 'mssql', '__proto__', 1, null, undefined, {}]) expect(driverById(bad), String(bad)).toBeUndefined()
  })
})

describe('normalizeRepositoryUrl', () => {
  it('accepts HTTPS base URLs and drops trailing slashes', () => {
    expect(normalizeRepositoryUrl(' https://mirror.example.com/maven2/ ')).toBe('https://mirror.example.com/maven2')
    expect(normalizeRepositoryUrl('https://nexus.corp:8443/repository/central')).toBe('https://nexus.corp:8443/repository/central')
  })

  it('rejects everything else', () => {
    for (const bad of ['http://repo1.maven.org/maven2', 'ftp://x/y', 'file:///m2', 'https://u:p@x/m2', 'https://x/m2?a=1', 'https://x/m2#f', 'nope', '', null, 3]) {
      expect(normalizeRepositoryUrl(bad), String(bad)).toBeNull()
    }
    expect(() => artifactUrl('http://x', DRIVERS[0]!)).toThrow()
  })
})
