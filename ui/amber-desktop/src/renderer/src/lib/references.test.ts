import { describe, expect, it } from 'vitest'
import type { AnalysisResult, ReferencesResult } from '@shared/protocol'
import refsJson from './__fixtures__/references.json'
import structureJson from './__fixtures__/structure.json'
import { aliasMap, groupOccurrences, isKeywordToken, knownObjects, occurrenceRanges, queryLabel, referenceTarget, snippetParts, tokenAt } from './references'

const refs = refsJson as unknown as Record<string, ReferencesResult>
const analysis = structureJson as unknown as AnalysisResult
const insert = analysis.statements[0]!

describe('tokenAt', () => {
  const text = 'select c.id, "Order Lines".qty from crm.customer c where [dbo].[t].x = 1.5;'
  it('reads dotted, quoted and bracketed names and knows which part the cursor is in', () => {
    const at = (needle: string, delta = 0): ReturnType<typeof tokenAt> => tokenAt(text, text.indexOf(needle) + delta)
    expect(at('c.id')).toMatchObject({ text: 'c.id', parts: ['c', 'id'], part: 0 })
    expect(at('c.id', 3)).toMatchObject({ text: 'c.id', part: 1 })
    expect(at('"Order', 2)).toMatchObject({ text: 'Order Lines.qty', parts: ['Order Lines', 'qty'], part: 0 })
    expect(at('crm.customer', 5)).toMatchObject({ text: 'crm.customer', part: 1, from: text.indexOf('crm'), to: text.indexOf('crm') + 12 })
    expect(at('[dbo]', 8)).toMatchObject({ parts: ['dbo', 't', 'x'], part: 1 })
    // End of a word counts (cursor right after it).
    expect(at('select', 6)).toMatchObject({ text: 'select' })
  })
  it('ignores punctuation, spaces and numbers', () => {
    expect(tokenAt(text, text.indexOf(',') + 1)).toBeNull()
    expect(tokenAt(text, text.indexOf('1.5') + 1)).toBeNull()
    expect(tokenAt('a = ;', 2)).toBeNull()
  })
})

describe('referenceTarget', () => {
  const tok = (text: string, offset: number): NonNullable<ReturnType<typeof tokenAt>> => tokenAt(text, offset)!
  it('finds objects, including the qualified name around the cursor', () => {
    expect(knownObjects(analysis).get('crm.customer')).toBe('crm.customer')
    expect(referenceTarget(tok('crm.customer', 6), analysis, insert)).toEqual({ object: 'crm.customer' })
    expect(referenceTarget(tok('paid', 1), analysis, insert)).toEqual({ object: 'paid' })
    // On the schema part of a known table, the table is meant, not the schema.
    expect(referenceTarget(tok('sales.orders o', 1), analysis, insert)).toEqual({ object: 'sales.orders' })
    expect(referenceTarget(tok('REPORT.TOP_CUSTOMER', 9), analysis, insert)).toEqual({ object: 'report.top_customer' })
  })
  it('resolves aliases for qualifiers and columns', () => {
    expect(aliasMap(insert).get('c')).toBe('crm.customer')
    expect(aliasMap(insert).has('r')).toBe(false) // derived tables are not tables
    expect(referenceTarget(tok('c.name', 0), analysis, insert)).toEqual({ object: 'crm.customer' })
    expect(referenceTarget(tok('c.name', 3), analysis, insert)).toEqual({ table: 'crm.customer', column: 'name' })
    expect(referenceTarget(tok('crm.blocked.customer_id', 15), analysis, insert)).toEqual({ table: 'crm.blocked', column: 'customer_id' })
  })
  it('takes the table of a bare column from the statement lineage, else asks for an object', () => {
    expect(referenceTarget(tok('amount', 2), analysis, insert)).toEqual({ table: 'sales.orders', column: 'amount' })
    expect(referenceTarget(tok('mystery', 2), analysis, insert)).toEqual({ object: 'mystery' })
    expect(queryLabel({ table: 't', column: 'c' })).toBe('t.c')
  })
})

describe('occurrences', () => {
  it('shows the definition first and groups the rest by statement without repeating it', () => {
    const g = groupOccurrences(refs.tmpOrders!)
    expect(g.definition?.access).toBe('CREATE')
    expect(g.groups[0]!.statement).toBeGreaterThanOrEqual(1)
    expect(g.groups.flatMap((x) => x.items).some((o) => o.access === 'CREATE' && o.range.start.offset === g.definition!.range.start.offset)).toBe(false)
    expect(g.total).toBe(refs.tmpOrders!.occurrences.length)
    expect(g.groups.map((x) => x.statement)).toEqual([...g.groups.map((x) => x.statement)].sort((a, b) => a - b))
  })
  it('handles names defined outside the script, CTE definitions and no match', () => {
    const outside = groupOccurrences(refs.shopOrders!)
    expect(outside.definition).toBeNull()
    expect(outside.total).toBe(2)
    const cte = groupOccurrences(refs.spendCte!)
    expect(cte.definition?.line).toBe('20: WITH spend AS (')
    expect(cte.total).toBe(2)
    expect(groupOccurrences(refs.nothing!)).toEqual({ definition: null, groups: [], total: 0 })
  })
  it('lists every editor range once, marking the definition', () => {
    const r = occurrenceRanges(refs.spendCte!)
    expect(r).toHaveLength(2)
    expect(r[0]!.definition).toBe(true)
    expect(occurrenceRanges(refs.tmpOrders!).filter((x) => x.definition)).toHaveLength(1)
    expect(occurrenceRanges(null)).toEqual([])
  })
  it('splits the snippet around the name', () => {
    expect(snippetParts(refs.shopOrders!.occurrences[0]!)).toEqual({ lineNo: 8, before: 'FROM ', match: 'shop.orders', after: ' o' })
    const col = refs.tierColumn!.occurrences.find((o) => o.access === 'WRITE')!
    expect(snippetParts(col)).toMatchObject({ lineNo: 19, match: 'tier', after: ')' })
    // A definition spanning lines highlights to the end of its first line.
    expect(snippetParts(refs.spendCte!.definition!)).toEqual({ lineNo: 20, before: 'WITH ', match: 'spend AS (', after: '' })
  })
})

describe('keywords and blanks', () => {
  it('does not look up SQL keywords, but does look up quoted names and qualified ones', () => {
    const text = 'SELECT "select", t.from FROM t WHERE x = 1;'
    const at = (needle: string): NonNullable<ReturnType<typeof tokenAt>> => tokenAt(text, text.indexOf(needle) + 1)!
    expect(isKeywordToken(at('SELECT'), text)).toBe(true)
    expect(isKeywordToken(at('WHERE'), text)).toBe(true)
    expect(isKeywordToken(at('"select"'), text)).toBe(false)
    expect(isKeywordToken(at('t.from'), text)).toBe(false)
    expect(isKeywordToken(tokenAt(text, text.indexOf(' t ') + 1)!, text)).toBe(false)
    expect(tokenAt(text, text.indexOf('= ') + 1)).toBeNull()
  })
})

describe('snippets from the script text', () => {
  it('highlights the right characters on indented lines, where the engine trims the line', () => {
    const source = 'BEGIN\n    INSERT INTO dw.daily (a) VALUES (1);\nEND;'
    const start = source.indexOf('dw.daily')
    const o = {
      statement: 1,
      access: 'INSERT',
      line: '2: INSERT INTO dw.daily (a) VALUES (1);',
      range: { start: { offset: start, line: 2, column: 17 }, end: { offset: start + 8, line: 2, column: 25 } },
      via: null
    }
    expect(snippetParts(o, source)).toEqual({ lineNo: 2, before: 'INSERT INTO ', match: 'dw.daily', after: ' (a) VALUES (1);' })
    // A range that runs over several lines is highlighted to the end of its first line.
    const multi = { ...o, range: { ...o.range, end: { offset: source.length, line: 3, column: 5 } } }
    expect(snippetParts(multi, source).match).toBe('dw.daily (a) VALUES (1);')
  })
})
