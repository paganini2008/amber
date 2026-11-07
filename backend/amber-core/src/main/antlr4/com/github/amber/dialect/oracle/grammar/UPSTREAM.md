# Upstream grammar

| | |
|---|---|
| Source | https://github.com/antlr/grammars-v4/tree/9fa9f94b20cd3225bd84f27554515da207ec39ff/sql/plsql |
| Commit | `9fa9f94b20cd3225bd84f27554515da207ec39ff` |
| License | Apache-2.0 (see the header of each `.g4` file) |
| Imported | 2026-11-07 |

Files taken from upstream:

- `sql/plsql/PlSqlLexer.g4`
- `sql/plsql/PlSqlParser.g4`
- `sql/plsql/Java/PlSqlLexerBase.java`
- `sql/plsql/Java/PlSqlParserBase.java`

Local changes: the Java support classes got a `package` declaration. Every further change to
the grammar must add a reproducing case to `amber-corpus`.

## Local coverage changes

Gaps found by a conformance run against SQL taken from the Oracle documentation. Every change is
marked `// Amber:` in the grammar where it is not obvious.

Parser (`PlSqlParser.g4`) and `PlSqlParserBase.java`:

- `set_command` (SQL*Plus `SET`) is guarded by `isSqlPlusSet()`, so `SET TRANSACTION …`,
  `SET CONSTRAINT[S] …` and `SET ROLE …` parse as SQL statements instead of a SQL*Plus `SET`
  followed by a bogus routine call (`CALL ONLY`, `CALL IMMEDIATE`).
- New `set_role_command` (`SET ROLE r [IDENTIFIED BY p], … | ALL [EXCEPT r, …] | NONE`) in `sql_unit`.
- New `partition_bound_value` used by range/list partition bounds, index partition bounds and
  `SPLIT PARTITION … AT (…)`: accepts `DATE '…'` / `TIMESTAMP '…'` literals, `NULL`, and mixes them
  with plain literals.
- `role_name` accepts `RESOURCE` (`GRANT CONNECT, RESOURCE TO u`); `revoke_system_privilege`
  takes a list (`REVOKE CONNECT, RESOURCE FROM u`).
- Set operators: `UNION | INTERSECT | MINUS | EXCEPT` each with optional `ALL` (21c).
  `isNotStartOfJoin()` keeps `EXCEPT [ALL] SELECT …` from being read as a table alias.
- 23ai `IF [NOT] EXISTS` before the object name (upstream had it after the name; both accepted) for
  `CREATE/DROP TABLE`, `CREATE/DROP VIEW`, `CREATE/DROP INDEX`, `CREATE/DROP USER`,
  `DROP/ALTER SEQUENCE`, `CREATE/DROP SYNONYM`.
- `CREATE … NOFORCE VIEW`; `DROP VIEW … CASCADE CONSTRAINTS`; `DROP INDEX … [ONLINE] [FORCE]
  [{DEFERRED|IMMEDIATE} INVALIDATION]`; `ALTER TABLE … DROP PRIMARY KEY … KEEP INDEX` (upstream had
  `KEY INDEX`, kept).
- `CREATE [OR REPLACE] {EDITIONABLE|NONEDITIONABLE} [PUBLIC] SYNONYM`.
- `ALTER SEQUENCE … RESTART [START WITH n]` (`RESTART` in `sequence_spec`).
- New `alter_system` / `alter_system_set_clause` (compact): `SET|RESET param = value … [SCOPE =
  MEMORY|SPFILE|BOTH] [SID = …] [COMMENT = …] [DEFERRED] [CONTAINER = …]`, `KILL|DISCONNECT SESSION
  '…' [IMMEDIATE|NOREPLAY|FORCE|POST_TRANSACTION]`, `FLUSH …`, `CHECKPOINT`, `SWITCH LOGFILE`,
  `ARCHIVE LOG …`, `ENABLE|DISABLE RESTRICTED SESSION`, `QUIESCE RESTRICTED`, `UNQUIESCE`,
  `SUSPEND`, `RESUME`, `REGISTER`. `parameter_value` also takes numbers.
- `alter_user`: the proxy alternative was missing `ALTER USER` (`ALTER USER u GRANT CONNECT THROUGH p`).
- `identified_other_clause`: `NO AUTHENTICATION` (18c, `CREATE USER u NO AUTHENTICATION`).
- `modify_table_partition` takes partition attributes: `COMPRESS …`, `ROW STORE COMPRESS …`,
  `NOCOMPRESS`, logging and physical attributes, `READ ONLY|WRITE`.
- `create_index`: `CREATE SEARCH INDEX … FOR JSON [PARAMETERS ('…')]`; `bitmap_join_index_clause`
  table aliases in `FROM` are optional.
- Window functions: `NTH_VALUE(…) [FROM FIRST|LAST] [RESPECT|IGNORE NULLS] OVER (…)`; `GROUPS`
  frames and frame `EXCLUDE {CURRENT ROW|GROUP|TIES|NO OTHERS}`; 21c `WINDOW w AS (…)` clause in
  `query_block` (`window_clause_`) and `OVER w` / `OVER (w …)` (`window_name_`).
- New `match_recognize_clause` (+ `mr_*` rules) after a table reference: `PARTITION BY`, `ORDER BY`,
  `MEASURES`, `ONE ROW|ALL ROWS PER MATCH`, `AFTER MATCH SKIP …`, `PATTERN` (alternation, grouping,
  `PERMUTE`, `{- -}` exclusion, `^`, quantifiers `* + ? {n,m}` with reluctant `?`), `SUBSET`, `DEFINE`.
  (`?` lexes as `BINDVAR`.)
- 12.2+ table function call without `TABLE()` in `FROM` (`table_function_call_`, labelled alternative
  `table_ref_aux_internal_func`): `FROM f('a')`, `FROM pkg.f(1)`.
- 23ai table value constructor `(VALUES (…), (…)) v (c1, c2)` in `FROM` and as a CTE body
  (`table_values_constructor_`); a table alias may be followed by a column list.
- 23ai `UPDATE … SET … FROM …` and `DELETE [FROM] t FROM …` (optional `from_clause`).
- `IS [NOT] TRUE|FALSE` (`logical_operation`).
- JSON: `JSON_TABLE` columns take `DEFAULT expr ON EMPTY|ERROR` and the ON ERROR / ON EMPTY clauses
  in any order; JSON simple dot notation with array steps (`d.doc.items[0].sku`, `[*]`, `[1, 3 TO 5]`,
  `json_array_step_`, only after a `.` step so MODEL cell references are unchanged).
- `CREATE PACKAGE … ACCESSIBLE BY (…)` / `DEFAULT COLLATION …` in any order with `AUTHID`.
- PL/SQL 21c stepped loop `FOR i IN 1 .. 10 BY 2`.
- `datatype`: 23ai `VECTOR [(dims|*, format|* [, DENSE|SPARSE])]`.

Lexer (`PlSqlLexer.g4`): new tokens `VECTOR` and `WINDOW`, both added to
`non_reserved_keywords_in_12c` so they remain usable as identifiers.

`WITH FUNCTION/PROCEDURE … SELECT …` parses as a whole; `OracleSplitter` keeps it as one unit ending
at `/` (corpus: `conformance/06_*`).

Parser: new rule `non_reserved_keywords_amber` in `regular_id` with 92 lexer keywords (BIG, FIXED,
LATEST, THESE, …) that upstream left out of every non-reserved list although Oracle does not reserve
them (corpus: `conformance/30_keyword_identifiers.sql`).
