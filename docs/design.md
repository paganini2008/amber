---
title: Amber — Technical Design
---

# Amber — Technical Design

Status: draft · Version: 1.0.0-SNAPSHOT · Last updated: 2026-11-07

Amber is a desktop tool that helps developers **read and understand SQL**. You give it a SQL
script — often thousands of lines mixing DDL, DML, queries, procedures and temporary tables — and
it shows how the script is organized, how data flows between statements, where each result column
comes from, and which parts carry risk.

Amber is open source (Apache-2.0), hosted at `github.com/paganini2008/amber`, and shipped as
native installers for Windows, macOS and Linux. Users never see a `.jar`: every release bundles the
engine and a trimmed Java runtime. JDBC drivers are not bundled; online mode downloads the one a
connection needs on demand (see §8).

---

## 1. Positioning and principles

Amber is a **SQL reader**, not a database client and not a data governance platform.

| Principle | Meaning |
|---|---|
| **Amber never runs SQL** | No SQL console, no result grids, no data editing. Statements from the user's script are analyzed, never executed — in any mode, including SQL produced by AI. See §11.4. |
| **Offline first** | Without a network, a driver or a database, every core feature works. Online mode only adds metadata. |
| **Deterministic analysis** | Structure, lineage, granularity and findings come from ANTLR parsing and rules. They are reproducible and always point back to a source range. |
| **Strict by default** | A script either parses completely or the analysis fails with precise errors. Degraded parsing is an explicit opt-in (§7.3). |
| **Say what is unknown** | Missing metadata, dynamic SQL and inferred bindings are labeled, never guessed. |
| **Overview first** | The default view answers "what does this script do"; details open on demand. |
| **AI is optional and advisory** | An LLM may propose optimizations using Amber's analysis as context. It never parses, never decides structure or lineage, and its output is always checked and labeled (§10). |

### Non-goals

Executing SQL of any kind written by the user or by AI, browsing result sets, editing data,
scheduling or ETL, schema migration, and database administration.

---

## 2. Scope

Everything below is part of 1.0.0.

- **Script structure**: statement splitting, per-statement structure, script outline.
- **CTE expansion**: collapse/expand, recursive CTEs marked.
- **Subqueries, derived tables, JOINs**: correlated subqueries with the outer columns they use,
  JOIN types, keys and conditions.
- **Temporary objects**: `#temp`, global temporary tables, table variables, followed across
  statements.
- **Procedures, functions, triggers, packages**: control flow plus analysis of embedded SQL.
- **Dynamic SQL**: `EXECUTE IMMEDIATE`, `sp_executesql`, `EXEC(@sql)`, `PREPARE`/`EXECUTE`;
  analyzed when the string is a resolvable constant, labeled otherwise.
- **Script-level data flow**: which statement creates, writes, reads, updates and drops which object.
- **Column lineage**: direct, indirect, cross-statement; reverse **impact analysis**.
- **Granularity and row-count risk**: grain of every step, JOIN fan-out warnings.
- **Duplicate SQL**: fingerprints, similar subtrees, repeated scans.
- **Findings**: static rules, metadata rules.
- **Rule-based rewrites**: deterministic rewrites shown as diffs, never applied automatically.
- **Semantic diff** between two versions of a script.
- **Confidence labels** on every node and lineage edge.
- **Offline mode** with metadata packs and DDL / CSV / Excel data dictionary import.
- **Online mode**: read-only metadata from 8 databases.
- **AI-assisted optimization** through Ollama or an OpenAI-compatible API (optional).
- **Export**: SVG, PNG, Mermaid, DOT, HTML report, Markdown, JSON, CSV, with optional masking.
- **Formatting and dialect conversion** (reference output only).
- **Command-line mode** for CI use (`amber analyze`, `amber export`), shipped inside the same app.

---

## 3. Supported databases

All eight dialects are first-class in 1.0.0. Oracle, SQL Server and PostgreSQL get the deepest
test corpus first; the other five follow the same quality bar.

| Database | Parser | JDBC driver (downloaded on demand) | Notes |
|---|---|---|---|
| Oracle | ANTLR 4, grammars-v4 `sql/plsql` | Oracle JDBC (ojdbc, thin) | SQL*Plus commands handled by the preprocessor |
| SQL Server | ANTLR 4, grammars-v4 `sql/tsql` | Microsoft JDBC Driver (mssql-jdbc) | `GO` batches |
| PostgreSQL | ANTLR 4, grammars-v4 `sql/postgresql` + Amber's PL/pgSQL body parser | pgjdbc | The upstream grammar keeps `$$` bodies as strings; Amber parses PL/pgSQL control flow itself and hands embedded SQL to the grammar; unparsable body statements are warnings, never script failures |
| MySQL | ANTLR 4, grammars-v4 `sql/mysql` | MySQL Connector/J | `DELIMITER` handled by the preprocessor |
| MariaDB | MySQL grammar + MariaDB extensions | MariaDB Connector/J | Sequences, `RETURNING`, system-versioned tables, `sql_mode=ORACLE` blocks |
| DB2 (LUW) | ANTLR 4, grammars-v4 `sql/db2` as a starting point | IBM Data Server Driver for JDBC (JCC) | SQL PL completed from the DB2 SQL Reference |
| H2 | ANTLR 4, grammar written from scratch | H2 JDBC | Built from the official H2 grammar; compatibility modes as grammar switches |
| SQLite | ANTLR 4, grammars-v4 `sql/sqlite` | sqlite-jdbc | Native library trimmed to the target platform |

### SQL statement coverage

| Kind | Examples | Structure | Column lineage |
|---|---|---|---|
| Query | SELECT, CTE, recursive CTE, set operations, window functions, PIVOT | yes | yes |
| DML | INSERT…SELECT, UPDATE…FROM/JOIN, DELETE…USING, MERGE, UPSERT | yes | yes, to target columns |
| DDL | CREATE TABLE [AS], CREATE VIEW, ALTER, INDEX, SEQUENCE, DROP, TRUNCATE | yes | CTAS and views |
| Temporary objects | `#t`, `##t`, `DECLARE GLOBAL TEMPORARY`, `@table` variables | yes | across statements |
| DCL | GRANT, REVOKE | objects and privileges listed | no |
| Procedural | PL/SQL, T-SQL, PL/pgSQL, SQL PL, MySQL/MariaDB stored programs | control flow + embedded SQL | embedded SQL |
| Triggers | BEFORE/AFTER/INSTEAD OF, `:NEW`/`:OLD`, `inserted`/`deleted` | trigger relation + embedded SQL | yes |
| Packages | Oracle package spec and body | members and call graph | embedded SQL |
| Dynamic SQL | `EXECUTE IMMEDIATE`, `sp_executesql` | labeled | when the string is constant |
| Transaction control | COMMIT, ROLLBACK, SAVEPOINT | shown in outline and control flow | no |

---

## 4. Architecture

```
┌──────────────────────── Amber desktop app (Electron) ─────────────────────────┐
│                                                                               │
│  Renderer (React + TypeScript)                                                │
│    editor · outline · graphs · lineage · findings · AI panel · settings       │
│          │  window.amber.*  (contextBridge, typed, no Node access)            │
│          ▼                                                                    │
│  Preload ──► Main process (TypeScript, Node.js)                               │
│                windows/menus · file I/O · engine supervisor · secret storage  │
│                AI gateway (Ollama / OpenAI-compatible HTTP, streaming)        │
│                update check                                                   │
│          │  JSON-RPC 2.0 over stdin/stdout, one message per line             │
│          ▼                                                                    │
│  Engine (Java 17+, bundled jlink runtime)                                    │
│    preprocess · ANTLR parsers (8 dialects) · IR · analyzers · rules           │
│    rewrites · metadata packs · JDBC metadata reader · AI context + checks     │
└───────────────────────────────────────────────────────────────────────────────┘
```

- **Renderer**: `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`, strict CSP.
  It talks only to the API that preload exposes.
- **Main process**: spawns `runtime/bin/java` with the bundled `engine.jar`, keeps it resident
  (JVM start-up is paid once), restarts it if it crashes and fails pending requests with an error.
  It owns all secrets (database passwords, AI API keys) via Electron `safeStorage`. Secrets are
  never sent to the renderer.
- **Engine**: all parsing and analysis. It never receives AI API keys and never makes calls to
  an AI provider; it only builds AI context and checks AI output.
- Start-up is accelerated with an **AppCDS** archive generated at build time.

### 4.1 Engine API (JSON-RPC methods)

| Method | Purpose |
|---|---|
| `analyze` | Script → IR, view models, findings, diagnostics |
| `lineage`, `impact` | Forward lineage for a column; reverse impact for a source column |
| `diff` | Semantic diff of two scripts |
| `rewrite.suggest` | Rule-based rewrites with preconditions |
| `format`, `convert` | Formatting; dialect conversion |
| `metadata.import`, `metadata.export` | DDL / CSV / Excel import; metadata pack I/O |
| `db.test`, `db.loadMetadata` | Read-only connection test and metadata loading |
| `ai.buildContext` | Build the AI prompt context for a scope (§10.3) |
| `ai.check` | Parse and structurally compare AI-produced SQL (§10.5) |
| `export` | SVG, Mermaid, DOT, HTML, Markdown, JSON, CSV |

Long operations report progress through JSON-RPC notifications (`$/progress`) and can be cancelled
(`$/cancel`).

```json
{"jsonrpc":"2.0","id":1,"method":"analyze","params":{"dialect":"oracle","encoding":"GBK","sql":"…","options":{"allowFallback":false},"metadataPack":"pack-01"}}
{"jsonrpc":"2.0","id":1,"result":{"script":{},"views":{},"findings":[],"diagnostics":[]}}
```

---

## 5. Repository layout

```
amber/
├─ backend/                                 Maven, Java 17+
│  ├─ pom.xml                               com.github.paganini2008:amber-all (parent, 1.0.0-SNAPSHOT)
│  └─ amber-core/                           the whole engine, one project
│     └─ src/main/
│        ├─ antlr4/com/github/amber/dialect/<db>/grammar/   .g4 files + UPSTREAM.md
│        └─ java/com/github/amber/
│           ├─ core/                        source text, ranges, diagnostics, IR, analyzers, rules
│           ├─ dialect/                     DialectFrontend SPI, shared ANTLR driver, splitter, fallback scanner
│           │  ├─ oracle/  sqlserver/  postgresql/  mysql/
│           │  └─ mariadb/  db2/  h2/  sqlite/
│           ├─ meta/                        metadata model, packs, DDL/CSV/Excel import
│           ├─ jdbc/                        drivers, read-only catalog readers
│           ├─ ai/                          AI context builder, response parser, SQL check
│           ├─ export/                      SVG, Mermaid, DOT, HTML, Markdown, JSON, CSV
│           └─ server/                      JSON-RPC entry point and CLI
├─ ui/
│  ├─ .gitignore
│  └─ amber-desktop/                        Electron + React + TypeScript, the desktop app
│     ├─ package.json
│     ├─ electron-builder.yml
│     └─ src/
│        ├─ main/                           main process
│        ├─ preload/
│        └─ renderer/                       React app
├─ docs/                                    GitHub Pages site (this document lives here)
│  └─ assets/                               images, screenshots, fonts used by the site and docs
├─ .github/workflows/                       ci.yml, release.yml
├─ LICENSE                                  Apache-2.0
└─ THIRD-PARTY-NOTICES.md
```

All backend code lives in the single `amber-core` project and is organized by package under
`com.github.amber`. `amber-core` produces `engine.jar`. Heavier tests (corpus, parse rate,
end-to-end, online mode against real databases, a real-model AI round trip) live in `amber-core` as `*IT` classes (package `com.github.amber.it`), run by failsafe in `mvn verify`; the fast unit tests are the `*Test` classes run by surefire.

---

## 6. Tech stack

| Layer | Choice |
|---|---|
| Desktop shell | Electron (current stable), electron-builder |
| UI | React, TypeScript, Vite; Tailwind CSS; Radix primitives (shadcn/ui); Lucide icons |
| Graphs | React Flow (`@xyflow/react`) with custom nodes; ELK (`elkjs`) layout in a Web Worker |
| Editor | CodeMirror 6 with per-dialect highlighting |
| State | Zustand; analysis results cached by script hash |
| Engine | Java 17+ (compiled for 17; installers bundle a Java 25 runtime), Maven |
| Parsing | ANTLR 4 (Java runtime), `antlr4-maven-plugin` |
| JSON | Jackson |
| Database access | JDBC, read-only |
| Packaging | `jlink` runtime + AppCDS, electron-builder (NSIS, DMG, DEB, RPM) |
| CI/CD | GitHub Actions |

---

## 7. Parsing layer

### 7.1 Choice

Every dialect uses ANTLR 4 with grammar files maintained in this repository. ANTLR has the widest
open SQL grammar ecosystem (grammars-v4); grammar files are plain text we can fix and extend; the
Java runtime is ANTLR's primary target and is the fastest. Closed-source parsers are rejected
because we cannot change their grammars and cannot ship them in an open-source project.

Each dialect module implements one interface:

```java
public interface DialectFrontend {
    Dialect dialect();
    Preprocessed preprocess(SourceText text);          // encoding, delimiters, client commands
    List<StatementUnit> split(Preprocessed source);    // statement boundaries
    ParseResult parse(StatementUnit unit);             // CST + syntax errors
    IrFragment toIr(ParseResult parsed, IrContext ctx);
}
```

### 7.2 Pipeline

1. **Preprocess**: detect encoding (UTF-8, UTF-16 with BOM, GBK, GB18030); normalize line endings
   while keeping a mapping back to original offsets; handle client-side syntax — SQL*Plus `/`,
   `SET`, `PROMPT`, `@file`; T-SQL `GO`; MySQL/MariaDB `DELIMITER`; DB2 `@` terminator; psql
   meta-commands.
2. **Split** into statement units with exact source ranges. Procedural bodies stay one unit.
3. **Parse** each unit — in parallel across a thread pool — using two-stage prediction: SLL
   first, full LL only when SLL fails.
4. **Build CST → IR** with one visitor per dialect.
5. **Resolve names**: bind columns to relations using the script's own DDL, imported metadata or
   online metadata.

### 7.3 Strict mode and fallback switch

| Mode | Setting | Behavior |
|---|---|---|
| **Strict** (default) | `analysis.allowFallback = false` | Any syntax error fails the analysis. Amber shows every error with line, column, the offending token and the expected tokens, and produces **no** partial result. |
| **Fallback** (opt-in) | `analysis.allowFallback = true` | Statements that fail to parse are scanned at token level to extract the objects they read and write. They appear in the outline and data flow as **Partial**, with no structure or column lineage. Every other statement is analyzed normally. A banner states how many statements were degraded. |

The switch is a global setting and can also be toggled per analysis from the error screen
("Analyze with fallback").

### 7.4 Grammar maintenance

- Grammars are copied into `backend/amber-core/src/main/antlr4/com/github/amber/dialect/<db>/grammar/`; `UPSTREAM.md`
  records the source commit **and the license of each grammar file**.
- Every grammar change adds a SQL case to that dialect's corpus (`amber-core/src/test/resources/corpus/<dialect>/`) that reproduces the problem.
- Upstream is reviewed periodically and merged as needed; the full corpus re-runs after a merge.
- H2 is written from the official grammar productions; DB2 SQL PL is completed from the DB2 SQL
  Reference; MariaDB extends the MySQL grammar through imported grammar fragments.

### 7.5 Corpus and tests

`amber-core/src/test/resources/corpus/` holds real-world-shaped scripts per dialect (procedures, packages, thousand-line
batch jobs). CI reports the parse-success rate per dialect, runs IR snapshot tests, and runs fuzz
tests that mutate scripts to catch crashes and hangs.

---

## 8. Intermediate representation (IR)

| Object | Meaning | Key attributes |
|---|---|---|
| `Script` | One file or input | dialect, encoding, statements, blocks, diagnostics |
| `Block` | Procedure, function, trigger, package member, anonymous block | parameters, variables, control-flow graph |
| `Statement` | One SQL statement | kind, reads, writes, source range, parse status |
| `Relation` | Table, view, CTE, subquery, temp table, table variable | origin (physical / derived / temporary), columns, grain |
| `Step` | One processing step on a relation | FROM, JOIN, WHERE, GROUP BY, HAVING, WINDOW, SELECT, set operation, ORDER/LIMIT |
| `Column` | Column | owner relation, type, expression |
| `LineageEdge` | Data flow between columns | direct / indirect, transform (pass-through, expression, aggregate, CASE, cast, constant) |
| `ObjectEvent` | Lifecycle event of an object | statement, kind (create, write, update, merge, read, drop, truncate) |
| `Finding` | Risk or optimization hint | rule id, severity, range, explanation, suggestion |

**Confidence**, shown on every node and edge:

| Level | When | Rendering |
|---|---|---|
| Certain | Parsed, and every column bound to known metadata | solid |
| Inferred | Parsed, but some binding relies on inference (`SELECT *`, unqualified columns without metadata) | dashed |
| Partial | Fallback mode: statement only scanned at token level | dotted, muted |
| Unresolved | Dynamic SQL with a non-constant string | muted, labeled |

The IR is versioned (`irVersion`) and serialized as JSON; the UI and the AI context builder
consume the same model.

---

## 9. Analyzers

All analyzers consume the IR, are independent of each other, and produce view models or findings.

### 9.1 Script outline (L0)

- Statements grouped into **phases**. Phase boundaries come from, in order: explicit comment
  markers, `BEGIN … END` blocks, then a heuristic (create → populate → transform → publish →
  clean up).
- Each statement gets a one-line, template-based summary, for example
  "Insert into `tmp_order` from `orders` ⋈ `customers`, grouped by customer".
- Procedural blocks show their control flow (IF, LOOP, cursor, exception handlers) as foldable
  nodes in the outline.

### 9.2 Object lifecycle and data flow (L0)

- **Lifecycle matrix**: one row per object and one column per statement, with
  create/write/update/merge/read/drop markers. It shows at a glance where a temporary table is
  born, changed, consumed and dropped.
- **Data-flow graph**: source tables → intermediate objects → targets, covering temp tables,
  table variables, INSERT…SELECT, UPDATE…FROM, MERGE and SELECT INTO.
- Inside procedures, SQL is attached to the control-flow graph so you can see on which branches a
  table is written.
- Across files, a workspace view lists all upstream and downstream scripts of a table.

### 9.3 Statement structure (L1/L2)

- Logical processing order per statement: sources → JOIN → filter → aggregate → window → output.
  This is labeled as logical order, not the database's execution plan.
- CTEs, subqueries, derived tables and temp tables are collapsible relations. Correlated
  subqueries list the outer columns they reference.
- JOIN nodes show type, keys and conditions. A LEFT JOIN whose right side is filtered in WHERE
  is flagged as an effective INNER JOIN.
- A **step-by-step reading** of each statement in plain English, generated from the IR.
- A **nested structure view** draws a statement as boxes inside boxes: CTEs, UNION / INTERSECT /
  EXCEPT branches, derived tables (`FROM (…) r`) and subqueries with their position (`WHERE IN`,
  `WHERE NOT EXISTS`, `SELECT scalar`, `SET scalar`). Each box lists its sources, clauses and output
  columns, collapses, jumps to its source range, and exports to SVG, PNG and Mermaid. The engine builds
  the tree generically from each dialect's lineage rules (`Statement.structure`, see protocol.md).

### 9.4 Column lineage and impact (L3)

- **Direct**: how each output column is derived — pass-through, expression, aggregate, CASE,
  cast, constant — hop by hop, with the expression shown.
- **Indirect**: columns used in WHERE, JOIN, GROUP BY, HAVING and window partitions that affect the
  value or row count, drawn with dashed lines (consistent with the SQLFlow data-flow model).
- **Cross-statement**: followed through temp tables, table variables, and variables assigned by
  `SELECT INTO`, up to physical tables.
- **Impact**: from a source column, list every affected output column and statement.
- Accurate lineage needs table structure. Amber uses the script's own DDL first, then metadata
  packs, then online metadata. Anything still unknown is marked Inferred.

### 9.5 Granularity and row-count risk

- Each step carries its **grain as a key set**, for example `(cust_id, month)`, derived from
  GROUP BY, DISTINCT, primary keys and unique indexes. Business names from an imported data
  dictionary replace raw keys when they are available.
- A JOIN whose key is not unique on the other side is flagged as **possible fan-out**. With
  metadata this is Certain; without it, Inferred.

### 9.6 Duplicate SQL

- Exact duplicates: normalized fingerprint (comments and whitespace removed, literals replaced
  by placeholders, identifiers case-folded).
- Structural duplicates: subtree hashes and similarity above a threshold.
- Repeated scans: the same table read several times with similar predicates in one statement or
  procedure.

### 9.7 Rules

Rules come in two layers. Static rules look only at SQL; metadata rules need keys, indexes or
statistics. Each rule has an id, severity, explanation and suggested fix, and can be suppressed
in a comment: `-- amber:ignore AMB-S012`.

| Rule | Layer | Severity |
|---|---|---|
| Cartesian product or missing JOIN condition | static | high |
| Function or implicit conversion on an indexed column | metadata | high |
| JOIN on large table without index on key | metadata | high |
| JOIN fan-out (non-unique key) | static / metadata | high |
| LEFT JOIN turned into INNER JOIN by WHERE | static | medium |
| Leading wildcard `LIKE '%x'` | static | medium |
| `NOT IN` over a nullable subquery | static | medium |
| Same table scanned repeatedly | static | medium |
| Correlated subquery replaceable by JOIN | static | medium |
| Row-by-row DML inside a cursor loop | static | medium |
| COMMIT inside a loop | static | medium |
| `UNION` where `UNION ALL` is sufficient | static | low |
| `SELECT *` in INSERT, CTAS or views | static | low |
| Redundant DISTINCT with GROUP BY | static | low |
| ORDER BY inside subquery or CTE without limit | static | low |

Without metadata these are presented as **risks**, never as a performance score.

### 9.8 Rule-based rewrites

- Correlated subquery → JOIN, IN ↔ EXISTS, repeated subquery → CTE, OR → UNION ALL, predicate
  pushdown.
- Each rewrite shows a diff and its precondition ("equivalent only if `cust_id` is unique").
- A rewrite is labeled **Equivalent** only when the precondition is proven by metadata and the
  structural check (§10.5) passes. Otherwise it is labeled **Suggestion**.
- Rewrites are never applied to the user's file automatically.

### 9.9 Semantic diff

Compares two versions of a script by IR: added or removed statements, changed sources, JOIN
changes, changed grain, and changed lineage of output columns.

---

## 10. AI-assisted optimization (optional)

### 10.1 Purpose and boundary

The AI has one job: **propose optimizations and optimized SQL**, reasoning over the structure Amber
has already extracted.

| Amber engine (always) | AI (optional) |
|---|---|
| Parses every statement with ANTLR | Never parses |
| Builds structure, data flow, lineage, grain | Receives them as context |
| Produces deterministic findings | Explains them and proposes fixes |
| Checks AI-produced SQL structurally | Produces optimization suggestions and rewritten SQL |
| Never runs SQL | Its SQL is never run either |

AI is off until a provider is configured. Every other feature works without it.

### 10.2 Providers

| Provider | Configuration | Typical use |
|---|---|---|
| **Ollama** | Base URL (default `http://localhost:11434`), model picked from the server's model list | Intranet and offline; SQL never leaves the machine |
| **OpenAI-compatible API** | Base URL, API key, model | OpenAI and any gateway that speaks the same API |

- Requests are made from the **main process**, streamed to the renderer, and can be cancelled.
- API keys are stored with `safeStorage` and never reach the renderer or the engine.
- Proxy, timeout, maximum context tokens and temperature are configurable per provider.

### 10.3 Context built from Amber's analysis

Scripts can be thousands of lines, so the raw script is not simply pasted into a prompt.
`ai.buildContext` assembles context for a chosen **scope**:

| Scope | Context sent |
|---|---|
| Statement | Full SQL of the statement; its structure tree (steps, JOINs, keys, grain); lineage of its outputs; findings; metadata of the tables involved (columns, keys, indexes, row estimates) |
| Block / procedure | The above for each statement in the block, plus the control-flow outline and lifecycle of the objects it touches |
| Script | The outline, data-flow graph and lifecycle matrix, plus condensed IR summaries per statement. Full SQL is included only for statements with findings or that the user selects, within the token budget |

For large scopes, the request is split by statement: one script-level pass picks targets, then
focused per-statement passes produce rewrites. The token budget is enforced by the builder;
anything trimmed is listed.

**Before anything is sent**, the AI panel shows a context preview: provider, model, scope,
token estimate and the exact content. For external providers the user confirms the first send
per script.

### 10.4 Response contract

The model is asked to return JSON:

```json
{
  "suggestions": [
    {
      "title": "Replace correlated subquery with a pre-aggregated join",
      "target": { "statement": 14, "range": [1203, 1488] },
      "rationale": "…",
      "expectedEffect": "…",
      "risk": "…",
      "rewrittenSql": "…"
    }
  ]
}
```

If the response is not valid JSON, the raw text is shown and labeled "Unstructured response".

### 10.5 Checking AI output

Every `rewrittenSql` goes through `ai.check` in the engine:

1. Parse with the same dialect in **strict** mode. If it does not parse, it is labeled **Invalid SQL**.
2. Compare with the original using the IR: output columns (names, order, count), write
   targets, source tables, grain, and the lineage of each output column.
3. Label the result:

| Label | Meaning |
|---|---|
| **Structure preserved** | Outputs, targets, sources, grain and lineage match |
| **Structure changed** | Lists exactly what differs |
| **Invalid SQL** | Fails strict parsing; errors shown |

No label ever claims semantic equivalence. AI suggestions are always marked **AI-generated**,
shown as a diff against the original, and never written to the user's file automatically.
The user may copy the SQL or save it to a new file. Amber never executes it.

### 10.6 Masking

An optional **Mask identifiers** setting replaces table names, column names and literals with
aliases before sending. Amber keeps the mapping locally and restores names in the response.
It is on by default for external providers and off for Ollama.

---

## 11. Offline and online modes

### 11.1 Offline mode (default)

Metadata sources, in order of priority:

1. The script's own DDL (`CREATE TABLE`, `CREATE VIEW`, temp tables).
2. Imported DDL files.
3. Data dictionaries in CSV or Excel (table, column, type, key, business name).
4. Metadata packs: versioned JSON archives saved from online mode or shared by teammates.

### 11.2 Online mode

Online mode connects **read-only** to load table structure, keys, indexes, row estimates and the
source of views, procedures and functions. The result can be saved as a metadata pack so the
same analysis works offline later.

- No JDBC driver is bundled. When a connection needs one, the app offers to download the matching driver
  from Maven Central (or a configured mirror), shows its license first, checks the file against a pinned
  SHA-256 and stores it per database type in the app's data folder; the user can also choose a jar. The
  engine loads it from that path (`driverPath`).
- Connections store host, port, database and user; passwords are encrypted with `safeStorage`.
- Metadata is loaded lazily per schema and cached; large catalogs are never scanned in full.

### 11.2.1 Several data sources and cross-database scripts

Scripts often span databases: SQL Server three- and four-part names (`db.schema.table`, linked
servers), Oracle `@dblink`, MySQL `db.table`, PostgreSQL foreign tables. Online mode therefore works
with a **workspace of several data sources** (any mix of the eight databases):

- Every object reference is first completed with the **session context** in effect at that point of
  the script — `USE db`, `SET search_path`, `ALTER SESSION SET CURRENT_SCHEMA`, `\connect`,
  SQL*Plus `CONNECT` — so `dbo.orders` after `USE sales` is `sales.dbo.orders`.
- A name that carries a database, linked server or dblink part is routed to the data source mapped
  to that part (the mapping is configured per workspace, with defaults by database name).
- Other names are looked up in the script's default data source first, then **automatically in every
  other connected data source**; catalog lookups are cached per data source and schema.
- When a name is found in more than one data source it is reported as ambiguous and the user picks;
  Amber does not guess.
- Objects are shown with their data source and database in the outline, lifecycle matrix, data-flow
  and lineage views; metadata packs record the data source of every object.

### 11.2.0 Minimal by design

Online mode is a metadata source, not a feature set. It is implemented minimally:

1. Amber analyzes the script offline first, then asks the database only about the objects the script
   references (columns, keys, indexes, row estimates, view/routine definitions) plus the server
   version — never a full catalog scan.
2. What it reads goes into the same catalog that DDL imports and metadata packs fill; every analysis
   then runs unchanged, only more precisely. The result can be saved as a metadata pack.
3. Several data sources are searched in the configured order; the first match wins and is cached;
   a name found in more than one source is reported as ambiguous.
4. The UI adds only data-source settings, a test button and an online/offline switch.

### 11.2.2 Online mode refines every feature

Online mode adds no separate feature set: every offline analysis keeps working the same way and
becomes more precise with metadata.

| Feature | Offline | With online metadata |
|---|---|---|
| Syntax check | Full grammar of the chosen dialect, latest version | Grammar switches set from the connected server's version (e.g. MySQL 5.7 vs 8.0, Oracle 19c vs 23ai), so syntax the server does not support is reported |
| Semantic check | Objects created in the script | Tables, views, columns, routines and their parameters exist; ambiguous unqualified columns; incompatible types in comparisons and assignments |
| Column lineage | Script DDL and inference; unresolved columns marked | `*` expanded, every column bound to its table: CERTAIN |
| Granularity and findings | Inferred from GROUP BY / DISTINCT | Keys, indexes and row estimates make fan-out and index findings certain |
| Calls and views | Shown as calls | Definitions loaded from the database and analyzed in place |

### 11.3 Catalog sources

| Database | Source |
|---|---|
| Oracle | `ALL_TABLES`, `ALL_TAB_COLUMNS`, `ALL_CONSTRAINTS`, `ALL_INDEXES`, `ALL_SOURCE`, `ALL_VIEWS` |
| SQL Server | `sys.tables`, `sys.columns`, `sys.indexes`, `sys.sql_modules` |
| PostgreSQL | `pg_catalog`, `information_schema` |
| MySQL / MariaDB | `information_schema` |
| DB2 | `SYSCAT.*` |
| H2 | `INFORMATION_SCHEMA` |
| SQLite | `sqlite_schema`, `pragma table_info`, `pragma index_list` |

### 11.4 Read-only guarantees

These rules are enforced in code and covered by tests:

- Amber has **no SQL console** and no code path that sends user-script statements or
  AI-generated SQL to a database.
- The only SQL Amber sends is a fixed set of catalog `SELECT` templates in the `com.github.amber.jdbc` package,
  parameterized with bind variables. CI checks that this set does not change without review.
- Connections are opened with `Connection.setReadOnly(true)` and, where supported, a read-only
  transaction or session. A read-only database account is recommended in the UI.
- Query timeout, maximum fetched rows and connection count are capped.
- Amber does **not** collect execution plans. `EXPLAIN` would require sending the user's
  statements to the database, which Amber never does.

---

## 12. User interface

The UI language is **English**.

### 12.1 Layout

```
┌─ Title bar ──────────────────────────────────────────────────────────────────┐
│ Amber   etl_daily.sql ▾    Oracle ▾   Offline ● ▾          Theme ▾   ⚙       │
├──────────────┬──────────────────────────────────────────┬────────────────────┤
│ Outline      │  [Outline] [Lifecycle] [Data flow]        │ Details            │
│ ▾ Prepare    │  [Structure] [Lineage]                    │  columns / grain   │
│   1 CREATE   │                                           │  findings          │
│   2 INDEX    │            main view                      │  source snippet    │
│ ▾ Transform  │   (graph / matrix / step reading)         │                    │
│   3 INSERT ⚠ │                                           │  AI panel          │
│   4 UPDATE   │                                           │  (when enabled)    │
│ ▸ Clean up   ├──────────────────────────────────────────┤                    │
│              │  SQL editor (synchronized)                │                    │
├──────────────┴──────────────────────────────────────────┴────────────────────┤
│ Problems: 2 high · 5 medium · 3 low     Diagnostics     Parse: strict ✓       │
└──────────────────────────────────────────────────────────────────────────────┘
```

- **Two-way navigation**: selecting a node, outline item or finding highlights its source range;
  moving the cursor in the editor selects the matching node.
- **Four levels**, drilled into on demand:

| Level | Shows | Answers |
|---|---|---|
| L0 Script | Outline, lifecycle matrix, data-flow graph | What does this script do, in which phases? |
| L1 Statement | Relations, CTEs and subqueries with a one-line summary and grain | How many steps does this statement have? |
| L2 Step | FROM → JOIN → WHERE → GROUP BY → SELECT, as a graph or as a step-by-step reading | How is the data shaped here? |
| L3 Column | The selected column's full lineage path; everything else dims | How is this value computed? |

- **Execution playback** — an animated replay of the script in logical execution order, without
  running anything:
  - play, pause, step forward and back, speed control; the current statement is highlighted in
    the editor and the outline;
  - data moves along data-flow edges into temp tables and targets, and the lifecycle matrix
    updates as each statement "runs";
  - inside a statement, steps light up in logical order (FROM → JOIN → WHERE → GROUP BY →
    window → SELECT → write);
  - in procedures, playback follows the control-flow graph. Amber has no runtime values, so at
    an IF or CASE the user picks the branch (or plays each branch in turn), and loops are shown
    as one representative iteration with a loop marker;
  - playback can be exported as an animated SVG.
- Graphs with more than 300 nodes collapse into groups automatically.
- The error screen in strict mode lists each syntax error with a code frame and offers
  "Analyze with fallback".

### 12.2 Visual identity

Amber's signature is a single idea: **the traced path is held in amber**. When a column or object
is selected, its lineage path is lit in amber and the rest of the canvas recedes. That is the
one bold moment; everything else is calm and dense, built for long reading sessions.

- **Accent**: amber, reserved for selection, the traced path and primary actions. It is
  never used for warnings.
- **Severity uses a separate hue family** so it never competes with the accent: high = red,
  medium = violet, low = blue, always paired with an icon.
- **Confidence uses line style**, not color: solid, dashed, dotted, muted.
- Edges are low contrast at rest.
- **Type**: IBM Plex Sans for the interface, IBM Plex Mono for SQL and identifiers. Both are
  bundled (SIL OFL), so no network fonts are used. Scale: 12 / 13 / 14 / 16 / 20 / 24 px,
  sentence case, no all-caps labels.
- **Motion**: only in response to user actions (expand, collapse, trace), 150–200 ms, and
  disabled when the OS requests reduced motion.

### 12.3 Themes

Themes are token sets (CSS custom properties) that can be switched at runtime from the title
bar. The default is **System**, which follows the OS light/dark setting.

| Token | Amber Light | Amber Dark | Resin (warm dark) | High Contrast |
|---|---|---|---|---|
| `--canvas` | `#F4F5F7` | `#14171B` | `#1A140E` | `#000000` |
| `--surface` | `#FFFFFF` | `#1C2025` | `#231A12` | `#0A0A0A` |
| `--ink` | `#1B1F24` | `#E6E8EB` | `#F2E8D9` | `#FFFFFF` |
| `--ink-muted` | `#5A626C` | `#99A2AD` | `#B8A488` | `#D0D0D0` |
| `--line` | `#DCE0E5` | `#2B3139` | `#3A2C1E` | `#7A7A7A` |
| `--amber` (fill) | `#E39B0B` | `#FFB224` | `#FFA51F` | `#FFC000` |
| `--amber-ink` (text on canvas) | `#8F5600` | `#FFC45C` | `#FFBE55` | `#FFD24D` |
| `--risk-high` | `#C9373C` | `#F0646A` | `#F06A5E` | `#FF5C5C` |
| `--risk-medium` | `#7B3FB8` | `#B48CF0` | `#C49AEE` | `#D6A8FF` |
| `--risk-low` | `#1F64B8` | `#6FA8F0` | `#7FB0E8` | `#8CC4FF` |

All text tokens meet WCAG AA against `--canvas` and `--surface`. The editor and graph themes are
derived from the same tokens. Themes are defined in one file so more can be added.

### 12.4 Settings

General (theme, font size), Analysis (default dialect, encoding, `allowFallback`), Metadata
(packs, imports), Connections, AI (providers, masking, token budget), Updates (check on start-up:
on/off; check now, download progress, restart to update).

---

## 13. Export

- **Graphs**: SVG generated from the view model (vector, not a screenshot), PNG rasterized from
  that SVG at 1× or 2×.
- **Mermaid and DOT** at object and CTE level. Column-level lineage is exported as SVG/HTML,
  where it stays readable.
- **Reports**: a single-file HTML report with interactive views that opens offline, and Markdown.
- **Data**: lineage, lifecycle and findings as JSON or CSV.
- **Masking**: optional replacement of table names, column names and literals before export.

---

## 14. Packaging and release

### 14.1 Product form

Users install a native app. Each installer contains:

- the Electron app (UI and main process),
- `engine.jar` and its dependencies,
- a `jlink` Java 25 runtime for that platform and architecture, with an AppCDS archive,
- all eight JDBC drivers,
- `LICENSE` and `THIRD-PARTY-NOTICES.md`.

No Java installation, driver download or `.jar` handling is ever required from the user.

### 14.2 Targets

| Platform | Arch | Artifact | Runner |
|---|---|---|---|
| Windows | x64 | `Amber-windows-x64-setup.exe` (NSIS) | `windows-latest` |
| macOS | arm64 | `Amber-macos-arm64.dmg` | `macos-latest` (Apple Silicon) |
| macOS | x64 | `Amber-macos-x64.dmg` | Intel macOS runner (`macos-15-intel`, to verify) |
| Linux | x64 | `Amber-linux-x64.deb`, `Amber-linux-x64.rpm` | `ubuntu-latest` |

The Java runtime is built natively on each runner, because `jlink` output is platform- and
architecture-specific. `sqlite-jdbc` keeps only the native library for the target.

Code signing and notarization are deferred. Until then, README and the download page explain
how to open an unsigned app on macOS (Gatekeeper) and Windows (SmartScreen).

### 14.3 Workflows

| Workflow | Trigger | Does |
|---|---|---|
| `ci.yml` | push, pull request | `mvn verify` (unit, corpus, snapshot tests), UI type-check, lint, unit tests |
| `release.yml` | push of a tag `v*` | Set version from the tag → build `engine.jar` once → matrix: jlink + AppCDS + electron-builder per target → smoke test (app starts, engine answers `analyze`) → GitHub Release with all artifacts and `SHA256SUMS` |
| (GitHub Pages) | push to `main` | Published by GitHub from `main` → `/docs` ("Deploy from a branch"); no workflow of its own |

- Maven and `package.json` stay at `1.0.0-SNAPSHOT` in the repository. The release workflow sets
  the version from the tag (`v1.0.0` → `1.0.0`).
- Tags with a pre-release suffix (`v1.0.0-rc.1`) are published as pre-releases. Others are
  published as the **latest** release.
- **Users always get the latest release.** Artifact names carry no version, so the download page
  links to stable URLs:
  `https://github.com/paganini2008/amber/releases/latest/download/Amber-macos-arm64.dmg`.
- **In-app updates**: on start-up (can be turned off for intranet use), Amber asks the GitHub API
  for the newest release (a pre-release build also follows newer pre-releases). If it is newer:
  - **Windows and Linux (.deb/.rpm)**: electron-updater downloads it in the background from the
    same release (`latest.yml` / `latest-linux.yml`, SHA-512 checked) and installs it when Amber
    restarts or quits; a notice offers "Restart to update".
  - **macOS**: a notice links to the download page. Squirrel.Mac installs only updates signed with
    an Apple Developer ID, so self-update waits for signing.
  - The version compared is the app's own (set from the release tag at build time) against the
    release tags on GitHub; nothing is fixed in the code.

### 14.4 Third-party licenses

`THIRD-PARTY-NOTICES.md` and the About screen list every bundled component with its license: the
grammar files, the Java runtime, Electron and its libraries, and fonts. JDBC drivers are not bundled
or redistributed: the user downloads each one on demand under its own license (listed in the notices
for reference).

---

## 15. GitHub Pages site

`docs/` is the source of the project site at `https://paganini2008.github.io/amber/`.

- **Home**: what Amber does, shown with a real traced lineage path rather than a marketing claim,
  plus download buttons per platform that point at `releases/latest/download/…`.
- **Docs**: getting started, views, metadata packs, AI setup (Ollama and API key), CLI.
- **Design**: this document.
- Images, screenshots, fonts and other static files live in `docs/assets/`.
- The site uses the same tokens and type as the app (Amber Light / Amber Dark, following the
  visitor's system setting, with a toggle).

---

## 16. Security and privacy

- No telemetry. No network requests except: online-mode JDBC connections the user creates, AI
  requests to providers the user configures, and the optional update check.
- Database passwords and AI API keys are encrypted with `safeStorage` and live only in the main
  process.
- The renderer is sandboxed with a strict Content Security Policy; external links open in the
  system browser.
- Logs never contain SQL text, unless the user turns on debug logging, which shows a warning.
- The engine's JSON-RPC channel is stdio only; it opens no network port.

---

## 17. Performance targets

To be measured on the validation checklist before they are committed to:

- 10,000-line script: parse plus L0/L1 views in ≤ 2 s on a typical office laptop.
- Engine warm start ≤ 1 s with AppCDS.
- Views stay interactive at 300 nodes; above that, nodes are grouped.
- Analysis is re-run on demand or after editing pauses; results are cached by statement hash so
  unchanged statements are not re-parsed.

---

## 18. Validation checklist (before building views)

- [ ] Parse-success rate on real scripts for each of the 8 dialects (Oracle, SQL Server and
      PostgreSQL first).
- [ ] grammars-v4 `sql/postgresql` coverage of PL/pgSQL bodies.
- [ ] grammars-v4 `sql/tsql` pass rate on business T-SQL.
- [ ] grammars-v4 `sql/db2` coverage of SQL PL procedures and triggers.
- [ ] Effort to write the H2 grammar; MariaDB extensions on top of the MySQL grammar.
- [ ] PL/SQL grammar parse time on a 10,000-line script (SLL/LL, parallel).
- [ ] `jlink` with JDK 25 on each runner (some JDK 25 builds omit `jmods`; confirm the chosen
      distribution supports linking), runtime size, AppCDS gain.
- [ ] Intel macOS runner availability and label.
- [ ] Installer size and cold start on a Windows office PC.
- [ ] Redistribution terms: ojdbc (FUTC), IBM JCC, MySQL Connector/J.
- [ ] License of every grammar file taken from grammars-v4.

---

## 19. Decisions and open questions

Decided:

- **No execution plans.** Amber never sends user statements to a database, including inside
  `EXPLAIN`. Performance findings come from static and metadata rules only.
- **What Amber produces**: optimized SQL (rule-based rewrites and checked AI suggestions),
  Mermaid diagrams, and images (SVG/PNG) that show the structure of a script, a statement or a
  block, plus HTML/Markdown reports and JSON/CSV data.

Open:

- **Linux arm64 and Windows arm64** builds.

---

## 20. Development phases

Version stays `1.0.0-SNAPSHOT` throughout; phases order the work by dependency.

| Phase | Content | Exit gate |
|---|---|---|
| 1. Parsing foundation | Maven skeleton, preprocessors, splitters, 8 grammars, IR, strict/fallback, corpus and fuzz tests | Parse-success targets met on real scripts |
| 2. Offline analysis | Outline, lifecycle, data flow, structure, lineage, impact, grain, duplicates, static rules, metadata packs and imports | A thousand-line script can be understood without reading it line by line |
| 3. App | Electron shell, engine supervisor, all views, themes, export | Daily use on real scripts |
| 4. Online and AI | Read-only metadata for 8 databases, metadata rules, rewrites, semantic diff, AI providers, context builder, AI check, masking | Connections pass on all 8 databases; AI suggestions checked and labeled |
| 5. Release | CLI, formatting/conversion, release workflow for all targets, Pages site, notices | Tagged release installs and runs on Windows, macOS (arm64, x64), Linux (deb, rpm) |

---

## 21. References

- grammars-v4: <https://github.com/antlr/grammars-v4> (`sql/plsql`, `sql/tsql`, `sql/postgresql`, `sql/mysql`, `sql/sqlite`, `sql/db2`)
- ANTLR 4: <https://www.antlr.org/>
- H2 grammar and compatibility modes: <https://h2database.com/html/grammar.html>, <https://h2database.com/html/features.html>
- Gudu SQLFlow data-flow model (direct and indirect lineage)
- DataHub: extracting column-level lineage from SQL
- Electron security checklist: <https://www.electronjs.org/docs/latest/tutorial/security>
- Ollama API: <https://github.com/ollama/ollama/blob/main/docs/api.md>
