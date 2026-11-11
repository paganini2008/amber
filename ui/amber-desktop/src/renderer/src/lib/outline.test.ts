import { describe, expect, it } from 'vitest'
import type { QueryBlock, Ref, Statement } from '@shared/protocol'
import { flattenOutline, statementParts } from './outline'

const range = (from: number, to: number, line = 1) => ({ start: { offset: from, line, column: 1 }, end: { offset: to, line, column: 1 } })
const block = (kind: string, name: string | null, from: number, to: number, sources: string[] = [], children: QueryBlock[] = []): QueryBlock =>
  ({ kind, name, role: null, range: range(from, to, from), sources: sources.map((n) => ({ name: n, kind: 'TABLE', alias: null })), outputs: [], clauses: [], children }) as QueryBlock
const ref = (name: string, at: number, kind = 'TABLE'): Ref => ({ name, key: name, kind, access: 'READ', confidence: 'CERTAIN', range: range(at, at + 3) })
const statement = (id: number, structure: QueryBlock[], refs: Ref[] = [], children: Statement[] = []): Statement =>
  ({ id, kind: 'SELECT', category: 'QUERY', container: false, confidence: 'CERTAIN', name: null, summary: `s${id}`, range: range(0, 500), ctes: [], refs, children, structure }) as Statement

describe('statementParts', () => {
  it('lists the CTEs of a WITH query in order, with what each one reads', () => {
    const s = statement(
      0,
      [block('CTE', 'second', 100, 200), block('CTE', 'first', 10, 90, [], [block('DERIVED', null, 20, 80)]), block('QUERY', null, 210, 300, ['second'])],
      [ref('raw_orders', 30), ref('first', 120, 'CTE'), ref('customers', 150), ref('second', 250, 'CTE')]
    )
    expect(statementParts(s)).toEqual([
      { name: 'first', range: s.structure![1]!.range, sources: ['raw_orders'] },
      { name: 'second', range: s.structure![0]!.range, sources: ['first', 'customers'] }
    ])
  })

  it('shows CTEs as rows under their statement, unless it is collapsed or has only one', () => {
    const many = statement(0, [block('CTE', 'a', 10, 20), block('CTE', 'b', 30, 40)])
    const one = statement(1, [block('CTE', 'only', 10, 20)])
    expect(flattenOutline([many, one], new Set()).map((r) => r.part?.name ?? `#${r.statement.id}`)).toEqual(['#0', 'a', 'b', '#1'])
    expect(flattenOutline([many], new Set([0])).map((r) => r.part?.name ?? `#${r.statement.id}`)).toEqual(['#0'])
  })
})
