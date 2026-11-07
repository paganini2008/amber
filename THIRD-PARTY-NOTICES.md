# Third-party notices

Amber is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)). Amber's installers and
source tree also contain the third-party components listed below, each under its own license.
The copyright notices and license texts of these components are reproduced or referenced here as
their licenses require.

This file is shipped inside every installer and is shown on the About screen.

> **Status.** Entries marked **Verify before first public release** have redistribution terms that
> must be checked by a maintainer before the first public release. Versions listed here must be
> kept in sync with `backend/pom.xml` and `ui/amber-desktop/package.json`.

## Summary

| Component | Used for | License |
|---|---|---|
| grammars-v4 PL/SQL grammar | Oracle parser | Apache-2.0 |
| grammars-v4 T-SQL grammar | SQL Server parser | MIT |
| grammars-v4 PostgreSQL grammar | PostgreSQL parser | MIT |
| grammars-v4 MySQL grammar | MySQL parser | BSD-3-Clause |
| grammars-v4 MariaDB grammar | MariaDB parser | MIT |
| grammars-v4 Db2 grammar | Db2 parser | MIT |
| grammars-v4 SQLite grammar | SQLite parser | MIT |
| H2 grammar (written for Amber) | H2 parser | Apache-2.0 |
| ANTLR 4 runtime | Parsing | BSD-3-Clause |
| Jackson (core, databind, annotations) | JSON | Apache-2.0 |
| Oracle JDBC driver (ojdbc) | Oracle metadata (online mode) | Oracle Free Use Terms and Conditions — **verify before first public release** |
| Microsoft JDBC Driver for SQL Server (mssql-jdbc) | SQL Server metadata | MIT |
| PostgreSQL JDBC driver (pgjdbc) | PostgreSQL metadata | BSD-2-Clause |
| MySQL Connector/J | MySQL metadata | GPL-2.0 with the Universal FOSS Exception — **verify before first public release** |
| MariaDB Connector/J | MariaDB metadata | LGPL-2.1 |
| IBM Data Server Driver for JDBC and SQLJ (JCC) | Db2 metadata | IBM license terms — **verify before first public release** |
| H2 Database Engine | H2 metadata | MPL-2.0 or EPL-1.0 (dual) |
| sqlite-jdbc (Xerial) | SQLite metadata | Apache-2.0 |
| Eclipse Temurin (OpenJDK) runtime | Bundled Java runtime | GPL-2.0 with the Classpath Exception |
| Electron | Desktop shell | MIT |
| Chromium and Node.js (inside Electron) | Desktop shell | Various open-source licenses (see below) |
| IBM Plex Sans, IBM Plex Mono | Fonts in the app and on the website | SIL Open Font License 1.1 |

Further npm packages bundled into the desktop app (React, CodeMirror, React Flow, elkjs, Radix UI,
Zustand, Lucide and others) are distributed under their own permissive licenses (mostly MIT,
Apache-2.0 and ISC). Their license texts are generated into the app package at build time; see
`ui/amber-desktop/package.json` for the list.

---

## SQL grammars

Grammars from [antlr/grammars-v4](https://github.com/antlr/grammars-v4), commit
`9fa9f94b20cd3225bd84f27554515da207ec39ff`, in
`backend/amber-core/src/main/antlr4/com/github/amber/dialect/<db>/grammar/`. Each directory has an
`UPSTREAM.md` that records the source path and the local changes. The license and copyright
notice of each grammar are kept in the header of each `.g4` file, which is the authoritative
notice for that file.

| Dialect | Upstream path | License |
|---|---|---|
| Oracle | `sql/plsql` | Apache-2.0 |
| SQL Server | `sql/tsql` | MIT |
| PostgreSQL | `sql/postgresql` | MIT |
| MySQL | `sql/mysql/Oracle` | BSD-3-Clause |
| MariaDB | `sql/mariadb` | MIT |
| Db2 | `sql/db2` | MIT |
| SQLite | `sql/sqlite` | MIT |

The **H2** grammar was written for Amber from the public H2 documentation
(<https://h2database.com/html/grammar.html>); no H2 source code was used. It is licensed under
Apache-2.0 like the rest of Amber.

The parsers generated from these grammars by ANTLR are covered by the license of the grammar they
were generated from.

## ANTLR 4 runtime

- Project: <https://www.antlr.org/>, <https://github.com/antlr/antlr4>
- Artifact: `org.antlr:antlr4-runtime` 4.13.2
- License: BSD-3-Clause

Copyright (c) 2012-2022 The ANTLR Project. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted
provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions
   and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of
   conditions and the following disclaimer in the documentation and/or other materials provided
   with the distribution.
3. Neither name of copyright holders nor the names of its contributors may be used to endorse or
   promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## Jackson

- Project: <https://github.com/FasterXML/jackson>
- Artifacts: `com.fasterxml.jackson.core:jackson-core`, `jackson-databind`, `jackson-annotations`
  2.19.2
- License: Apache-2.0 (<https://www.apache.org/licenses/LICENSE-2.0>)

Jackson is a high-performance JSON processor for Java, developed by the FasterXML community. The
NOTICE files included in the Jackson jars are retained in the distribution.

## JDBC drivers

The drivers are bundled so that online mode can read catalog metadata. Amber never sends user
statements to a database.

### Oracle JDBC driver (ojdbc)

- Project: <https://www.oracle.com/database/technologies/appdev/jdbc.html>
- License: Oracle Free Use Terms and Conditions (FUTC),
  <https://www.oracle.com/downloads/licenses/oracle-free-license.html>
- **Verify before first public release**: confirm that the FUTC permits redistributing the driver
  inside Amber's installers, and which notices must accompany it.

### Microsoft JDBC Driver for SQL Server (mssql-jdbc)

- Project: <https://github.com/microsoft/mssql-jdbc>
- License: MIT. Copyright (c) Microsoft Corporation.

### PostgreSQL JDBC driver (pgjdbc)

- Project: <https://jdbc.postgresql.org/>, <https://github.com/pgjdbc/pgjdbc>
- License: BSD-2-Clause. Copyright (c) 1997, PostgreSQL Global Development Group.

### MySQL Connector/J

- Project: <https://dev.mysql.com/downloads/connector/j/>
- License: GPL-2.0 with the Universal FOSS Exception, version 1.0
  (<https://oss.oracle.com/licenses/universal-foss-exception/>). Copyright (c) Oracle and/or its
  affiliates.
- **Verify before first public release**: confirm that distributing Connector/J alongside
  Apache-2.0 Amber under the Universal FOSS Exception is allowed as packaged (separate, unmodified
  jar loaded through JDBC), and which notices and source offers are required.

### MariaDB Connector/J

- Project: <https://mariadb.com/kb/en/about-mariadb-connector-j/>,
  <https://github.com/mariadb-corporation/mariadb-connector-j>
- License: LGPL-2.1. Shipped as an unmodified, separate jar that can be replaced by the user.
  Source code is available from the project's repository.

### IBM Data Server Driver for JDBC and SQLJ (JCC)

- Project: <https://www.ibm.com/support/pages/db2-jdbc-driver-versions-and-downloads>
- License: IBM license terms that accompany the driver (`com.ibm.db2:jcc`).
- **Verify before first public release**: confirm that the IBM terms permit redistributing the
  driver inside Amber's installers, and which notices must accompany it.

### H2 Database Engine

- Project: <https://h2database.com/>
- License: dual-licensed under the Mozilla Public License 2.0 or the Eclipse Public License 1.0
  (<https://h2database.com/html/license.html>). Source code is available from
  <https://github.com/h2database/h2database>.

### sqlite-jdbc

- Project: <https://github.com/xerial/sqlite-jdbc>
- License: Apache-2.0. Includes SQLite, which is in the public domain. Only the native library for
  the target platform is shipped in each installer.

## Java runtime: Eclipse Temurin

- Project: <https://adoptium.net/>
- Each installer contains a runtime image created with `jlink` from Eclipse Temurin 25 (OpenJDK).
- License: GNU General Public License, version 2, with the Classpath Exception
  (<https://openjdk.org/legal/gplv2+ce.html>). Third-party components of OpenJDK are listed in the
  runtime's `legal/` directory, which is kept in the bundled image.
- Source code: <https://github.com/adoptium/jdk25u> and <https://github.com/openjdk/jdk25u>.

## Electron, Chromium and Node.js

- Electron: <https://www.electronjs.org/>, MIT License. Copyright (c) Electron contributors,
  Copyright (c) 2013-2020 GitHub Inc.
- Electron embeds Chromium and Node.js, which include many third-party components under their own
  licenses (BSD, MIT, LGPL, Apache-2.0 and others). The complete notices are shipped with every
  installer in `LICENSES.chromium.html` and `LICENSE` next to the application executable, as
  produced by Electron.

## Fonts: IBM Plex

- IBM Plex Sans and IBM Plex Mono, <https://github.com/IBM/plex>
- License: SIL Open Font License, version 1.1 (<https://openfontlicense.org>).
  Copyright © 2017 IBM Corp. with Reserved Font Name "Plex".
- Bundled in the desktop app; the website loads them from Google Fonts.
