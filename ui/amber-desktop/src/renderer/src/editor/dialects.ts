import { MariaSQL, MSSQL, MySQL, PLSQL, PostgreSQL, SQLDialect, SQLite, StandardSQL } from '@codemirror/lang-sql'

const DB2_KEYWORDS =
  'absolute action add after all allocate alter and any are array as asc assertion at authorization before begin between both breadth by call cascade cascaded case cast catalog check close collate collation column commit condition connect connection constraint constraints constructor continue corresponding create cross cube current current_date current_path current_role current_time current_timestamp current_user cursor cycle data day deallocate declare default deferrable deferred delete depth deref desc describe descriptor deterministic diagnostics disconnect distinct do domain drop dynamic each else elseif end equals escape except exception exec execute exists exit external fetch first for foreign found from full function general get global go goto grant group grouping handler having hold hour identity if immediate in indicator initially inner inout input insert intersect interval into is isolation iterate join key language last lateral leading leave left level like limit local locator loop map match merge method minute modifies module month names natural nesting new next no none not null of offset old on only open option or order ordinality out outer output overlaps pad parameter partial path prepare preserve primary prior privileges procedure public read reads recursive redo ref references referencing relative release repeat resignal restrict result return returns revoke right role rollback rollup routine row rows savepoint schema scroll search second section select session session_user set sets signal similar size some space specific specifictype sql sqlexception sqlstate sqlwarning start state static system_user table temporary then time timestamp timezone_hour timezone_minute to trailing transaction translation treat trigger under undo union unique unnest until update usage user using value values view when whenever where while with within without work write year zone global temporary declare session fetch first rows only optimize isolation uncommitted'

const H2_KEYWORDS =
  'all and any array as asymmetric authorization between both case cast check constraint cross current_catalog current_date current_path current_role current_schema current_time current_timestamp current_user day default distinct else end except exists false fetch for foreign from full group groups having hour if ilike in inner intersect interval is join key leading left like limit localtime localtimestamp minus minute month natural not null offset on or order over partition primary qualify range regexp right row rownum rows second select session_user set some symmetric system_user table to top trailing true uescape union unique unknown user using value values when where window with year merge into matched insert update delete create drop alter truncate index view sequence schema temporary memory cached local global linked alias trigger call execute explain grant revoke commit rollback savepoint begin transaction script runscript'

const DB2 = SQLDialect.define({ keywords: DB2_KEYWORDS, operatorChars: '*+-%<>!=&|/~', doubleQuotedStrings: false })
const H2 = SQLDialect.define({ keywords: H2_KEYWORDS, operatorChars: '*+-%<>!=&|/~', doubleQuotedStrings: false })

/** CodeMirror SQL configuration for each engine dialect id. */
export function codemirrorDialect(id: string): SQLDialect {
  switch (id) {
    case 'oracle':
      return PLSQL
    case 'sqlserver':
      return MSSQL
    case 'postgresql':
      return PostgreSQL
    case 'mysql':
      return MySQL
    case 'mariadb':
      return MariaSQL
    case 'sqlite':
      return SQLite
    case 'db2':
      return DB2
    case 'h2':
      return H2
    default:
      return StandardSQL
  }
}
