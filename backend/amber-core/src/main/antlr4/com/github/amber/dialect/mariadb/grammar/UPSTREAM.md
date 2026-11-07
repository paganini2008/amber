# Upstream grammar

| | |
|---|---|
| Source | https://github.com/antlr/grammars-v4/tree/9fa9f94b20cd3225bd84f27554515da207ec39ff/sql/mariadb |
| Commit | `9fa9f94b20cd3225bd84f27554515da207ec39ff` |
| License | MIT (see the header of each `.g4` file) |
| Imported | 2026-11-07 |

Files taken from upstream:

- `sql/mariadb/MariaDBLexer.g4`
- `sql/mariadb/MariaDBParser.g4`

Local changes: the Java support classes got a `package` declaration. Every further change to
the grammar must add a reproducing case to `amber-corpus`.

## Local performance changes (2026-11-07)

Profiled with ANTLR's decision profiler; parse trees of the whole corpus are byte-identical before
and after. Warm parse time of the corpus fell from 9.2 s to 0.10 s.

1. `sqlStatements`: left-factored so choosing "last statement" only looks at the run of `;`.
2. `querySpecification`: the two alternatives that differed only in where `INTO` goes are merged.
3. `selectElement`: `functionCall` is tried before `fullColumnName`, so `count(*)` no longer
   forces the SLL pass to fail.

## Local coverage changes

- New rule `withStatement : withClause dmlStatement`, used in `sqlStatement` (so `WITH … SELECT/INSERT/
  UPDATE/DELETE` works at top level, MariaDB 10.2+; upstream accepted CTEs only inside compound
  statements) and in `compoundStatement` in place of the inline `withClause dmlStatement`.
- `createTable#queryCreateTable` and `insertStatementValue`: allow `withClause?` before the query, for
  `CREATE TABLE … AS WITH … SELECT` and `INSERT INTO … WITH … SELECT`.

### Conformance gaps closed (2026-11-07)

Found by a conformance run of MariaDB-documented syntax. The SLL/LL split of the corpus is unchanged
(same 23 units need full LL before and after).

Lexer:

- New tokens `INTERSECT`, `ATOMIC`, `IMMEDIATE`, `PORTION`, `EXAMINED`, `TIES`, `INET4`, `INET6`, and for
  sql_mode=ORACLE `ELSIF`, `RAISE`, `EXCEPTION`, `VARCHAR2`, `NVARCHAR2`, `CLOB`, `RAW`; all except
  `INTERSECT` are added to `keywordsCanBeId`. `DOUBLE_DOT : '..'` for `FOR i IN 1..10`.
- `EXCEPT` removed from `keywordsCanBeId` (reserved in MariaDB); otherwise `FROM t1 EXCEPT SELECT …` was
  read as an alias plus a second statement.

Queries and DML:

- `setOperator : UNION | INTERSECT | EXCEPT`, used in `unionStatement`, `unionParenthesis` and the
  trailing set operation of `selectStatement` (`INTERSECT [ALL]`, `EXCEPT [ALL]`). Oracle-mode `MINUS`
  is not supported (it still parses as an alias followed by a second SELECT).
- `limitClause`: `ROWS EXAMINED n`, `OFFSET n ROWS`, `FETCH FIRST|NEXT n ROWS ONLY|WITH TIES`.
- `withClause`: `CYCLE cols RESTRICT`.
- `valuesStatement`: `ORDER BY` / `LIMIT`; `tableSourceItem#valuesTableItem`: `(VALUES …) AS v [(cols)]`.
- `tableSourceItem#atomTableItem`: `FOR SYSTEM_TIME` (new rule `systemTimeSpec`: `AS OF`, `BETWEEN … AND`,
  `FROM … TO`, `ALL`); index hints may be separated by blanks.
- `jsonTable`: the document is an expression (was a string literal only).
- `functionCall#udfFunctionCall`: optional `WITHIN GROUP (ORDER BY …)` and `OVER (…)`
  (`PERCENTILE_CONT`, `PERCENTILE_DISC`, `MEDIAN`).
- `GROUP_CONCAT(… LIMIT n [OFFSET m])`.
- `expressionAtom`: `NEXT VALUE FOR s` / `PREVIOUS VALUE FOR s` (`sequenceValueExpressionAtom`),
  `:name.col` (`bindVariableExpressionAtom`, sql_mode=ORACLE triggers), `SQL%ROWCOUNT`
  (`cursorAttributeExpressionAtom`).
- `convertedDataType`: `DOUBLE [PRECISION]`, `FLOAT`, `REAL`, `INET4`, `INET6`, `UUID`, `VARCHAR(n)` /
  `VARCHAR2(n)`, `DATETIME(n)` / `TIME(n)` / `TIMESTAMP(n)`.
- New rule `returningClause : RETURNING selectElements` after `insertStatement`, `replaceStatement` and
  `singleDeleteStatement`.
- `singleDeleteStatement`: table alias, `FOR PORTION OF p FROM … TO …`, and a second alternative
  `DELETE HISTORY FROM t [BEFORE SYSTEM_TIME …]`. `singleUpdateStatement`: `FOR PORTION OF`.

DDL and administration:

- `dataType`: `INET4`, `INET6`; sql_mode=ORACLE `VARCHAR2`, `NVARCHAR2`, `RAW`, `NUMBER[(p[,s])]`, `CLOB`.
- `columnConstraint#versioningColumnConstraint`: `WITH|WITHOUT SYSTEM VERSIONING`.
- `alterSpecification`: `ADD|DROP SYSTEM VERSIONING`, `ADD PERIOD FOR …`, `DROP PERIOD [IF EXISTS] FOR …`;
  `alterPartitionSpecification`: `CONVERT PARTITION p TO TABLE t`, `CONVERT TABLE t TO PARTITION …`.
- `indexColumnNames`: trailing `WITHOUT OVERLAPS`.
- `createDatabaseOption`: `COMMENT`.
- `sequenceSpec`: `AS <int type>`, `RESTART` without a value, negative numbers.
- `createTrigger`, `createView`, `createProcedure`: `IF NOT EXISTS`. `createUser`: `OR REPLACE`.
  `createRole`: `OR REPLACE`, `WITH ADMIN …`. `setRole`: `SET DEFAULT ROLE r [FOR user]`.
- `analyzeTable`: `PERSISTENT FOR ALL | COLUMNS (…) INDEXES (…)`. `fullDescribeStatement`: `ANALYZE
  [FORMAT=JSON] <statement>`, `EXPLAIN EXTENDED|PARTITIONS` without `= …`; it is now tried before
  `simpleDescribeStatement` in `utilityStatement` (else `EXPLAIN EXTENDED SELECT …` was read as
  `EXPLAIN <table EXTENDED>` plus a second statement). `showStatement#showExplain`: `SHOW ANALYZE FOR n`.
  `killStatement`: `KILL QUERY ID n`.

Stored programs and compound statements:

- `sqlStatement`: `blockStatement`, `ifStatement`, `caseStatement`, `repeatStatement`, `whileStatement`
  and `oracleAnonymousBlock`, so compound statements run outside stored programs
  (`BEGIN NOT ATOMIC … END`). They were removed from `compoundStatement` (bodies reach them through
  `procedureSqlStatement -> sqlStatement`) and `routineBody` is now just `sqlStatement`, so no body
  statement is reachable two ways. `loopStatement` stays in `compoundStatement` only: at top level a
  FOR loop made `SELECT NEXT VALUE FOR s` ambiguous for SLL (`SELECT NEXT VALUE` + `FOR s …`).
- Labels of `blockStatement`, `loopStatement`, `repeatStatement`, `whileStatement` are `ID` tokens (were
  `uid`), and `<<label>>` is accepted. A keyword-like label let SLL read the trailing keywords of any
  statement as a labelled block (e.g. `CREATE USER … PASSWORD EXPIRE` fell back to LL).
- `blockStatement`: `BEGIN NOT ATOMIC`; optional `oracleExceptionSection` (`EXCEPTION WHEN … THEN …`).
- `loopStatement`: FOR loops over `a..b` (with `REVERSE`), `(SELECT …)` and cursors, with `DO … END FOR`
  or `LOOP … END LOOP`. `whileStatement`: `WHILE … LOOP … END LOOP`.
- `declareCursor`: parameters; `cursorStatement#OpenCursor`: arguments. `elifAlternative`: `ELSIF`.
- `executeStatement`: `USING` takes expressions; `EXECUTE IMMEDIATE expr [USING …]`.
- `compoundStatement` gained `oracleStatement`: `x := e` / `:NEW.c := e`, `NULL`, `RAISE [e]`,
  `EXIT [label] [WHEN cond]`, and procedure calls without CALL (`pkg.proc(…)`). Names are ID tokens so
  no keyword statement is shadowed.
- sql_mode=ORACLE routines: `createProcedure` / `createFunction` take an optional parameter list,
  `RETURN` as well as `RETURNS`, `IF NOT EXISTS`, and `AS|IS oracleDeclaration* blockStatement`.
  `procedureParameter` / `functionParameter` accept `name [IN|OUT|IN OUT|INOUT] type [DEFAULT|:= e]`.
  New rules `oracleType` (a data type or `x%TYPE` / `x%ROWTYPE`), `oracleDeclaration` (variables,
  exceptions, cursors, nested procedures/functions), `createPackage` (`CREATE [OR REPLACE] PACKAGE [BODY]`)
  in `ddlStatement`, `dropPackage` in `ddlStatement`, `oracleAnonymousBlock` (`DECLARE … BEGIN … END`).
