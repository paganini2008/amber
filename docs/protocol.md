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
| Development | `java -jar backend/amber-core/target/engine/engine.jar serve` (JDK 25); the app takes `AMBER_ENGINE_JAR` (and optionally `AMBER_JAVA`) from `ui/amber-desktop/.env`, see `.env.example` |

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
notification `{"jsonrpc":"2.0","method":"$/cancel","params":{"id":1}}`.

## Methods

### `engine.info`

Params: none. Result:

```json
{"name":"Amber","version":"1.0.0-SNAPSHOT","java":"25.0.5",
 "dialects":[{"id":"oracle","name":"Oracle"},{"id":"sqlserver","name":"SQL Server"}, …]}
```

Dialect ids: `oracle`, `sqlserver`, `postgresql`, `mysql`, `mariadb`, `db2`, `h2`, `sqlite`.

### `analyze`

Params:

| Field | Type | Notes |
|---|---|---|
| `dialect` | string | required |
| `text` | string | the script; or give `path` |
| `path` | string | file to read; encoding detected (BOM, UTF-8, GB18030) unless `encoding` is set |
| `encoding` | string | optional charset name |
| `name` | string | display name; defaults to the file name or `untitled.sql` |
| `allowFallback` | boolean | default `false` (strict) |
| `metadataPacks` | string[] | paths of metadata packs (JSON) to use |
| `ddlFiles` | string[] | DDL files whose CREATE TABLE / CREATE INDEX statements describe tables |
| `metadata` | TableMeta[] | table metadata passed inline, e.g. the `pack.tables` returned by `db.loadMetadata` |

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
- `VARIABLE` / `PARAMETER` nodes trace values through procedural code (Oracle, SQL Server, PostgreSQL,
  MySQL, MariaDB, Db2): `owner` is the scope (routine name as written, `block@L25`, `batch@L1`, or
  `session` for MySQL `@user` variables), `column` the variable name, `ownerKey` `var:<SCOPE>@<offset>:<NAME>`.
  Writes: `SELECT … INTO`, `SELECT @v = …`, `SET @v = …`, `v := …`, `SET v = …`, declarations with defaults,
  `FETCH … INTO`; reads: variables used in VALUES, SET, select items, WHERE (FILTER). A known table column
  of the same name wins. Container statements (procedures, blocks, IF, LOOP) may carry lineage for
  assignments that are not statements of their own. `INSERT … VALUES` maps values to columns by position.
- `column` `*rows*` is a pseudo column: `INDIRECT` edges (`role` `FILTER`, `JOIN`, `GROUP`) point at it
  — the source column decides which rows reach the owner, not their values.
- `transform` (direct edges): `PASS_THROUGH EXPRESSION AGGREGATE WINDOW CASE CAST CONSTANT`.
- `statement` is the id of the statement that creates the edge.

`Finding`:

```json
{"rule":"AMB-S002","severity":"MEDIUM","title":"LEFT JOIN behaves as INNER JOIN",
 "message":"The WHERE condition on r.status drops the rows the LEFT JOIN kept.",
 "suggestion":"Move the condition into the ON clause, or use INNER JOIN if that is intended.",
 "statement":3,"range":Range,"confidence":"INFERRED"}
```

`severity`: `HIGH MEDIUM LOW INFO`. `confidence` `INFERRED` means "possible" (no metadata to prove it).
A comment `-- amber:ignore AMB-S002` (comma-separated ids) in the statement or on the line above
suppresses it. Rules: S001 Cartesian product · S002 LEFT JOIN turned INNER · S003 leading wildcard ·
S004 NOT IN subquery · S005 table read several times · S006 row-by-row DML in a loop · S007 COMMIT in
a loop · S008 function on a filtered column · S009 UNION vs UNION ALL · S010 SELECT * written to a
table/view · S011 DISTINCT with GROUP BY · S012 ORDER BY in a subquery · D001 duplicate statement ·
D002 repeated subquery · M001 many-to-many join (needs metadata) · I001 dynamic SQL · I002 columns without a table.

`Range` and `Diagnostic`:

```json
{"start":{"offset":120,"line":5,"column":1},"end":{"offset":180,"line":7,"column":12}}
{"severity":"ERROR","code":"syntax","message":"mismatched input 'from' expecting …",
 "range":Range,"offending":"from","expected":["'('","IDENTIFIER"]}
```

Lines and columns are 1-based; offsets are 0-based UTF-16 indexes into the text, end exclusive.

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
 "rowEstimate":120000,"source":"pg-prod"}
```

`MetadataPack` (file format, `format` 1): `{"format":1,"dialect":"oracle","source":"…","created":"2026-…","tables":[TableMeta]}`.

`DataSource` (password is passed per request by the app, which stores it encrypted; the engine
never saves or logs it):

```json
{"id":"pg-prod","dialect":"postgresql","url":null,"host":"db.local","port":5432,"database":"demo",
 "user":"reader","password":"…","properties":{}}
```

| Method | Params | Result |
|---|---|---|
| `metadata.import` | `{dialect, ddlFiles?: string[], packs?: string[], save?: path}` | `{dialect, tables, names[], saved, pack}` — builds a pack from DDL files and/or packs |
| `db.test` | `{dataSource}` | `{product, version, majorVersion, minorVersion, user, readOnly}` — `user` may be null; some drivers ignore `readOnly` |
| `db.loadMetadata` | `{dialect, text \| path, dataSources: [DataSource], save?: path}` | `{tables, missing[], ambiguous[{name, dataSources[], chosen}], errors{id: message}, servers{id: info}, saved, pack}` — `tables` is a count; the table list is `pack.tables`. A `path` is decoded with the engine's detection (BOM, UTF-8, GB18030); send `text` for other encodings. |

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
`statements` (ids; empty = all). Result `{rewrites: [Rewrite]}`:

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
  with GROUP BY, S012 ORDER BY without effect, D002 repeated subquery → CTE.

`rewrite.apply` — params `{text, edits: [{start, end, replacement}]}` (0-based offsets), result `{text}`;
nothing is saved.

`format` — layout only, never semantics. Params `{dialect, text | path, encoding?, indentSize? (4),
keywordCase? (UPPER | LOWER | PRESERVE), maxLineWidth? (100), commaStyle? (TRAILING | LEADING), start?, end?}`;
with `start`/`end` (0-based offsets) only the statements they overlap are formatted ("format selection").
Result `{text, changed, warnings[], skipped[Range]}`. The formatter only changes whitespace and keyword
case; every statement is lexed again and must keep the same tokens and comments byte for byte, and the
script must split into the same statements, or that statement is kept as written (listed in `skipped`
with a warning). Client commands, PostgreSQL `$$` bodies and terminators are kept as written.

### Methods added as features land

`export` (images and Mermaid are produced by the app). The UI must treat unknown fields as optional.
