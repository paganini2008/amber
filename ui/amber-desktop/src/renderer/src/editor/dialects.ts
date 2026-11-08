import { MariaSQL, MSSQL, MySQL, PLSQL, PostgreSQL, SQLDialect, SQLite, StandardSQL, type SQLDialectSpec } from '@codemirror/lang-sql'

/**
 * Editor highlighting per engine dialect. The keyword lists start from @codemirror/lang-sql, add
 * the statements and clauses legacy scripts use (MERGE … WHEN [NOT] MATCHED, CONNECT BY, PIVOT,
 * OUTPUT, RETURNING, FETCH/OFFSET, window clauses, PL/SQL and T-SQL control flow, …) and drop
 * built-in function names: COUNT, SUM or TRUNC are functions, not keywords, in every dialect.
 */

/** Function names that some upstream keyword lists include; they are never keywords here. */
const FUNCTIONS = new Set(
  (
    'count sum avg min max abs ceil ceiling floor exp ln log log10 mod power sqrt coalesce nullif greatest least ' +
    'lower upper trim ltrim rtrim substring substr length char_length character_length octet_length bit_length position ' +
    'overlay translate convert normalize cardinality width_bucket trunc nvl decode ' +
    'rank dense_rank row_number ntile lag lead first_value last_value nth_value cume_dist percent_rank percentile_cont ' +
    'percentile_disc array_agg string_agg std stddev stddev_pop stddev_samp variance var_pop var_samp dev_pop dev_samp ' +
    'corr covar_pop covar_samp regr_avgx regr_avgy regr_count regr_intercept regr_r2 regr_slope regr_sxx regr_sxy regr_syy ' +
    'xmlagg xmlattributes xmlbinary xmlcast xmlcomment xmlconcat xmlelement xmlexists xmlforest xmliterate xmlparse xmlpi ' +
    'xmlquery xmlroot xmlserialize xmltext xmlvalidate xmldocument found_rows utc_date utc_time utc_timestamp'
  ).split(' ')
)

/** Clauses most dialects share: MERGE, window functions' OVER clause, row limiting, CTEs. */
const COMMON = 'merge using matched when then over partition range rows unbounded preceding following current row fetch first next only offset recursive nulls last'

const ORACLE =
  `${COMMON} pivot unpivot connect start prior nocycle siblings connect_by_root level ties percent window within materialized ` +
  'returning bulk collect forall save exceptions others pragma autonomous_transaction rowtype record type elsif loop exit continue ' +
  'declare begin end exception raise cursor open close immediate execute lateral minus'

const SQLSERVER =
  `${COMMON} top output ties percent pivot unpivot apply window try catch throw raiserror waitfor goto tran transaction nocount ` +
  'elseif begin end declare cursor open close deallocate exec execute return returns go'

const POSTGRESQL = `${COMMON} window filter within ties lateral returning conflict do nothing materialized only elsif loop exit continue exception raise perform foreach declare begin end`

const MYSQL = `${COMMON.replace(/\bmerge\b|\bmatched\b/g, '')} window lateral duplicate elseif loop leave iterate repeat until handler continue exit signal resignal cursor declare begin end`

const MARIADB = `${MYSQL} returning`

const SQLITE = `${COMMON.replace(/\bmerge\b|\bmatched\b/g, '')} window filter returning conflict do nothing materialized`

const DB2_KEYWORDS =
  'absolute action add after all allocate alter and any are array as asc assertion at authorization before begin between both breadth by call cascade cascaded case cast catalog check close collate collation column commit condition connect connection constraint constraints constructor continue corresponding create cross cube current current_date current_path current_role current_time current_timestamp current_user cursor cycle data day deallocate declare default deferrable deferred delete depth deref desc describe descriptor deterministic diagnostics disconnect distinct do domain drop dynamic each else elseif end equals escape except exception exec execute exists exit external fetch first for foreign found from full function general get global go goto grant group grouping handler having hold hour identity if immediate in indicator initially inner inout input insert intersect interval into is isolation iterate join key language last lateral leading leave left level like limit local locator loop map match merge method minute modifies module month names natural nesting new next no none not null of offset old on only open option or order ordinality out outer output overlaps pad parameter partial path prepare preserve primary prior privileges procedure public read reads recursive redo ref references referencing relative release repeat resignal restrict result return returns revoke right role rollback rollup routine row rows savepoint schema scroll search second section select session session_user set sets signal similar size some space specific specifictype sql sqlexception sqlstate sqlwarning start state static system_user table temporary then time timestamp timezone_hour timezone_minute to trailing transaction translation treat trigger under undo union unique unnest until update usage user using value values view when whenever where while with within without work write year zone global temporary declare session fetch first rows only optimize isolation uncommitted'

const DB2 = `${COMMON} truncate nocycle final atomic`

const H2_KEYWORDS =
  'all and any array as asymmetric authorization between both case cast check constraint cross current_catalog current_date current_path current_role current_schema current_time current_timestamp current_user day default distinct else end except exists false fetch for foreign from full group groups having hour if ilike in inner intersect interval is join key leading left like limit localtime localtimestamp minus minute month natural not null offset on or order over partition primary qualify range regexp right row rownum rows second select session_user set some symmetric system_user table to top trailing true uescape union unique unknown user using value values when where window with year merge into matched insert update delete create drop alter truncate index view sequence schema temporary memory cached local global linked alias trigger call execute explain grant revoke commit rollback savepoint begin transaction script runscript'

const H2 = `${COMMON} then by asc desc outer add column rename references cascade restrict escape domain ties percent only`

const words = (text: string | undefined): string[] => (text ?? '').split(/\s+/).filter(Boolean)

/** The keyword, type and builtin lists for one dialect (exported for tests). */
export interface DialectWords {
  keywords: Set<string>
  types: Set<string>
  builtin: Set<string>
}

function wordsFor(base: SQLDialectSpec, baseKeywords: string | undefined, extra: string): DialectWords {
  const keywords = new Set([...words(baseKeywords), ...words(extra)].map((w) => w.toLowerCase()).filter((w) => !FUNCTIONS.has(w)))
  // Upstream builtin lists may contain clause words (SQL Server's "next value for"); keywords win.
  const builtin = new Set(words(base.builtin).filter((w) => !keywords.has(w)))
  return { keywords, types: new Set(words(base.types)), builtin }
}

const SPECS: Record<string, { base: SQLDialectSpec; keywords: string | undefined; extra: string }> = {
  oracle: { base: PLSQL.spec, keywords: PLSQL.spec.keywords, extra: ORACLE },
  sqlserver: { base: MSSQL.spec, keywords: MSSQL.spec.keywords, extra: SQLSERVER },
  postgresql: { base: PostgreSQL.spec, keywords: PostgreSQL.spec.keywords, extra: POSTGRESQL },
  mysql: { base: MySQL.spec, keywords: MySQL.spec.keywords, extra: MYSQL },
  mariadb: { base: MariaSQL.spec, keywords: MariaSQL.spec.keywords, extra: MARIADB },
  sqlite: { base: SQLite.spec, keywords: SQLite.spec.keywords, extra: SQLITE },
  db2: { base: { operatorChars: '*+-%<>!=&|/~', doubleQuotedStrings: false }, keywords: DB2_KEYWORDS, extra: DB2 },
  h2: { base: { operatorChars: '*+-%<>!=&|/~', doubleQuotedStrings: false }, keywords: H2_KEYWORDS, extra: H2 }
}

export function dialectWords(id: string): DialectWords | null {
  const s = SPECS[id]
  return s ? wordsFor(s.base, s.keywords, s.extra) : null
}

const cache = new Map<string, SQLDialect>()

/** CodeMirror SQL configuration for each engine dialect id. */
export function codemirrorDialect(id: string): SQLDialect {
  const s = SPECS[id]
  if (!s) return StandardSQL
  let d = cache.get(id)
  if (!d) {
    const w = wordsFor(s.base, s.keywords, s.extra)
    d = SQLDialect.define({
      ...s.base,
      keywords: [...w.keywords].join(' '),
      types: [...w.types].join(' '),
      builtin: [...w.builtin].join(' ')
    })
    cache.set(id, d)
  }
  return d
}
