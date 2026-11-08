import { describe, expect, it } from 'vitest'
import { codemirrorDialect, dialectWords } from './dialects'

const IDS = ['oracle', 'sqlserver', 'postgresql', 'mysql', 'mariadb', 'db2', 'h2', 'sqlite']

const EXPECTED: Record<string, string[]> = {
  oracle: ['merge', 'using', 'matched', 'connect', 'start', 'prior', 'pivot', 'unpivot', 'returning', 'fetch', 'offset', 'over', 'partition', 'bulk', 'collect', 'forall', 'exception', 'cursor', 'declare', 'begin', 'end', 'elsif', 'loop'],
  sqlserver: ['merge', 'using', 'matched', 'output', 'top', 'fetch', 'offset', 'over', 'partition', 'pivot', 'unpivot', 'apply', 'try', 'catch', 'declare', 'begin', 'end', 'cursor', 'for', 'next'],
  postgresql: ['merge', 'using', 'matched', 'returning', 'lateral', 'window', 'over', 'partition', 'fetch', 'offset', 'conflict', 'elsif', 'loop', 'exception', 'declare', 'begin', 'end'],
  mysql: ['window', 'over', 'partition', 'lateral', 'offset', 'duplicate', 'elseif', 'loop', 'cursor', 'declare', 'handler'],
  mariadb: ['window', 'over', 'partition', 'returning', 'offset', 'elseif', 'loop', 'cursor', 'declare'],
  db2: ['merge', 'using', 'matched', 'fetch', 'first', 'rows', 'only', 'offset', 'over', 'partition', 'lateral', 'truncate', 'connect', 'prior', 'cursor', 'declare', 'begin', 'end', 'elseif', 'loop'],
  h2: ['merge', 'using', 'matched', 'then', 'qualify', 'top', 'fetch', 'offset', 'over', 'partition', 'window', 'by', 'asc', 'desc'],
  sqlite: ['returning', 'window', 'over', 'partition', 'filter', 'conflict', 'offset', 'limit']
}

describe('editor dialects', () => {
  it('highlight the clauses legacy scripts use as keywords', () => {
    for (const id of IDS) {
      const w = dialectWords(id)!
      for (const k of EXPECTED[id]!) expect(w.keywords.has(k), `${id}: ${k}`).toBe(true)
    }
  })

  it('never mark built-in functions as keywords', () => {
    for (const id of IDS) {
      const w = dialectWords(id)!
      for (const f of ['count', 'sum', 'avg', 'max', 'min', 'trunc', 'nvl', 'coalesce', 'row_number', 'substring', 'upper']) {
        expect(w.keywords.has(f), `${id}: ${f}`).toBe(false)
      }
    }
  })

  it('keep MERGE out of the dialects that have no MERGE statement', () => {
    for (const id of ['mysql', 'mariadb', 'sqlite']) expect(dialectWords(id)!.keywords.has('matched'), id).toBe(false)
  })

  it('build a CodeMirror dialect for every engine dialect', () => {
    for (const id of IDS) expect(codemirrorDialect(id).spec.keywords?.split(' ')).toContain('select')
    expect(codemirrorDialect('oracle').spec.keywords?.split(' ')).not.toContain('count')
    expect(codemirrorDialect('oracle')).toBe(codemirrorDialect('oracle'))
    expect(codemirrorDialect('unknown')).toBeDefined()
  })
})
