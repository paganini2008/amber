# Third-party notices

Amber is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)). Amber's installers and
source tree also contain the third-party components listed below, each under its own license.
The copyright notices and license texts of these components are reproduced or referenced here as
their licenses require.

This file is shipped inside every installer and is shown on the About screen.

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
| JDBC drivers (Oracle, SQL Server, PostgreSQL, MySQL, MariaDB, Db2, H2, SQLite) | Online mode | **Not included**: downloaded on demand by the user from Maven Central (see below) |
| Eclipse Temurin (OpenJDK) runtime | Bundled Java runtime | GPL-2.0 with the Classpath Exception |
| Electron | Desktop shell | MIT |
| electron-updater and its dependencies | In-app updates (Windows, Linux) | MIT, ISC (semver), BlueOak-1.0.0 (sax) |
| Chromium and Node.js (inside Electron) | Desktop shell | Various open-source licenses (see below) |
| IBM Plex Sans, IBM Plex Mono | Fonts in the app and on the website | SIL Open Font License 1.1 |
| React, React DOM | Desktop user interface | MIT |
| CodeMirror 6, Lezer | SQL editor and highlighting | MIT |
| React Flow (@xyflow/react) | Lineage, call and ER diagrams | MIT |
| ELK (elkjs) | Diagram layout | EPL-2.0 (chosen from EPL-2.0 OR GPL-3.0-or-later) |
| Radix UI primitives | Dialogs, menus, tabs, tooltips | MIT |
| TanStack Virtual | Long lists | MIT |
| Zustand | UI state | MIT |
| Lucide icons (lucide-react) | Icons | ISC |
| Tailwind CSS (generated styles) | Styling | MIT |
| clsx | Class names | MIT |
| Simple Icons (path data of 5 database marks) | Database icons | CC0-1.0 |

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

**Amber does not include or redistribute any JDBC driver.** Online mode, which is optional and reads catalog metadata
only, needs the driver of the database it connects to. When one is missing, Amber offers to download it from Maven
Central (or a mirror you set) and shows the driver's license first. The download starts only after you accept, and
each file is checked against a SHA-256 pinned in Amber. You can also choose a driver .jar yourself. Offline analysis
needs no driver.

The drivers Amber offers to download, and their licenses (accepted by the user when downloading):

| Database | Maven coordinates | License |
|---|---|---|
| Oracle | `com.oracle.database.jdbc:ojdbc11:23.26.2.0.0` | Oracle Free Use Terms and Conditions |
| SQL Server | `com.microsoft.sqlserver:mssql-jdbc:13.4.0.jre11` | MIT |
| PostgreSQL | `org.postgresql:postgresql:42.7.13` | BSD-2-Clause |
| MySQL | `com.mysql:mysql-connector-j:9.4.0` | GPL-2.0 with the Universal FOSS Exception |
| MariaDB | `org.mariadb.jdbc:mariadb-java-client:3.5.9` | LGPL-2.1-or-later |
| Db2 | `com.ibm.db2:jcc:12.1.4.0` | IBM International Program License Agreement |
| H2 | `com.h2database:h2:2.4.240` | MPL-2.0 or EPL-1.0 |
| SQLite | `org.xerial:sqlite-jdbc:3.50.3.0` | Apache-2.0 |

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

## Desktop user-interface libraries

These libraries are compiled into the desktop app's user interface. Each keeps its own copyright; the
license texts are in the packages on npm (and in `node_modules/<package>/LICENSE` of a source checkout).

| Library | Copyright | License |
|---|---|---|
| React, React DOM <https://react.dev> | Meta Platforms, Inc. and affiliates | MIT |
| CodeMirror 6 (`@codemirror/*`), Lezer (`@lezer/*`) <https://codemirror.net> | Marijn Haverbeke and others | MIT |
| React Flow (`@xyflow/react`) <https://reactflow.dev> | webkid GmbH | MIT |
| Radix UI primitives (`@radix-ui/*`) <https://www.radix-ui.com> | WorkOS | MIT |
| TanStack Virtual <https://tanstack.com/virtual> | Tanner Linsley | MIT |
| Zustand <https://github.com/pmndrs/zustand> | Paul Henschel | MIT |
| Lucide (`lucide-react`) <https://lucide.dev> | Lucide Contributors (parts © Cole Bemis, Feather) | ISC |
| Tailwind CSS <https://tailwindcss.com> | Tailwind Labs, Inc. | MIT |
| clsx <https://github.com/lukeed/clsx> | Luke Edwards | MIT |

### Database icons: Simple Icons

- The PostgreSQL, MySQL, MariaDB, H2 and SQLite marks in `ui/amber-desktop/src/renderer/src/assets/db/` use
  path data from Simple Icons 16.35.0, <https://simpleicons.org>, released under **CC0 1.0 Universal**
  (public domain dedication). The Oracle, SQL Server and Db2 marks and the generic database glyph are
  original Amber drawings; all marks sit on the same Amber tile.
- Database names and logos are trademarks of their respective owners; used only to identify compatibility.

### ELK (elkjs)

- Eclipse Layout Kernel compiled to JavaScript, <https://github.com/kieler/elkjs>, used unmodified to lay
  out diagrams.
- Offered under EPL-2.0 OR GPL-3.0-or-later; Amber uses it under the **Eclipse Public License 2.0**
  (<https://www.eclipse.org/legal/epl-2.0/>). Its source code is available at the address above.
  Copyright © Kiel University and others.
