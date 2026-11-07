import type { AnalysisResult, Occurrence, QueryBlock, ReferenceQuery, ReferencesResult, Statement } from '@shared/protocol'

/**
 * Find references / go to definition: what the name under the cursor refers to, and how the
 * engine's occurrences are grouped and shown. The engine does the matching (`references`).
 */

export interface Token {
  /** The whole dotted name, quotes and brackets removed, e.g. "c.tier". */
  text: string
  parts: string[]
  /** Which part the offset is in. */
  part: number
  from: number
  to: number
}

const WORD = /[\p{L}\p{N}_$#@]/u

function unquote(part: string): string {
  const p = part.trim()
  if ((p.startsWith('"') && p.endsWith('"')) || (p.startsWith('`') && p.endsWith('`')) || (p.startsWith('[') && p.endsWith(']'))) return p.slice(1, -1)
  return p
}

const OPEN: Record<string, string> = { '"': '"', '`': '`', '[': ']' }
const CLOSE: Record<string, string> = { '"': '"', '`': '`', ']': '[' }

/** The (possibly dotted, possibly quoted) identifier at an offset, or null on whitespace or punctuation. */
export function tokenAt(text: string, offset: number): Token | null {
  const lineStart = text.lastIndexOf('\n', offset - 1) + 1
  const nl = text.indexOf('\n', offset)
  const lineEnd = nl < 0 ? text.length : nl
  // Inside a quoted name? Count quote characters before the offset on this line.
  let from = offset
  let to = offset
  for (const q of ['"', '`']) {
    const before = text.slice(lineStart, offset).split(q).length - 1
    if (before % 2 === 1) {
      from = text.lastIndexOf(q, offset - 1)
      const close = text.indexOf(q, offset)
      to = close < 0 || close > lineEnd ? lineEnd : close + 1
    }
  }
  const open = text.lastIndexOf('[', offset - 1)
  if (from === offset && open >= lineStart && text.indexOf(']', open) >= offset && !text.slice(open, offset).includes(']')) {
    from = open
    to = text.indexOf(']', open) + 1
  }
  const isWord = (c: string | undefined): boolean => c !== undefined && WORD.test(c)
  if (from === offset && to === offset) {
    if (!isWord(text[offset]) && isWord(text[offset - 1])) from = offset - 1
    if (!isWord(text[from])) return null
    to = from + 1
  }
  // Grow left and right over word characters, dots and whole quoted parts.
  for (;;) {
    const c = text[from - 1]
    if (from > lineStart && (isWord(c) || c === '.')) from--
    else if (from > lineStart && c !== undefined && CLOSE[c]) {
      const o = text.lastIndexOf(CLOSE[c], from - 2)
      if (o < lineStart) break
      from = o
    } else break
  }
  for (;;) {
    const c = text[to]
    if (to < lineEnd && (isWord(c) || c === '.')) to++
    else if (to < lineEnd && c !== undefined && OPEN[c]) {
      const e = text.indexOf(OPEN[c], to + 1)
      if (e < 0 || e >= lineEnd) break
      to = e + 1
    } else break
  }
  let raw = text.slice(from, to)
  while (raw.startsWith('.')) {
    raw = raw.slice(1)
    from++
  }
  while (raw.endsWith('.')) {
    raw = raw.slice(0, -1)
    to--
  }
  // Split on dots outside quotes.
  const pieces: string[] = []
  let cur = ''
  let quote: string | null = null
  for (const ch of raw) {
    if (quote) {
      cur += ch
      if (ch === quote) quote = null
    } else if (OPEN[ch]) {
      quote = OPEN[ch]!
      cur += ch
    } else if (ch === '.') {
      pieces.push(cur)
      cur = ''
    } else cur += ch
  }
  pieces.push(cur)
  const parts = pieces.map(unquote)
  if (!parts.length || parts.some((p) => !p) || !parts.some((p) => WORD.test(p))) return null
  if (/^[\d.]+$/.test(raw)) return null
  let pos = from
  let part = parts.length - 1
  for (let i = 0; i < pieces.length; i++) {
    const end = pos + pieces[i]!.length
    if (offset <= end) {
      part = i
      break
    }
    pos = end + 1
  }
  return { text: parts.join('.'), parts, part, from, to }
}

function walkBlocks(blocks: QueryBlock[] | undefined, visit: (b: QueryBlock) => void): void {
  for (const b of blocks ?? []) {
    visit(b)
    walkBlocks(b.children, visit)
  }
}

/** alias (lower case) → table name, from the statement's query blocks. */
export function aliasMap(statement: Statement | undefined): Map<string, string> {
  const out = new Map<string, string>()
  walkBlocks(statement?.structure, (b) => {
    for (const s of b.sources) if (s.alias && s.kind !== 'SUBQUERY') out.set(s.alias.toLowerCase(), s.name)
  })
  return out
}

/** Every object name the analysis knows (lower case → as written): tables, views, temp tables, CTEs, programs. */
export function knownObjects(analysis: AnalysisResult): Map<string, string> {
  const out = new Map<string, string>()
  const add = (n: string | null | undefined): void => {
    if (n && !out.has(n.toLowerCase())) out.set(n.toLowerCase(), n)
  }
  for (const l of analysis.lifecycles ?? []) add(l.name)
  const walk = (list: Statement[]): void => {
    for (const s of list) {
      s.refs.forEach((r) => add(r.name))
      s.ctes.forEach(add)
      if (s.name) add(s.name)
      walk(s.children ?? [])
    }
  }
  walk(analysis.statements ?? [])
  return out
}

/** Words that are SQL syntax, not names: looking them up would only say "nothing found". */
const KEYWORDS = new Set(
  (
    'select from where and or not in is null as on join left right inner outer full cross group by order having union all ' +
    'distinct insert into values update set delete merge using when matched then else end case create table view index ' +
    'drop alter truncate with recursive begin declare if loop while for return procedure function trigger exists between ' +
    'like limit offset fetch first next rows only top asc desc primary key foreign references default constraint grant revoke ' +
    'commit rollback cursor open close exec execute call temporary temp global local over partition window qualify pivot unpivot'
  ).split(' ')
)

/** True when the token is a single SQL keyword (quoted names are never keywords). */
export function isKeywordToken(token: Token, source: string): boolean {
  if (token.parts.length !== 1) return false
  const raw = source.slice(token.from, token.to)
  return !/^["`[]/.test(raw) && KEYWORDS.has(token.parts[0]!.toLowerCase())
}

const COLUMN_OWNERS = new Set(['TABLE', 'TEMP_TABLE', 'TABLE_VARIABLE', 'VIEW', 'MATERIALIZED_VIEW'])

/** Tables of the statement that have a column with this name, from the column lineage. */
function columnOwners(analysis: AnalysisResult, statementId: number | null, column: string): string[] {
  const lineage = analysis.lineage
  if (!lineage || statementId === null) return []
  const byId = new Map(lineage.nodes.map((n) => [n.id, n]))
  const owners: string[] = []
  const c = column.toLowerCase()
  for (const e of lineage.edges) {
    if (e.statement !== statementId) continue
    for (const id of [e.from, e.to]) {
      const n = byId.get(id)
      if (n && n.column.toLowerCase() === c && COLUMN_OWNERS.has(n.ownerKind) && !owners.includes(n.owner)) owners.push(n.owner)
    }
  }
  return owners
}

/**
 * What a token refers to: an object (table, view, CTE, procedure…) or a table column. Aliases
 * resolve through the statement's query blocks; a bare column takes the table the statement's
 * lineage connects it to. Unknown names are still asked for as objects, so the engine can say
 * there are none.
 */
export function referenceTarget(token: Token, analysis: AnalysisResult, statement: Statement | undefined): ReferenceQuery {
  const objects = knownObjects(analysis)
  const aliases = aliasMap(statement)
  const { parts, part } = token
  const upTo = parts.slice(0, part + 1).join('.')
  // The longest known object name that covers the cursor's part: "shop.orders" on "shop" or "orders".
  for (let end = parts.length; end > part; end--) {
    for (let start = 0; start <= part; start++) {
      const name = parts.slice(start, end).join('.')
      if (objects.has(name.toLowerCase())) return { object: objects.get(name.toLowerCase())! }
    }
  }
  if (part < parts.length - 1) {
    // The cursor is on a qualifier: an alias or a schema-qualified table.
    const alias = aliases.get(upTo.toLowerCase())
    return { object: alias ?? upTo }
  }
  if (parts.length >= 2) {
    const qualifier = parts.slice(0, -1).join('.')
    const table = aliases.get(qualifier.toLowerCase()) ?? objects.get(qualifier.toLowerCase()) ?? qualifier
    return { table, column: parts[parts.length - 1]! }
  }
  const owners = columnOwners(analysis, statement?.id ?? null, parts[0]!)
  if (owners.length) return { table: owners[0]!, column: parts[0]! }
  return { object: parts[0]! }
}

export function queryLabel(q: ReferenceQuery): string {
  return 'object' in q ? q.object : `${q.table}.${q.column}`
}

const sameRange = (a: Occurrence, b: Occurrence): boolean => a.range.start.offset === b.range.start.offset && a.range.end.offset === b.range.end.offset

export interface OccurrenceGroup {
  statement: number
  items: Occurrence[]
}

/** Occurrences grouped by statement in source order, without the definition (which is shown first). */
export function groupOccurrences(result: ReferencesResult): { definition: Occurrence | null; groups: OccurrenceGroup[]; total: number } {
  const def = result.definition
  const rest = result.occurrences.filter((o) => !def || !sameRange(o, def)).sort((a, b) => a.range.start.offset - b.range.start.offset)
  const groups: OccurrenceGroup[] = []
  for (const o of rest) {
    const g = groups.find((x) => x.statement === o.statement)
    if (g) g.items.push(o)
    else groups.push({ statement: o.statement, items: [o] })
  }
  groups.sort((a, b) => a.statement - b.statement)
  return { definition: def, groups, total: rest.length + (def ? 1 : 0) }
}

/** Every range to mark in the editor while the panel is open. */
export function occurrenceRanges(result: ReferencesResult | null): { from: number; to: number; definition: boolean }[] {
  if (!result) return []
  const out = result.occurrences.map((o) => ({ from: o.range.start.offset, to: o.range.end.offset, definition: Boolean(result.definition && sameRange(o, result.definition)) }))
  const def = result.definition
  if (def && !result.occurrences.some((o) => sameRange(o, def))) out.push({ from: def.range.start.offset, to: def.range.end.offset, definition: true })
  return out.sort((a, b) => a.from - b.from)
}

/** The snippet of an occurrence split around the name, for highlighting: "12: FROM [shop.orders] o". */
export function snippetParts(o: Occurrence): { lineNo: number; before: string; match: string; after: string } {
  const m = /^(\d+):\s?(.*)$/s.exec(o.line)
  const lineNo = m ? Number(m[1]) : o.range.start.line
  const text = m ? m[2]! : o.line
  const start = Math.max(0, Math.min(text.length, o.range.start.column - 1))
  const length = o.range.start.line === o.range.end.line ? o.range.end.column - o.range.start.column : text.length - start
  const end = Math.max(start, Math.min(text.length, start + Math.max(0, length)))
  return { lineNo, before: text.slice(0, start), match: text.slice(start, end), after: text.slice(end) }
}

/** Accesses that change or create the object, shown as filled badges. */
export const WRITE_LIKE = new Set(['CREATE', 'INSERT', 'UPDATE', 'DELETE', 'MERGE', 'TRUNCATE', 'ALTER', 'DROP', 'WRITE'])
