# Upstream grammar

| | |
|---|---|
| Source | https://github.com/antlr/grammars-v4/tree/9fa9f94b20cd3225bd84f27554515da207ec39ff/sql/tsql |
| Commit | `9fa9f94b20cd3225bd84f27554515da207ec39ff` |
| License | MIT (see the header of each `.g4` file) |
| Imported | 2026-11-07 |

Files taken from upstream:

- `sql/tsql/TSqlLexer.g4`
- `sql/tsql/TSqlParser.g4`

Local changes: the Java support classes got a `package` declaration. Every further change to
the grammar must add a reproducing case to `amber-corpus`.

## Local coverage changes (2026-11-07)

Found by a conformance run over statements taken from the SQL Server documentation. Every change is
marked `// Amber` in the grammar. New lexer tokens (`ATOMIC`, `CONTAINED`, `CUBE`, `IGNORE`, `MATCH`,
`NULLS`, `PERIOD`, `RESPECT`, `ROLLUP`, `SPATIAL`, `SYSTEM_TIME`, `WINDOW`) are also added to `keyword`,
so they stay usable as identifiers.

- `tsql_file` / `batch`: a procedure call without `EXEC` (`execute_body_batch`) may only start a batch
  (start of the input or right after `GO`). Upstream let a new `batch` begin anywhere, so unsupported
  syntax inside a batch was silently parsed as a call (`CREATE USER u WITHOUT LOGIN` became
  `CREATE USER u` plus a call of a procedure named `WITHOUT`). `GO` is now a child of `tsql_file`;
  statements stay direct children of `batch`.
- `security_statement`: `GRANT` takes a list of permissions (`grant_permission_list`) and a column list
  after the securable (`grant_on_clause`); new `DENY …` and `REVOKE [GRANT OPTION FOR] … {TO | FROM} …
  [CASCADE]` alternatives.
- `predicate`: `UPDATE(col)` (DML triggers), `IS [NOT] DISTINCT FROM`, graph `MATCH(a-(e)->b)`
  (`graph_match_pattern`).
- `alter_table`: `DROP COLUMN IF EXISTS`, `DROP CONSTRAINT IF EXISTS` (and lists), `DROP PERIOD FOR
  SYSTEM_TIME`, `[WITH CHECK|NOCHECK] {CHECK|NOCHECK} CONSTRAINT {ALL | c1, c2}`, `SET (name = value …)`
  for `SYSTEM_VERSIONING`, `DATA_DELETION` etc.
- New `generic_option` / `generic_option_value`: `name [=] value` items of option lists the grammar does
  not model one by one (nested lists, `6 MONTHS`, `PRIMARY`, `(1, 2, 3)`); used as the last alternative of
  `table_option`, `create_table_index_option`, `alter_table_index_option` (e.g. `BUCKET_COUNT`),
  `database_optionspec` (`QUERY_STORE = ON (…)`), restore and bulk insert options and the new DDL below.
- Temporal tables: `PERIOD FOR SYSTEM_TIME (a, b)` in `column_def_table_constraint`, `table_source_item`
  takes `for_system_time_clause` (`AS OF`, `FROM … TO`, `BETWEEN … AND`, `CONTAINED IN`, `ALL`).
- `table_source_item`: `tablesample_clause`; `rowset_function`: `OPENQUERY(srv, 'q')`,
  `OPENDATASOURCE('p', 'c').db.schema.object`.
- `create_table`: `AS NODE | EDGE` (graph tables), `FILESTREAM_ON`; `table_indices`: `INCLUDE`, filter
  `WHERE`, index options and filegroup on inline indexes; `clustered`: `NONCLUSTERED HASH`;
  `column_definition_element`: `COLUMN_ENCRYPTION_KEY = name` (identifier, not only a string).
- `query_expression`: `(…) EXCEPT | INTERSECT | UNION …` after a parenthesized query (upstream accepted
  only `UNION [ALL]`).
- `query_specification`: `GROUP BY … WITH ROLLUP | CUBE`, `WINDOW w AS (…)` (`window_clause`);
  `over_clause`: `OVER w`; `analytic_windowed_function`: `IGNORE | RESPECT NULLS` (`null_treatment`);
  `function_call#SCALAR_FUNCTION`: optional `WITHIN GROUP (ORDER BY …)` (e.g. `APPROX_PERCENTILE_CONT`).
- `expression`: `@var.Property` / `@var.Method(…)` (CLR types such as geography), ODBC escapes
  `{d '…'}`, `{ts '…'}`, `{fn f(…)}` (new `LCURLY`/`RCURLY` tokens); `function_call#UDT_STATIC_METHOD`:
  `GEOGRAPHY::Point(…)`.
- `freetext_predicate`: the documented `FREETEXT(column | (columns) | *, 'text' [, LANGUAGE l])`.
- `id_`: new lexer token `SQLCMD_VARIABLE` for sqlcmd variables used bare in names: `$(Db)`,
  `stg_$(Env)`, so `FROM $(Src).dbo.t` parses.
- `execute_body`: `WITH RECOMPILE | RESULT SETS {UNDEFINED | NONE | (…)}` (`execute_option_clause`);
  `execute_var_string`: concatenations of strings, variables and function calls (`EXEC (N'a' + N'b')`).
- `raiseerror_statement`: several `WITH` options (`WITH NOWAIT, LOG`).
- `dbcc_clause`: `dbcc_generic` for any other DBCC command (`CHECKIDENT ('t', RESEED, 0)`, `SQLPERF(…)`).
- New statements: `restore_statement` (`RESTORE DATABASE | LOG | FILELISTONLY …`, in `backup_statement`),
  `bulk_insert_statement` (in `another_statement`), `alter_database_scoped_configuration`,
  `create_fulltext_index`, `create_spatial_index`, `create_external_data_source` (in `ddl_clause`).
- `create_login_sql_server`: `WITH PASSWORD = … , <options in any order>` (`create_login_option`).
- `alter_partition_function`: `SPLIT | MERGE RANGE (expression)` (upstream: integers only).
- `function_option`: `INLINE = ON | OFF`, `NATIVE_COMPILATION`; `procedure_option`: `NATIVE_COMPILATION`,
  `SCHEMABINDING`; `block_statement`: `BEGIN ATOMIC WITH (…)` (`atomic_block_options`).
