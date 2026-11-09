import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, describe, expect, it } from 'vitest'
import { DEFAULT_MAVEN_REPO, driverForDialect, DRIVERS } from '@shared/drivers'
import { setLanguage } from '@/i18n'
import { settings as en } from '@/i18n/en/settings'
import { settings as es } from '@/i18n/es/settings'
import { settings as zh } from '@/i18n/zh-CN/settings'
import { ConnectionDriverField, missingDriverJar, needsDriverJar } from './ConnectionDriverField'
import { DriverConsent, DriverFailureMessage } from './DriverDownloadDialog'

const DIALECTS = ['oracle', 'sqlserver', 'postgresql', 'mysql', 'mariadb', 'db2', 'h2', 'sqlite']
const noop = (): void => undefined
const html = (s: string): string => s.replace(/&/g, '&amp;').replace(/'/g, '&#x27;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

function render(dialect: string, value?: string, installed = false): string {
  return renderToStaticMarkup(
    <ConnectionDriverField dialect={dialect} value={value} installed={installed} onChange={noop} onBrowse={noop} onDownload={noop} />
  )
}

describe('ConnectionDriverField', () => {
  afterEach(() => setLanguage('en'))

  it('appears for every database type Amber can download a driver for, not for an unknown one', () => {
    for (const d of DIALECTS) {
      expect(needsDriverJar(d)).toBe(true)
      expect(render(d)).toContain(en.connections.driver)
    }
    expect(needsDriverJar('teradata')).toBe(false)
    expect(render('teradata')).toBe('')
  })

  it('offers the download of the pinned driver, with its version and size, when it is not installed', () => {
    expect(render('db2')).toContain('Download IBM JCC 12.1.4.0 (6.6 MB)')
    expect(render('postgresql')).toContain('Download PostgreSQL JDBC 42.7.13 (1.2 MB)')
    expect(render('mariadb')).toContain('Download MariaDB Connector/J 3.5.9 (785 kB)')
    expect(render('db2')).toContain('Browse…')
    expect(render('db2')).toContain(html(en.connections.driverHint))
  })

  it('shows an installed driver instead of the download, and lets a jar of your own go back to it', () => {
    const installed = render('postgresql', '', true)
    expect(installed).toContain('PostgreSQL driver 42.7.13 installed')
    expect(installed).not.toContain('Download PostgreSQL')
    expect(installed).toContain('placeholder="postgresql-42.7.13.jar"')
    expect(installed).toContain(`title="${en.drivers.usesDownloaded}"`)
    expect(installed).not.toContain(en.drivers.useDownloaded + '<')
    const own = render('postgresql', '/opt/pg.jar', true)
    expect(own).toContain('value="/opt/pg.jar"')
    expect(own).toContain(en.drivers.useDownloaded)
  })

  it('warns (without blocking) when there is neither a jar nor a downloaded driver', () => {
    expect(missingDriverJar('db2', undefined)).toBe(true)
    expect(missingDriverJar('db2', '  ')).toBe(true)
    expect(missingDriverJar('db2', '/opt/jcc.jar')).toBe(false)
    expect(missingDriverJar('db2', '', true)).toBe(false)
    expect(missingDriverJar('teradata', '')).toBe(false)
    const missing = render('db2', ' ')
    expect(missing).toContain('role="alert"')
    expect(missing).toContain('aria-invalid="true"')
    expect(missing).toContain('No Db2 driver yet. Download it or choose a jar; until then Amber cannot connect.')
    const ok = render('db2', '/opt/drivers/jcc.jar')
    expect(ok).toContain('aria-invalid="false"')
    expect(ok).not.toContain('role="alert"')
  })

  it('is translated', () => {
    setLanguage('zh-CN')
    expect(render('db2')).toContain(zh.connections.driver)
    expect(render('db2')).toContain(zh.connections.driverHint)
    setLanguage('es')
    expect(render('db2')).toContain(es.connections.driver)
  })
})

describe('DriverConsent', () => {
  afterEach(() => setLanguage('en'))

  it('shows each driver with its own license name and link, the source host, size and pinned checksum', () => {
    for (const d of DRIVERS) {
      const out = renderToStaticMarkup(<DriverConsent driver={d} repository={DEFAULT_MAVEN_REPO} />)
      expect(out, d.id).toContain(html(d.license.name))
      expect(out, d.id).toContain(`href="${html(d.license.url)}"`)
      expect(out, d.id).toContain('target="_blank"')
      expect(out, d.id).toContain(d.sha256)
      expect(out, d.id).toContain(`${d.groupId}:${d.artifactId}:${d.version}`)
      expect(out, d.id).toContain('repo1.maven.org')
      expect(out, d.id).not.toContain('role="progressbar"')
    }
    const db2 = renderToStaticMarkup(<DriverConsent driver={driverForDialect('db2')!} repository="https://nexus.corp/m2" />)
    expect(db2).toContain('International Program License Agreement (IPLA)')
    expect(db2).toContain('nexus.corp')
    expect(db2).toContain('6.6 MB')
  })

  it('shows progress while downloading', () => {
    const db2 = driverForDialect('db2')!
    const out = renderToStaticMarkup(<DriverConsent driver={db2} repository={DEFAULT_MAVEN_REPO} progress={Math.round(db2.size / 2)} />)
    expect(out).toContain('role="progressbar"')
    expect(out).toContain('aria-valuenow="50"')
    expect(out).toContain('Downloading 3.3 MB of 6.6 MB')
  })

  it('explains a failure and suggests choosing a jar by hand', () => {
    const hash = renderToStaticMarkup(<DriverFailureMessage error={{ code: -32603, message: 'checksum differs', data: { reason: 'hash' } }} />)
    expect(hash).toContain('checksum differs')
    expect(hash).toContain(html(en.drivers.dialog.checksumFailedHint))
    expect(hash).toContain(html(en.drivers.dialog.manual))
    const offline = renderToStaticMarkup(<DriverFailureMessage error={{ code: -32603, message: 'no route', data: { reason: 'offline' } }} />)
    expect(offline).toContain(html(en.drivers.dialog.offlineHint))
  })
})
