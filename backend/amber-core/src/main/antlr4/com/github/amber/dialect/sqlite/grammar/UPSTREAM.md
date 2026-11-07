# Upstream grammar

| | |
|---|---|
| Source | https://github.com/antlr/grammars-v4/tree/9fa9f94b20cd3225bd84f27554515da207ec39ff/sql/sqlite |
| Commit | `9fa9f94b20cd3225bd84f27554515da207ec39ff` |
| License | MIT (see the header of each `.g4` file) |
| Imported | 2026-11-07 |

Files taken from upstream:

- `sql/sqlite/SQLiteLexer.g4`
- `sql/sqlite/SQLiteParser.g4`

Local changes: the Java support classes got a `package` declaration. Every further change to
the grammar must add a reproducing case to `amber-corpus`.

## Local coverage changes

- `pragma_value`: also accepts the keywords `ON`, `DELETE` and `DEFAULT`
  (`PRAGMA foreign_keys = ON`, `PRAGMA journal_mode = DELETE`, `PRAGMA secure_delete = DEFAULT`).
  SQLite accepts any token as a pragma value; other keywords used there (`FULL`, `NO`, …) were already
  identifiers through `fallback`.
- `fallback_excluding_conflicts`: added `FILTER_`, `OVER_` and `WINDOW_`, which SQLite treats as fallback
  identifiers (columns or aliases named `filter`, `over`, `window`). The FILTER/OVER/WINDOW clauses still
  parse as before.
