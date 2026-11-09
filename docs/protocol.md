---
title: Amber — Engine Protocol
---

# Amber engine protocol

The desktop app talks to the Java engine over the engine process's **stdin/stdout**, using
**JSON-RPC 2.0** with **one JSON message per line** (UTF-8, `\n` terminated). The engine writes logs
to stderr only. The engine never runs user SQL; no method sends user statements to a database.

## Starting the engine

| Mode | Command |
|---|---|
| Packaged app | `<resources>/runtime/bin/java -XX:SharedArchiveFile=<resources>/engine/amber.jsa -jar <resources>/engine/engine.jar serve` |
| Development | `java -jar backend/amber-core/target/engine/engine.jar serve` (Java 17+); the app takes `AMBER_ENGINE_JAR` (and optionally `AMBER_JAVA`) from `ui/amber-desktop/.env`, see `.env.example` |

`engine.jar` loads its dependencies (ANTLR runtime, Jackson, JDBC drivers) from the `lib/` folder next
to it. The engine is long-lived: start it once, keep it running, restart it if it exits.

The command line also offers `engine.jar analyze <file> --dialect <id> [--fallback] [--json]` and
`engine.jar version`.

## Messages

```json
→ {"jsonrpc":"2.0","id":1,"method":"analyze","params":{"dialect":"oracle","name":"etl.sql","text":"…"}}
← {"jsonrpc":"2.0","id":1,"result":{…}}
← {"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"Unknown dialect: foo"}}
```

| Error code | Meaning |
|---|---|
| -32700 | Line is not valid JSON |
| -32600 | Not a valid request |
| -32601 | Unknown method |
| -32602 | Invalid params |
| -32603 | Internal engine error |
| -32800 | Request cancelled |

Requests are handled concurrently; responses may arrive out of order. To cancel, send the
notification `{"jsonrpc":"2.0","method":"$/cancel","params":{"id":1}}`: the request is answered with
-32800 at once (also when it was still queued) and its work is interrupted; cancelling an unknown or
finished id does nothing.

Every request with an `id` gets exactly one answer; notifications (no `id`) never get one, not even an
error. A line that is not valid JSON is answered with -32700 and, when the line starts a request object
whose `id` can still be read (e.g. a cut-off line), that `id`; otherwise `id` is null. A string value may be
up to 512 M characters, so large scripts can be sent inline as `text`. A `path` that does not exist or
cannot be read or written (`save`) is -32602 with the file name, not an engine error. When stdin ends, the
engine still answers the requests it has accepted (for up to 60 s) before it exits.

## Language of the results

The methods whose results carry sentences for people take an optional `locale` param: `"en"` (default),
`"zh-CN"` (Simplified Chinese) or `"es"` (Spanish). `zh`, `zh_CN`, `zh-Hans` and `es-*` are accepted as
aliases; any other value, a missing `locale` or a non-string one gives English. The language only
changes prose: ids, rule ids, codes, enum values, SQL, object and column names, ranges and counts are the
same in every language, and JSON-RPC error messages and parser diagnostics stay in English. Each request
has its own language, also when several run at once.

```json
→ {"jsonrpc":"2.0","id":7,"method":"analyze","params":{"dialect":"oracle","text":"…","locale":"zh-CN"}}
```

| Method | Localized fields |
|---|---|
| `analyze` | `statements[].summary` (also in `children`), `findings[].title`, `message`, `suggestion`, `suggestedSqlNote` |
| `engine.rules` | `rules[].title`, `description` |
| `doc.generate` | the whole document `text` (headings, labels, sentences; HTML `lang` attribute and title) |
| `diff` | `summary.headline`, `summary.notes`, `summary.reasons[].message`, `statements[].beforeSummary` / `afterSummary`, `objects[].message`, `lineage.columns[].message`, `lineage.indirect[].message`, `shapes[].message`, `findings[].title` / `message`, `controlFlow[].message` |
| `rewrite.suggest` | `rewrites[].title`, `explanation`, `safetyNote`, `checkDifferences` |
| `rewrite.optimize` | `statements[].applied[].title`, `skipped[].title`, `check.differences` |
| `ai.check` | `differences` |
| `er.model` | `relationships[].note`, the wording in `mermaid` (e.g. "(data flow)", "(name match?)") |
| `db.loadMetadata` | `errors` written by the engine (e.g. a missing driver jar); messages that come from a database driver stay as the driver wrote them |
| `references`, `rewrite.apply` | accept `locale`; their results hold no prose (code lines, offsets, SQL) |

`ai.buildContext` takes no `locale`: the prompt for the model is always English.

## Methods

### `engine.info`

Params: none. Result:

```json
{"name":"Amber","version":"1.0.0-SNAPSHOT","java":"25.0.5",
 "dialects":[{"id":"oracle","name":"Oracle"},{"id":"sqlserver","name":"SQL Server"}, …]}
```

Dialect ids: `oracle`, `sqlserver`, `postgresql`, `mysql`, `mariadb`, `db2`, `h2`, `sqlite`.

### `engine.rules`

The finding rules the engine knows, for a rules settings page. Params: none, or only `locale` (see
[Language of the results](#language-of-the-results)). Result:

```json
{"rules":[{"id":"AMB-P001","severity":"INFO","title":"Possible missing index",
           "description":"A column used in joins or filters has no index that starts with it …",
           "category":"performance","needsMetadata":true,"hasQuickFix":false}, …]}
```

`severity` is the default; `category` is `risk`, `performance`, `maintainability` or `info`; `needsMetadata`
rules only report when table metadata is known; `hasQuickFix` rules can have a `rewrite.suggest` rewrite.

### `analyze`

Params:

| Field | Type | Notes |
|---|---|---|
| `dialect` | string | required |
| `text` | string | the script; or give `path` |
| `path` | string | file to read; encoding detected (BOM, UTF-16 without BOM, UTF-8, GB18030) unless `encoding` is set |
| `encoding` | string | optional charset name |
| `name` | string | display name; defaults to the file name or `untitled.sql` |
| `allowFallback` | boolean | default `false` (strict) |
| `metadataPacks` | string[] | paths of metadata packs (JSON) to use |
| `ddlFiles` | string[] | DDL files whose CREATE TABLE / CREATE INDEX statements describe tables |
| `metadata` | TableMeta[] | table metadata passed inline, e.g. the `pack.tables` returned by `db.loadMetadata` |
| `rules` | Rules | optional rule settings, see below |
| `locale` | string | optional `en` (default), `zh-CN` or `es`: language of summaries and findings, see [Language of the results](#language-of-the-results) |

`Rules` (accepted by every method that computes findings: `analyze`, `doc.generate`, `diff`, `rewrite.suggest`,
`rewrite.optimize`, `ai.buildContext`):

```json
{"disabled":["AMB-S009","AMB-P001"], "severity":{"AMB-P004":"LOW","AMB-S008":"HIGH"}}
```

Disabled rules report no findings (and offer no rewrites); a configured severity replaces the rule's own for
every finding. Ids are case-insensitive; unknown ids are ignored; an unknown severity is an invalid-params error.

Result:

```json
{
  "status": "SUCCESS",                  // SUCCESS | FAILED | PARTIAL
  "dialect": "oracle",
  "name": "etl.sql",
  "lineCount": 812,
  "elapsedMs": 143,
  "diagnostics": [Diagnostic],          // errors and warnings (syntax, invisible characters, unparsed
                                        // PL/pgSQL body statements); when FAILED, statements/lifecycles are empty
  "statements": [Statement],            // top level, in source order; children nested
  "lifecycles": [Lifecycle],            // one per data object (tables, temp tables, views, …)
  "stepCount": 57,                      // statements in source order, containers included
  "lineage": Lineage,                   // column lineage of the whole script
  "findings": [Finding]                 // risk and improvement hints, never a performance score
}
```

`Statement`:

```json
{
  "id": 12,                             // step index: position in source order, depth first
  "kind": "INSERT",                     // see kinds below
  "category": "DML",                    // QUERY DML DDL PROGRAM CONTROL PROCEDURAL DCL TCL CLIENT OTHER
  "container": false,                   // true for procedures, blocks, IF, LOOP, TRY, handlers
  "confidence": "INFERRED",             // CERTAIN | INFERRED | PARTIAL | UNRESOLVED
  "name": null,                         // object a program/DDL defines, e.g. "dw.p_load"
  "summary": "Insert into tmp_order from sales.orders, crm.customers via 2 CTEs",
  "range": Range,
  "ctes": ["paid_orders", "cust_lvl"],
  "refs": [Ref],
  "children": [Statement],
  "structure": [QueryBlock],            // nested query structure; empty for statements without a query
  "executionOrder": [Step]              // logical processing order of the query; empty without one
}
```

`QueryBlock` (one box of the nested structure view; children of a query block are in source order):

```json
{"id":7,                                // pre-order number within the statement; Step.block refers to it
 "kind":"SUBQUERY",                     // QUERY | SET | CTE | DERIVED | SUBQUERY
 "name":null,                           // CTE name, derived-table alias, or set operators ("UNION ALL")
 "role":"WHERE NOT EXISTS",             // DERIVED: "FROM"; SUBQUERY: clause + predicate, e.g. "WHERE IN",
                                        // "SELECT scalar", "HAVING EXISTS", "SET scalar"
 "range":Range,
 "sources":[{"name":"crm.blocked","kind":"TABLE","alias":"b"}],   // kind as Lineage ownerKind
 "outputs":["1"],                       // output column names (expression text when unnamed)
 "clauses":["WHERE"],                   // DISTINCT TOP JOIN/JOIN ×n WHERE START WITH CONNECT BY GROUP BY
                                        // HAVING WINDOW QUALIFY ORDER BY LIMIT OFFSET FETCH PIVOT UNPIVOT FOR
 "phases":[{"name":"FROM","rank":1,"range":Range},{"name":"WHERE","rank":4,"range":Range},
           {"name":"SELECT","rank":8,"range":Range}],   // in processing order, with each clause's source
 "children":[QueryBlock]}
```

`Step` — one step of the statement's logical processing order, the order the SQL standard defines for
reading a query (not the database's execution plan; nothing is run):

```json
{"step":12,"block":3,"depth":1,"phase":"WHERE","detail":null,"range":Range}
```

- Order inside a block: `FROM` (joins) → `START WITH` → `CONNECT BY` → `WHERE` → `GROUP BY` → `HAVING` →
  `WINDOW` → `SELECT` → `QUALIFY` → `DISTINCT` → `ORDER BY` → `OFFSET` → `LIMIT` / `FETCH` / `TOP`.
- Nested boxes run just before the phase that uses them: CTEs first (`phase` `CTE`, `detail`
  "x is ready"), derived tables before `FROM`, IN / EXISTS subqueries before `WHERE` / `HAVING`, scalar
  subqueries before `SELECT`. A set operation (`phase` `UNION ALL`, …) follows its branches.
- An INSERT / UPDATE / DELETE / MERGE / CREATE … AS ends with `phase` `WRITE` (`block` -1, `detail`
  e.g. "Insert into report.daily_sales").
- `depth` is the nesting level for indentation; `range` is what to highlight in the editor.

A CTE, derived table or subquery that holds one plain query is a single box that carries that
query's sources, outputs and clauses. A `SET` box has one child per branch.

Statement kinds: `SELECT INSERT UPDATE DELETE MERGE SELECT_INTO LOCK CREATE_TABLE CREATE_TABLE_AS
CREATE_VIEW CREATE_MATERIALIZED_VIEW CREATE_INDEX CREATE_SEQUENCE CREATE_SCHEMA CREATE_SYNONYM
CREATE_TYPE CREATE_OTHER ALTER DROP TRUNCATE RENAME COMMENT CREATE_PROCEDURE CREATE_FUNCTION
CREATE_PACKAGE CREATE_PACKAGE_BODY CREATE_TRIGGER CREATE_TYPE_BODY ROUTINE BLOCK IF LOOP CASE TRY
EXCEPTION_HANDLER CURSOR CALL DYNAMIC_SQL DECLARE ASSIGNMENT RETURN GRANT REVOKE TRANSACTION SESSION
CLIENT_COMMAND DATA UNPARSED OTHER`.

`Ref` (an object a statement touches):

```json
{"name":"sales.orders","key":"SALES.ORDERS","kind":"TABLE","access":"READ",
 "confidence":"INFERRED","range":Range,"via":null}
```

- `kind`: `TABLE TEMP_TABLE TABLE_VARIABLE VIEW MATERIALIZED_VIEW CTE INDEX SEQUENCE PROCEDURE FUNCTION
  PACKAGE TRIGGER TYPE SYNONYM SCHEMA ROUTINE OTHER`
- `access`: `CREATE READ INSERT UPDATE DELETE MERGE TRUNCATE ALTER DROP EXECUTE`
- `key` is the normalized name used to match the same object across statements.
- `via`: when a statement calls a procedure or function **defined in the script** (a CALL / EXEC /
  anonymous-block call, or a function call inside a query), the call also lists the data objects that
  routine's body reads and writes — transitively through nested calls, without the routine's own
  temporaries — with `via` set to the routine name and `range` the call. Direct mentions have `via: null`.
  Routines defined outside the script are not followed (only their `EXECUTE` ref).

`Lifecycle`:

```json
{"name":"tmp_order","key":"TMP_ORDER","kind":"TEMP_TABLE","role":"INTERMEDIATE",
 "createdInScript":true,"droppedInScript":true,
 "events":[{"step":7,"access":"CREATE","range":Range,"via":null}, …]}
```

`role`: `SOURCE` (only read), `INTERMEDIATE` (written, then read), `TARGET` (written, not read
afterwards), `STRUCTURE` (only created/altered/dropped). `step` is the `id` of the statement.

`Lineage` — a column-level graph; trace upstream ("where does this value come from") by following
edges into a node, downstream ("what does this affect") by following edges out of it:

```json
{"nodes":[{"id":0,"ownerKind":"TABLE","owner":"sales.orders","ownerKey":"SALES.ORDERS","column":"amount"},
          {"id":1,"ownerKind":"CTE","owner":"paid_orders","ownerKey":"cte:PAID_ORDERS@412","column":"amount"}, …],
 "edges":[{"from":0,"to":1,"kind":"DIRECT","transform":"PASS_THROUGH","role":null,"confidence":"INFERRED",
           "statement":8,"range":Range}, …]}
```

- `ownerKind`: `TABLE TEMP_TABLE TABLE_VARIABLE VIEW MATERIALIZED_VIEW CTE SUBQUERY RESULT VARIABLE PARAMETER UNKNOWN`. A node
  with the same `ownerKey` and `column` is the same node everywhere, so paths run across statements and
  through temp tables. `UNKNOWN` marks an unqualified column whose table could not be determined.
- Unqualified columns (`from orders join customers on cust_id = id`) are placed by metadata first (script DDL,
  `ddlFiles`, packs, online). When several tables are in scope and no metadata decides, Amber uses the script's
  own **column evidence**, read offline once per script: qualified uses anywhere in it (`orders.cust_id`, or
  `o.cust_id` with `o` an alias of `orders`), `INSERT INTO t (c, …)` lists (also MySQL `INSERT … SET`), `UPDATE t
  SET c = …` (also `ON CONFLICT … DO UPDATE SET`, `ON DUPLICATE KEY UPDATE`) and MERGE `UPDATE SET` / `INSERT (…)`
  actions. If exactly one candidate table is shown to have the column it is attributed there (edges stay
  `INFERRED`, never `CERTAIN`); if none or several are, it stays `UNKNOWN` and AMB-I002 lists it. Evidence never
  overrides metadata: a table whose columns are known is judged by them only, and a table the script creates with
  a column list is judged by its DDL.
- `VARIABLE` / `PARAMETER` nodes trace values through procedural code (Oracle, SQL Server, PostgreSQL,
  MySQL, MariaDB, Db2): `owner` is the scope (routine name as written, `block@L25`, `batch@L1`, or
  `session` for MySQL `@user` variables), `column` the variable name, `ownerKey` `var:<SCOPE>@<offset>:<NAME>`.
  Writes: `SELECT … INTO`, `SELECT @v = …`, `SET @v = …`, `v := …`, `SET v = …`, declarations with defaults,
  `FETCH … INTO`; reads: variables used in VALUES, SET, select items, WHERE (FILTER). A known table column
  of the same name wins. Container statements (procedures, blocks, IF, LOOP) may carry lineage for
  assignments that are not statements of their own. `INSERT … VALUES` maps values to columns by position.
- Function results: `RETURN expr` writes a VARIABLE named `return` in the function's scope; a call of a
  function defined in the script inside an expression reads it. Call arguments flow into the routine's
  PARAMETER nodes (positional and named `p => x`, `p := x`, `@p = x`); OUT / IN OUT parameters (T-SQL
  `OUTPUT`) flow back into the caller's variables. Calls of routines outside the script are not linked.
- `RETURNING … INTO` (Oracle, PL/pgSQL) writes variables; plain `INSERT/UPDATE/DELETE … RETURNING` (PostgreSQL)
  produces RESULT columns. Dynamic SQL `EXECUTE … INTO v` lists `v` as written but has no incoming edge.
- Cursor FOR-loop records (`FOR r IN (SELECT …)`, `FOR r IN cursor`, PL/pgSQL `FOR r IN SELECT`): `r.col`
  reads the loop query's output column. Oracle collection elements `v(i)` / `v(i).f` read `v`.
- `column` `*rows*` is a pseudo column: `INDIRECT` edges (`role` `FILTER`, `JOIN`, `GROUP`) point at it
  — the source column decides which rows reach the owner, not their values.
- `transform` (direct edges): `PASS_THROUGH EXPRESSION AGGREGATE WINDOW CASE CAST CONSTANT`.
- `statement` is the id of the statement that creates the edge.

`Finding`:

```json
{"rule":"AMB-S002","severity":"MEDIUM","title":"LEFT JOIN behaves as INNER JOIN",
 "message":"The WHERE condition on r.status drops the rows the LEFT JOIN kept.",
 "suggestion":"Move the condition into the ON clause, or use INNER JOIN if that is intended.",
 "statement":3,"range":Range,"confidence":"INFERRED",
 "suggestedSql":"select … from v left join p on p.id = v.pid and p.kind = 'x'",
 "suggestedSqlKind":"REWRITE_OF_STATEMENT","verified":"STRUCTURE_PRESERVED",
 "suggestedSqlNote":"Changes behavior in edge cases: …","rewriteId":"R1"}
```

The `suggestedSql*` fields are optional (null when no optimized SQL can be built safely):
`suggestedSqlKind` `REWRITE_OF_STATEMENT` (replaces the statement — for AMB-P005 the whole loop, for AMB-P004 both
UPDATEs) or `ADDITIONAL_DDL` (a statement to add, e.g. the `CREATE INDEX` hint of AMB-P001); `verified`
`STRUCTURE_PRESERVED` / `STRUCTURE_CHANGED` (structural check as `ai.check`) or `NOT_CHECKED` (DDL, parsed only).
Suggested SQL always parses strictly in the dialect. `rewriteId` is set when the SQL is the `rewrite.suggest`
rewrite of that rule and statement (same text), so every finding with an optimized version shows it one way.

`severity`: `HIGH MEDIUM LOW INFO`. `confidence` `INFERRED` means "possible" (no metadata to prove it).
A comment `-- amber:ignore AMB-S002` (comma-separated ids) in the statement or on the line above
suppresses it. Rules: S001 Cartesian product · S002 LEFT JOIN turned INNER · S003 leading wildcard ·
S004 NOT IN subquery · S005 table read several times · S006 row-by-row DML in a loop · S007 COMMIT in
a loop · S008 function on a filtered column · S009 UNION vs UNION ALL · S010 SELECT * written to a
table/view · S011 DISTINCT with GROUP BY · S012 ORDER BY in a subquery · D001 duplicate statement ·
D002 repeated subquery · M001 many-to-many join (needs metadata) · I001 dynamic SQL · I002 columns without a table · I003 checks stopped early (time budget `AMBER_FINDINGS_SECONDS` ran out; marks the first unchecked statement).

Performance and maintenance hints (offline, deterministic; `engine.rules` has the full list):

| Rule | What | Severity | Metadata | Optimized SQL |
|---|---|---|---|---|
| P001 | possible missing index: a join / filter column no index starts with; counted across the script, once per table and column | INFO (LOW from 3 statements) | required | `CREATE INDEX` (DDL) |
| P002 | implicit conversion: column of a known type vs a literal / declared variable of another type (text vs number, date vs non-ISO / NLS-dependent string, VARCHAR vs N'…') | MEDIUM (LOW when only the literal is converted) | required | literal in the column's type, when mechanical |
| P003 | correlated scalar subquery in the select list (runs per row) | LOW | no | LEFT JOIN of a grouped derived table (quick fix) |
| P004 | consecutive UPDATEs of the same rows; UPDATE + INSERT … WHERE NOT EXISTS (upsert) | INFO | no | the combined UPDATE |
| P005 | cursor loop whose body is one DML using only the cursor row (FOR r IN (SELECT …), PL/pgSQL / Db2 FOR, FETCH loops) | MEDIUM | no | the set-based statement (MERGE / UPDATE … FROM / INSERT … SELECT / DELETE … EXISTS) |
| P006 | OR across different indexed columns of one table | INFO | required | UNION ALL branches |
| P007 | arithmetic / concatenation on a compared column; NVL / COALESCE / ISNULL on a join column | LOW (certain with an index) | no | `col + 1 = 5` → `col = 5 - 1` |
| P008 | COUNT(*) > 0 / = 0 to test existence (also COUNT INTO v; IF v > 0) | LOW | no | EXISTS / NOT EXISTS |
| P009 | DISTINCT over joins when the select list covers a unique key of one table | LOW | required | — |

`Range` and `Diagnostic`:

```json
{"start":{"offset":120,"line":5,"column":1},"end":{"offset":180,"line":7,"column":12}}
{"severity":"ERROR","code":"syntax","message":"mismatched input 'from' expecting …",
 "range":Range,"offending":"from","expected":["'('","IDENTIFIER"]}
```

Lines and columns are 1-based; offsets are 0-based UTF-16 indexes into the text, end exclusive. Lines end
at `\n`, `\r\n` or a lone `\r` only (as in the editor); Unicode line separators (U+2028, U+2029, U+0085) are
reported as invisible characters and read as spaces. Columns count UTF-16 units (an emoji counts 2).

A MySQL statement with more than 12 opening parentheses in a row before a query (at its start or after
UNION / EXCEPT / INTERSECT) is reported as a syntax error instead of being parsed: the MySQL grammar needs
exponential time and memory for it. Any parser prediction that needs more than 20 million steps is
abandoned the same way.

### Metadata and online mode

Online mode is optional (offline is the default) and minimal: it only reads metadata of the objects a
script references, through the JDBC `DatabaseMetaData` API — Amber sends no SQL of its own and never
the user's SQL. What it reads is passed back to `analyze` as `metadata` (or saved as a pack), and every
analysis becomes more precise.

`TableMeta`:

```json
{"catalog":null,"schema":"shop","name":"purchase","kind":"TABLE",
 "columns":[{"name":"id","type":"bigint(19)","nullable":false}, …],
 "primaryKey":["id"],"uniqueKeys":[["email"]],
 "indexes":[{"name":"ix_purchase_customer","columns":["customer_id"],"unique":false}],
 "rowEstimate":120000,"source":"pg-prod",
 "foreignKeys":[{"name":"fk_purchase_customer","columns":["customer_id"],
                 "refCatalog":null,"refSchema":"shop","refTable":"customer","refColumns":["id"]}]}
```

`foreignKeys` come from `DatabaseMetaData.getImportedKeys` (online), from `REFERENCES` / `FOREIGN KEY` clauses
of CREATE TABLE and `ALTER TABLE … ADD [CONSTRAINT n] FOREIGN KEY` (DDL files and the script itself), or from a
pack. `refColumns` is empty when the DDL names only the table (its primary key is meant). The field is optional:
packs and inline metadata without it load with no foreign keys (the pack `format` stays 1).

`MetadataPack` (file format, `format` 1): `{"format":1,"dialect":"oracle","source":"…","created":"2026-…","tables":[TableMeta]}`.

`DataSource` (password is passed per request by the app, which stores it encrypted; the engine
never saves or logs it):

```json
{"id":"pg-prod","dialect":"postgresql","url":null,"host":"db.local","port":5432,"database":"demo",
 "user":"reader","password":"…","properties":{},"driverPath":null}
```

`driverPath` is the absolute path of a JDBC driver `.jar`. **The engine includes no JDBC driver**, so online
mode needs it for every database: the desktop app downloads the matching driver from Maven Central on demand
(after the user accepts its license; the file is checked against a pinned SHA-256) or the user chooses a jar,
and the app sends its path. The engine loads the driver from that jar (found through
`META-INF/services/java.sql.Driver`, or a well-known driver class) and uses the one that accepts the
connection's URL. Loaded jars are cached per path and modification time. Connections stay read-only and
metadata-only either way. Without `driverPath`, only a driver on the engine's class path would be used, and
only tests have any.

When no driver is usable, `db.test` answers with error `-32602` (invalid params) and a plain message, and
`db.loadMetadata` lists the same message under `errors` for that source. The cases are:

- a source without `driverPath`: "The Db2 JDBC driver is not installed. Amber does not include database drivers:
  download it with Download driver in the connection settings …", naming the database;
- a path that is not absolute, does not exist, is a folder, cannot be read or is not a jar;
- a jar with no driver for the URL (shown with any `password=` hidden).

Like
other error messages, `db.test`'s stay English; `db.loadMetadata` takes the optional `locale` and gives its
`errors` in that language.

| Method | Params | Result |
|---|---|---|
| `metadata.import` | `{dialect, ddlFiles?: string[], packs?: string[], save?: path}` | `{dialect, tables, names[], saved, pack}` — builds a pack from DDL files and/or packs |
| `db.test` | `{dataSource}` | `{product, version, majorVersion, minorVersion, user, readOnly}` — `user` may be null; some drivers ignore `readOnly` |
| `db.loadMetadata` | `{dialect, text \| path, dataSources: [DataSource], save?: path, locale?}` | `{tables, missing[], ambiguous[{name, dataSources[], chosen}], errors{id: message}, servers{id: info}, saved, pack}` — `tables` is a count; the table list is `pack.tables`. A `path` is decoded with the engine's detection (BOM, UTF-16 without BOM, UTF-8, GB18030); send `text` for other encodings. |

`db.loadMetadata` analyzes the script, takes the tables and views it reads or writes but does not
create itself, looks each up in every data source, and lists names found nowhere in `missing`. A name
found in several sources is listed in `ambiguous`; `chosen` is the source whose table is used: a source
of the script's database family first (MySQL and MariaDB count as one), then the table holding most of
the columns the script uses, then the order of `dataSources`.

With metadata, rules become more precise, e.g. `AMB-S004` (NOT IN) disappears when the subquery column
is NOT NULL and becomes certain when it is nullable; `AMB-S008` becomes certain when the column is
indexed; `AMB-M001` only reports joins whose columns exist and are unique on neither side.

### AI assistance (optional)

The engine never calls a model and never parses with one. It turns its own analysis into a prompt,
the app sends that prompt to the provider the user configured (Ollama, or an OpenAI-compatible API with
the user's key), and the engine then checks what came back.

`ai.buildContext` — params: the `analyze` params plus

| Field | Type | Notes |
|---|---|---|
| `statements` | int[] | statement ids in scope; empty or absent means the whole script |
| `tokenBudget` | int | approximate token limit of the user message, default 6000 |
| `mask` | boolean | default `true`: object names, columns and string literals become `T1`, `C1`, `'S1'` |
| `notes` | string | optional: the developer's own notes for the model (data volumes, constraints, goals); added as "Notes from the developer", masked with the same aliases, never trimmed |

Result: `{system, user, tokenEstimate, statements: int[], trimmed: string[], mask{alias: original}}`. `system` and
`user` are the two chat messages. The user message holds, per statement, its SQL, summary, references,
CTEs, column lineage and findings, and the known metadata of the tables it uses. Statements that do not
fit the budget are summarized or listed in `trimmed`. `mask` stays on the user's machine. The model is
asked to answer with
`{"suggestions":[{title, target:{statement}, rationale, expectedEffect, risk, rewrittenSql}]}`.

`ai.maskText` — params `{text, mask}`, result `{text}`: masks a follow-up message the developer writes
after the first answer with the context's `mask`, so the conversation keeps the same aliases (whole words,
case-insensitive; one-letter names and literals stay as written). The app sends it as the next user
message of the same chat.

`ai.check` — params `{dialect, original, rewritten, mask?, metadataPacks?, ddlFiles?, metadata?}`.
It restores the masked names in `rewritten`, parses it strictly, and compares its structure with `original`:
output columns, written objects, source tables, grouping columns and the base-column lineage of every
output. Result `{label, sql, differences[], diagnostics[]}`:

| `label` | Meaning |
|---|---|
| `STRUCTURE_PRESERVED` | parses, and every compared aspect is the same |
| `STRUCTURE_CHANGED` | parses, and `differences` lists what changed |
| `INVALID_SQL` | does not parse; `diagnostics` says why |

`STRUCTURE_PRESERVED` is not a proof that the results are the same. The UI shows it as "structure
preserved", never as "equivalent". Nothing is executed.

### Maintenance tools

`references` — find usages and go to definition inside the script. Params: the `analyze` params plus
`object` (a table, view, temp table, CTE, procedure… matched case-insensitively) **or** `table` + `column`.
Result `{name, kind, definition, occurrences[]}`; each occurrence `{statement, access, range, line, via}`
(`access`: the Ref access, or `READ` / `WRITE` / `CREATE` for columns; `line` is "12: <source line>").
`definition` is where the script defines it (CREATE, `WITH name AS`, CREATE PROCEDURE…), or null when it
comes from outside. Column occurrences follow aliases and skip same-named columns of other tables.

`doc.generate` — a handover document of the script. Params: the `analyze` params plus `format`
(`markdown`, default, or `html`) and optional `save` (file path). Result `{format, text, saved}`. Sections:
overview, what the script does (outline), objects and their roles, data flow (Mermaid), where the target
columns come from, risks, the most complex statements with their logical order, and parts Amber could not
read. The HTML is one self-contained page without scripts. Command line:
`engine.jar doc FILE --dialect ID [--html] [--out PATH] [--fallback]`.

`diff` — change impact check between two versions of a script: what the change really changes, not
just which lines. Params `{dialect, before: {text | path, encoding?, name?}, after: {…}, metadataPacks?,
ddlFiles?, metadata?}`. Result:

```json
{"dialect":"oracle","before":Side,"after":Side,"elapsedMs":42,
 "summary":{"risk":"HIGH","headline":"…","statements":{"unchanged":40,"formatOnly":3,"modified":2,"added":1,"removed":0,"moved":0},
            "reasons":[{"level":"HIGH","code":"FILTER_REMOVED","message":"DELETE sales.orders lost its WHERE clause …",
                        "beforeStatement":3,"afterStatement":3,"beforeRange":Range,"afterRange":Range}], "notes":[]},
 "statements":[StatementPair], "objects":[ObjectChange], "lineage":{"columns":[ColumnChange],"indirect":[IndirectChange]},
 "shapes":[ShapeChange], "findings":[FindingChange], "controlFlow":[ControlFlowChange]}
```

- `Side`: `{name, status, fallback, lineCount, stepCount, unparsed, diagnostics[]}`; a side that fails strict
  parsing is compared with fallback parsing (`fallback` true).
- `StatementPair`: `{status, kind, target, beforeId, afterId, beforeRange, afterRange, beforeSummary,
  afterSummary, similarity, reordered, hunks[{before, after, beforeText, afterText}], children[]}`; `status`
  `UNCHANGED FORMAT_ONLY MODIFIED ADDED REMOVED MOVED`; nested like `statements`, in the new version's order.
- `ObjectChange.kind`: `ADDED REMOVED ACCESS_ADDED ACCESS_REMOVED ROLE_CHANGED KIND_CHANGED REF_ADDED REF_REMOVED`.
- `ColumnChange` (columns written to tables, views, temp tables): `kind` `ADDED REMOVED SOURCES_CHANGED
  TRANSFORM_CHANGED`, `beforeSources`/`afterSources` are base columns (through CTEs, subqueries, temp tables).
- `IndirectChange`: a FILTER / JOIN / GROUP source `ADDED` or `REMOVED` for a target.
- `ShapeChange.kind`: `BLOCK_ADDED BLOCK_REMOVED SOURCE_ADDED SOURCE_REMOVED CLAUSE_ADDED CLAUSE_REMOVED
  JOIN_COUNT_CHANGED SET_OPERATOR_CHANGED OUTPUTS_CHANGED OUTPUT_ORDER_CHANGED RENAMED`.
- `FindingChange`: `NEW` or `RESOLVED` findings. `ControlFlowChange`: a statement moved into or out of an
  IF / LOOP / handler.
- Risk: HIGH when a written column's sources change, a DELETE/UPDATE/MERGE loses a filter, a target
  changes, or a DROP/TRUNCATE/unfiltered DELETE on a pre-existing object appears; MEDIUM for transform,
  join/grouping/shape, new-source, control-flow changes or a new HIGH finding; LOW for format-only,
  renamed aliases/CTEs and similar. Each reason carries ranges on both sides.

`rewrite.suggest` — deterministic quick-fixes for findings, no AI. Params: the `analyze` params plus
`statements` (ids; empty = all) and `rules` (disabled rules offer no rewrites). Result `{rewrites: [Rewrite]}`:

```json
{"id":"R2","rule":"AMB-S004","title":"NOT IN (subquery) → NOT EXISTS","explanation":"…","statement":15,
 "edits":[{"range":Range,"replacement":"NOT EXISTS (SELECT 1 FROM …)"}],"rewrittenStatement":"SELECT …",
 "safety":"CHANGES_BEHAVIOR_IN_EDGE_CASES","safetyNote":"NOT IN returns no rows when the subquery yields a NULL …",
 "check":"STRUCTURE_PRESERVED","checkDifferences":[],"conflictsWith":null}
```

- `safety`: `EQUIVALENT` (always the same result), `EQUIVALENT_WITH_METADATA` (the same given the column
  types / nullability / keys known from metadata), `CHANGES_BEHAVIOR_IN_EDGE_CASES` (read `safetyNote`).
- Every rewrite was applied, parsed strictly and compared structurally (`check`, as `ai.check`) before it is
  returned. Edits are minimal and keep the author's layout, comments and keyword case. `conflictsWith` names
  an earlier rewrite whose edits overlap. Rules: S002 LEFT JOIN made inner, S004 NOT IN → NOT EXISTS, S008
  function on a date column → range, S009 UNION → UNION ALL, S010 SELECT * → column list, S011 DISTINCT
  with GROUP BY, S012 ORDER BY without effect, D002 repeated subquery → CTE, P003 correlated aggregate
  subquery in the select list → LEFT JOIN of a grouped derived table (`EQUIVALENT_WITH_METADATA` when both
  correlated columns have the same type in metadata, else `CHANGES_BEHAVIOR_IN_EDGE_CASES`).

`rewrite.optimize` — "apply all": every non-conflicting rewrite of a statement applied together, the suggester
run again on the result until nothing changes (at most 4 passes), and the final statement verified. Params:
the `analyze` params plus `statements` (ids; empty = all), `includeEdgeCases` (default `false`: only `EQUIVALENT`
and `EQUIVALENT_WITH_METADATA` rewrites) and `rules`. Result:

```json
{"statements":[{"statement":4,"original":"select distinct …","optimized":"select …",
   "applied":[{"id":"R2","rule":"AMB-S011","title":"…","safety":"EQUIVALENT"},
              {"id":"2:R1","rule":"AMB-S012","title":"…","safety":"EQUIVALENT"}],
   "skipped":[{"id":"R3","rule":"AMB-S009","title":"…","reason":"EDGE_CASE"}],
   "safety":"EQUIVALENT","check":{"label":"STRUCTURE_CHANGED","differences":["Ordering and row limits: …"]},
   "additionalDdl":["CREATE INDEX ix_orders_status ON sales.orders (status)"]}],
 "optimizedText":"… the whole script with every optimized statement replaced …"}
```

- `applied[].id`: the rewrite id of its pass (`R2` in pass 1, `2:R1` in pass 2). `safety`: the weakest applied.
- `skipped[].reason`: `CONFLICT` (overlaps an applied rewrite and never became applicable), `EDGE_CASE` (needs
  `includeEdgeCases`), `DISABLED` (rule turned off in `rules`), `UNVERIFIED` (the combination did not parse or
  verify; the statement keeps its text).
- `check`: structural check of the final statement against the original (null when only DDL is suggested).
- `additionalDdl`: optimized SQL of kind `ADDITIONAL_DDL` from the statement's findings (AMB-P001 index hints).
- Statements with nothing to change are omitted; nothing is executed or saved.

`rewrite.apply` — params `{text, edits: [{start, end, replacement}]}` (0-based offsets), result `{text}`;
nothing is saved.

`format` — layout only, never semantics. Params `{dialect, text | path, encoding?, indentSize? (4),
keywordCase? (UPPER | LOWER | PRESERVE), maxLineWidth? (100), commaStyle? (TRAILING | LEADING), start?, end?}`;
with `start`/`end` (0-based offsets) only the statements they overlap are formatted ("format selection").
Result `{text, changed, warnings[], skipped[Range]}`. The formatter only changes whitespace and keyword
case; every statement is lexed again and must keep the same tokens and comments byte for byte, and the
script must split into the same statements, or that statement is kept as written (listed in `skipped`
with a warning). Client commands, PostgreSQL `$$` bodies and terminators are kept as written.

### Entity-relationship model

`er.model` — the tables a script uses and how they relate, inferred offline. Legacy schemas rarely declare
foreign keys; the relationships hide in the script's join conditions, so Amber reads both. Nothing is executed.
Params: the `analyze` params (`dialect`, `text | path`, `encoding?`, `name?`, `allowFallback?`, `metadataPacks?`,
`ddlFiles?`, `metadata?`) plus `includeDataFlow?` (default `false`) and `includeNameMatch?` (default `true`).
A script that fails strict parsing is an error (-32602) unless `allowFallback` is set. Result:

```json
{"entities":[{"key":"SALES.ORDERS","name":"sales.orders","kind":"TABLE","createdInScript":false,
              "definitionRange":null,"metadataSource":"pg-prod",
              "columns":[{"name":"id","type":"NUMBER","nullable":false,"pk":true,"unique":true,"fk":false,
                          "joined":true,"source":"METADATA"},
                         {"name":"customer_id","type":null,"nullable":null,"pk":false,"unique":false,"fk":true,
                          "joined":true,"source":"USAGE"}]}],
 "relationships":[{"id":"R1","from":{"entity":"SALES.ORDERS","columns":["customer_id"]},
                   "to":{"entity":"SALES.CUSTOMERS","columns":["id"]},
                   "cardinality":"MANY_TO_ONE","evidence":"JOIN","confidence":"INFERRED",
                   "occurrences":[{"statement":3,"range":Range}],"count":4,
                   "note":"Seen in 4 joins; sales.customers.id covers the primary key of sales.customers.",
                   "constraint":null}],
 "mermaid":"erDiagram\n    sales_orders {\n …",
 "stats":{"statements":12,"joinEqualities":9,"unresolved":1,"elapsedMs":3}}
```

- **Entities**: tables, views, materialized views, temp tables and table variables the script mentions (not CTEs,
  derived tables or aliases), in order of first mention; `key` as `Ref.key` (an unqualified name joins its single
  qualified twin). `kind` `TABLE VIEW MATERIALIZED_VIEW TEMP_TABLE TABLE_VARIABLE`. `createdInScript` / `definitionRange`:
  a CREATE TABLE / VIEW, CREATE TABLE AS or SELECT INTO of the script. Tables only referenced by a foreign key of the
  script's own DDL are entities too; metadata foreign keys only link entities of the script.
- **Columns**: from DDL (`source` `DDL`: the script or `ddlFiles`), metadata (`METADATA`: packs, `metadata`, online),
  else from use in the script (`USAGE`: lineage and join columns). `pk` part of the primary key; `unique` unique on its
  own; `fk` on the referencing side of a declared key or of a join that points at a key; `joined` used in an
  equality join.
- **Relationships**: `from` / `to` columns pair up in order; for `MANY_TO_ONE` `from` is the many side. `evidence`
  (strongest first): `DECLARED_FK` (script or DDL files, `CERTAIN`), `METADATA_FK` (packs, online, `CERTAIN`),
  `JOIN`, `NAME_MATCH`, `DATA_FLOW`. A declared key and joins on the same columns are one relationship (evidence of
  the key, the joins' occurrences kept). `count` counts every occurrence; at most 100 are listed. `occurrences[].statement`
  is the step id of `analyze`; `range` the equality condition(s). `constraint`: name of the declared key.
- **Joins**: `ON` conditions (also nested joins and MERGE … ON), `USING (…)`, `WHERE` equi-joins of comma joins and
  correlated subqueries (`p.order_id = o.id` with `o` from the outer query or the UPDATE / DELETE target), through
  aliases (T-SQL `UPDATE o … FROM t o` too). Equalities of one condition between the same two table references form
  one (composite) key. Columns of CTEs and derived tables count only when lineage maps them 1:1 (PASS_THROUGH) to a
  base column. Skipped: conditions in an OR, comparisons through functions, casts or arithmetic, literals and
  variables, a column compared with itself, and columns whose table cannot be told (`stats.unresolved`; give
  metadata or qualify the column). Unqualified columns that only the script's column evidence (see `Lineage`)
  places count as resolved; such a join keeps the confidence the cardinality rules give (`INFERRED` or `POSSIBLE`,
  never `CERTAIN`) and its `note` names the evidence (`orders.cust_id (INSERT column list, line 12)`). Self joins are kept (`employees.manager_id → employees.id`). NATURAL JOIN is not read.
- **Cardinality**: the side whose columns cover a primary key, unique key or unique index (metadata or DDL) is the
  one side → `MANY_TO_ONE`, `INFERRED`; both sides → `ONE_TO_ONE`. `MANY_TO_MANY` only when metadata with keys
  proves neither side unique. Otherwise, for a single-column join, naming conventions orient it as `MANY_TO_ONE`
  with `POSSIBLE`: the one side's column looks like its table's own key (`id`, or `customer_id` / `customerid` /
  `customer_no` / `customer_code` / `customer_key` in `customer`, `customers`, `tbl_customer`) and the other
  side's like a reference (`…_id`, `…_no`, `…_code`, …); a side whose keys are known and do not cover the column is
  never the one side. Else `UNKNOWN` (`POSSIBLE`). `ONE_TO_MANY` is never produced (relationships are oriented
  many → one) but stays in the enum.
- **Name matches** (`NAME_MATCH`, `POSSIBLE`, `MANY_TO_ONE`): a column like `customer_id` of one entity and exactly one
  other entity named `customer` / `customers` / `tbl_customer` whose single-column primary key (or, without keys, a
  column of the same name or `id`) fits, when no relationship links that column yet. `includeNameMatch:false` turns
  them off.
- **Data flow** (`DATA_FLOW`, `UNKNOWN`, `INFERRED`, only with `includeDataFlow`): rows or values copied from one table
  into another by INSERT … SELECT, CREATE TABLE / VIEW AS, SELECT INTO, UPDATE / MERGE (through CTEs and derived
  tables); columns pair source → target (empty for `*`).
- **`mermaid`**: an `erDiagram` for export. Names are reduced to letters, digits, `_` and `-` (made unique; the
  original column name is kept as a comment); at most 12 attributes per entity (keys, foreign keys and joined
  columns first) plus a `"… n more"` line. Crow's feet: `MANY_TO_ONE` `}o--||`, `ONE_TO_ONE` `|o--||`,
  `ONE_TO_MANY` `||--o{`, `MANY_TO_MANY` `}o--o{`, `UNKNOWN` `}o..o{` with "(?)" in the label; declared keys are solid
  lines, inferred ones dashed (`..`). Labels name the columns; data flows and name matches say so.

### Methods added as features land

`export` (images and Mermaid are produced by the app). The UI must treat unknown fields as optional.
