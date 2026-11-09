/**
 * JDBC drivers Amber can download on demand. Amber bundles no database driver: when a connection
 * needs one, the user downloads it from a Maven repository (Maven Central by default) after seeing
 * the vendor's license. Each entry is pinned to one file by size and SHA-256, so a mirror or a proxy
 * can never hand Amber a different jar.
 *
 * One entry per dialect; the entry id is the dialect id, and a downloaded driver is stored at
 * `<userData>/drivers/<id>/<file>` and used for every connection of that dialect that has no jar of
 * its own.
 */

export interface DriverLicense {
  name: string
  url: string
}

export interface DriverArtifact {
  /** The dialect id this driver serves; also the folder name under `<userData>/drivers`. */
  id: string
  /** Short product name, e.g. "IBM JCC". */
  name: string
  vendor: string
  groupId: string
  artifactId: string
  version: string
  /** File name in the repository and on disk. */
  file: string
  /** Exact size in bytes. */
  size: number
  /** Lower-case hex SHA-256 of the jar. */
  sha256: string
  license: DriverLicense
}

export const DEFAULT_MAVEN_REPO = 'https://repo1.maven.org/maven2'

function artifact(
  id: string,
  name: string,
  vendor: string,
  coordinates: string,
  size: number,
  sha256: string,
  license: DriverLicense
): DriverArtifact {
  const [groupId, artifactId, version] = coordinates.split(':') as [string, string, string]
  return { id, name, vendor, groupId, artifactId, version, file: `${artifactId}-${version}.jar`, size, sha256, license }
}

export const DRIVERS: readonly DriverArtifact[] = [
  artifact('oracle', 'Oracle JDBC (ojdbc11)', 'Oracle', 'com.oracle.database.jdbc:ojdbc11:23.26.2.0.0', 7718137,
    'dbc0ff940bc056d5d9b8f42c0946ded4ebbc08c25cecf6ec1e521b2c8216956b',
    { name: 'Oracle Free Use Terms and Conditions (FUTC)', url: 'https://www.oracle.com/downloads/licenses/oracle-free-license.html' }),
  artifact('sqlserver', 'Microsoft JDBC Driver for SQL Server', 'Microsoft', 'com.microsoft.sqlserver:mssql-jdbc:13.4.0.jre11', 1547706,
    'e36f5237c1267983e5b88dc2169f6b9d7e50eceec6dc1ca31018e3877e14af66',
    { name: 'MIT', url: 'https://github.com/microsoft/mssql-jdbc/blob/main/LICENSE' }),
  artifact('postgresql', 'PostgreSQL JDBC', 'PostgreSQL Global Development Group', 'org.postgresql:postgresql:42.7.13', 1220948,
    '6e0e4cc2d8cae902084f8a2b18728b073a6fd9d1f87c9d8bff8f298c18185b93',
    { name: 'BSD-2-Clause', url: 'https://jdbc.postgresql.org/about/license.html' }),
  artifact('mysql', 'MySQL Connector/J', 'Oracle', 'com.mysql:mysql-connector-j:9.4.0', 2591877,
    '49ed93c8b2bea9cb0929b85a8a28837b191d0f8eac6919fdcef16e36e2cd53b3',
    { name: 'GPL-2.0 with the Universal FOSS Exception 1.0', url: 'https://oss.oracle.com/licenses/universal-foss-exception/' }),
  artifact('mariadb', 'MariaDB Connector/J', 'MariaDB', 'org.mariadb.jdbc:mariadb-java-client:3.5.9', 785411,
    '11e3bb5bbf8ef0e806ae4d6c5d5033fedf7262cc777f0190bde8a2f3c8e6bd8d',
    { name: 'LGPL-2.1-or-later', url: 'https://github.com/mariadb-corporation/mariadb-connector-j/blob/master/LICENSE' }),
  artifact('db2', 'IBM JCC', 'IBM', 'com.ibm.db2:jcc:12.1.4.0', 6629661,
    '9864979950be6550d63d4ceb6e3defa159e850f5f70d35c3dc44b4403606c0b3',
    { name: 'International Program License Agreement (IPLA)', url: 'https://www.ibm.com/support/customer/csol/terms/?ref=L-XFLK-S5N8U5-01-03-2026-zz-en' }),
  artifact('h2', 'H2 Database', 'H2 Group', 'com.h2database:h2:2.4.240', 2685418,
    '29b70e427cc1c40cdc376283adbb0cc62853073797bb5fe5761f81fe73d57ce0',
    { name: 'MPL 2.0 or EPL 1.0', url: 'https://h2database.com/html/license.html' }),
  artifact('sqlite', 'SQLite JDBC (Xerial)', 'Xerial', 'org.xerial:sqlite-jdbc:3.50.3.0', 14346720,
    'a3f53a2aa15ae9425a9e793bbe9c8e5288febeb4b65ef5c1a4e80d4c2045cf08',
    { name: 'Apache-2.0', url: 'https://www.apache.org/licenses/LICENSE-2.0' })
]

export function driverById(id: unknown): DriverArtifact | undefined {
  return typeof id === 'string' ? DRIVERS.find((d) => d.id === id) : undefined
}

/** The downloadable driver for a dialect (the catalog id is the dialect id). */
export function driverForDialect(dialect: string): DriverArtifact | undefined {
  return driverById(dialect)
}

/** "com.ibm.db2:jcc:12.1.4.0" */
export function coordinates(d: DriverArtifact): string {
  return `${d.groupId}:${d.artifactId}:${d.version}`
}

/**
 * A repository base URL, or null when it is not usable. HTTPS only (the jar is code the engine
 * runs); no credentials, query or fragment; trailing slashes removed.
 */
export function normalizeRepositoryUrl(value: unknown): string | null {
  if (typeof value !== 'string' || !value.trim()) return null
  try {
    const u = new URL(value.trim())
    if (u.protocol !== 'https:' || !u.hostname || u.username || u.password || u.search || u.hash) return null
    return `${u.origin}${u.pathname}`.replace(/\/+$/, '')
  } catch {
    return null
  }
}

/** The jar's URL in a Maven repository layout: `<base>/<group as path>/<artifact>/<version>/<file>`. */
export function artifactUrl(repository: string, d: DriverArtifact): string {
  const base = normalizeRepositoryUrl(repository)
  if (!base) throw new Error(`Not an HTTPS repository URL: ${repository}`)
  return `${base}/${d.groupId.split('.').join('/')}/${d.artifactId}/${d.version}/${d.file}`
}

/**
 * Why a download failed; sent as `error.data.reason` so the page can suggest what to do.
 * offline: no connection (DNS, refused, proxy); http: the repository answered an error status;
 * size / hash: the file is not the pinned one; repository: not an HTTPS URL; io: the file could not
 * be written.
 */
export type DriverFailure = 'offline' | 'http' | 'size' | 'hash' | 'cancelled' | 'timeout' | 'repository' | 'io'

export const DRIVER_FAILURES: readonly DriverFailure[] = ['offline', 'http', 'size', 'hash', 'cancelled', 'timeout', 'repository', 'io']
