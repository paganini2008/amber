# Upstream grammar

| | |
|---|---|
| Source | https://github.com/antlr/grammars-v4/tree/9fa9f94b20cd3225bd84f27554515da207ec39ff/sql/postgresql |
| Commit | `9fa9f94b20cd3225bd84f27554515da207ec39ff` |
| License | MIT (see the header of each `.g4` file) |
| Imported | 2026-11-07 |

Files taken from upstream:

- `sql/postgresql/PostgreSQLLexer.g4`
- `sql/postgresql/PostgreSQLParser.g4`
- `sql/postgresql/Java/LexerDispatchingErrorListener.java`
- `sql/postgresql/Java/ParserDispatchingErrorListener.java`
- `sql/postgresql/Java/PostgreSQLLexerBase.java`
- `sql/postgresql/Java/PostgreSQLParserBase.java`

Local changes: the Java support classes got a `package` declaration, and
`PostgreSQLParserBase.GetRoutineBodyString` returns `""` when error recovery left the routine body's
string constant out (it threw a `NullPointerException` on broken `CREATE FUNCTION … AS` text, found by
the robustness fuzzer). Every further change to
the grammar must add a reproducing case to `amber-corpus`.

## Local coverage changes

Gaps found by a conformance run against SQL taken from the PostgreSQL documentation (up to PG 17).

Lexer (`PostgreSQLLexer.g4`):

- Removed the PL/pgSQL-only keyword tokens `ASSERT CONSTANT DEBUG DIAGNOSTICS DUMP ELSIF EXCEPTION EXIT
  FOREACH GET INFO LOOP NOTICE OPEN PERFORM RAISE ROWTYPE SLICE SQLSTATE STACKED USE_COLUMN USE_VARIABLE
  WARNING WHILE`. They are not SQL keywords in PostgreSQL and no parser rule used them (except `EXIT` in
  `colLabel`, removed), so they lex as identifiers: `SELECT open FROM t`, `CREATE TABLE t (sqlstate
  char(5))`, `SET client_min_messages TO warning`. PL/pgSQL bodies are read by `PlPgSqlParser`.
- Removed the unused tokens `XMLAGG XMLCOMMENT XPATH XPATH_EXISTS XML_IS_WELL_FORMED
  XML_IS_WELL_FORMED_DOCUMENT XML_IS_WELL_FORMED_CONTENT` (ordinary functions in PostgreSQL) and
  `FORMAT_LA` (its literal was the text `FORMAT_LA`; `json_format_clause` now uses `FORMAT`).
- `BREADTH` matched the misspelt text `BREATH`.
- `PLSQLVARIABLENAME` also covers psql's `:'name'` and `:"name"` (replaces the unused
  `PLSQLIDENTIFIER`).
- `StringConstant`: string constants separated only by whitespace containing a newline are one
  constant (docs 4.1.2.1), e.g. `'line one'⏎'line two'`.

Parser (`PostgreSQLParser.g4`):

- `mergestmt`: optional leading `WITH`, `ONLY`/`*` on target and source, any number of WHEN clauses
  in any order (`merge_when_clause`), `WHEN MATCHED … THEN DELETE`, `… THEN DO NOTHING`,
  `WHEN NOT MATCHED BY SOURCE` (update/delete/do nothing) and `BY TARGET`, `INSERT … OVERRIDING
  {SYSTEM|USER} VALUE`, `INSERT DEFAULT VALUES`, `RETURNING`. Rule names `merge_insert_clause`,
  `merge_update_clause`, `merge_delete_clause` kept. `MERGE` added to `preparablestmt` (so `WITH …
  MERGE` and CTE bodies) and `explainablestmt`.
- `createtrigstmt`: `CREATE OR REPLACE [CONSTRAINT] TRIGGER` (PG 14).
- `createfunctionstmt`: SQL-standard bodies `RETURN expr` and `BEGIN ATOMIC … END` (`routine_body_`,
  `routine_body_stmt`).
- SQL/JSON: `json_aggregate_func` (`JSON_ARRAYAGG`, `JSON_OBJECTAGG(k VALUE v)`) wired into `func_expr`
  with optional `RETURNING`; `x IS [NOT] JSON [VALUE|ARRAY|OBJECT|SCALAR] [WITH|WITHOUT UNIQUE [KEYS]]`;
  new `json_table` / `json_table_column_definition` in `table_ref` (also `LATERAL`).
- XML: `xmlelement(name n, xmlattributes(…), content…)`; `xmlroot(x, version …)` (the grammar
  demanded a stray `XML`); XMLTABLE column option `PATH expr`.
- `UNIQUE NULLS [NOT] DISTINCT` in column and table constraints.
- `ALTER TABLE … DETACH PARTITION … CONCURRENTLY|FINALIZE`; `ALTER TABLE … SET ACCESS METHOD`;
  `ALTER COLUMN … SET COMPRESSION`, `SET STORAGE DEFAULT`; column definitions take `STORAGE` /
  `COMPRESSION` (`column_storage_`).
- `GRANT role TO r WITH {ADMIN|INHERIT|SET} {OPTION|TRUE|FALSE}, …` (`grant_role_opt`) and
  `REVOKE {ADMIN|INHERIT|SET} OPTION FOR …` (PG 16).
- `SET SESSION AUTHORIZATION DEFAULT`; `expr AT LOCAL` (PG 17).
- Recursive CTE `SEARCH {DEPTH|BREADTH} FIRST BY … SET col` and `CYCLE … SET col [TO v DEFAULT v]
  USING col` (`search_clause_`, `cycle_clause_`).
- `JOIN … USING (cols) AS alias` (PG 14); `GROUP BY ALL|DISTINCT …` (PG 14).

Not changed: `REFERENCES t ON DELETE CASCADE MATCH FULL` stays an error; PostgreSQL itself only
accepts `MATCH` before `ON DELETE`/`ON UPDATE`.
