# Upstream grammar

| | |
|---|---|
| Source | https://github.com/antlr/grammars-v4/tree/9fa9f94b20cd3225bd84f27554515da207ec39ff/sql/mysql/Oracle |
| Commit | `9fa9f94b20cd3225bd84f27554515da207ec39ff` |
| License | BSD-3-Clause (see the header of each `.g4` file) |
| Imported | 2026-11-07 |

Files taken from upstream:

- `sql/mysql/Oracle/MySQLLexer.g4`
- `sql/mysql/Oracle/MySQLParser.g4`
- `sql/mysql/Oracle/Java/MySQLLexerBase.java`
- `sql/mysql/Oracle/Java/MySQLParserBase.java`
- `sql/mysql/Oracle/Java/SqlMode.java`
- `sql/mysql/Oracle/Java/SqlModes.java`

Local changes: the Java support classes got a `package` declaration. Every further change to
the grammar must add a reproducing case to `amber-corpus`.

## Local coverage changes

- `query`: the statement before `;` is optional, so empty statements parse (`SELECT 1;;`, and the bare
  `;` left after a mysqldump `/*!40101 … */;` versioned comment once the splitter has dropped it).
- `transactionStatement`: `START TRANSACTION` options are comma-separated
  (`WITH CONSISTENT SNAPSHOT, READ WRITE`); upstream accepted them only juxtaposed.
- `jsonOnResponse`: `DEFAULT` takes any literal (`JSON_VALUE(j, '$.a' RETURNING UNSIGNED DEFAULT 0 ON EMPTY)`);
  upstream accepted only a string.
- `simpleExpr#simpleExprCastTime`: the precision after `AS DATETIME` is optional
  (`CAST(NOW() AT TIME ZONE '+00:00' AS DATETIME)`).
- `showCreateProcedureStatusStatement`, `showCreateFunctionStatusStatement`,
  `showCreateProcedureCodeStatement`, `showCreateFunctionCodeStatement`: upstream required a stray
  `CREATE` (`SHOW CREATE PROCEDURE STATUS`); they now match the real `SHOW PROCEDURE|FUNCTION STATUS|CODE`.
  Rule names are unchanged.
- Lexer `INTERSECT_SYMBOL`: upstream matched the literal text `INTERSECT_SYMBOL`, so `INTERSECT [ALL]`
  never worked. Now matches `INTERSECT` (still only for server version >= 8.0.31).
- Lexer `GET_MASTER_PUBLIC_KEY_SYMBOL`: upstream matched `GET_MASTER_PUBLIC_KEY_SYM`; now
  `GET_MASTER_PUBLIC_KEY`.
- Lexer `MASTER_SYMBOL` and the `MASTER_*` replication option keywords: removed the
  `isServerVersionLt80024()` predicate. With the default server version 8.2 they were plain identifiers,
  breaking `ALTER INSTANCE ROTATE INNODB MASTER KEY`, `RESET MASTER`, `SHOW MASTER STATUS` and
  `CHANGE MASTER TO MASTER_HOST = …` (all still valid in 8.x). They are in the identifier keyword lists,
  so columns named `master` or `master_host` still parse.
- `MySQLLexerBase.charSets`: filled with MySQL's character set names (`_utf8mb4`, `_latin1`, `_binary`, …;
  compared case-insensitively), so charset introducers (`_utf8mb4'abc' COLLATE utf8mb4_bin`) lex as
  `UNDERSCORE_CHARSET`; upstream left the set empty, which made every introducer an identifier.
