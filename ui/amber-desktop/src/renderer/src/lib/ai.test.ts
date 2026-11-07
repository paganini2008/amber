import { describe, expect, it } from 'vitest'
import { appendDelta, balancedObjects, checkLabelText, parseSuggestions, sliceRange, targetStatement, unmaskText } from './ai'

describe('parseSuggestions', () => {
  const body = '{"suggestions":[{"title":"Use EXISTS","target":{"statement":3},"rationale":"r","expectedEffect":"e","risk":"low","rewrittenSql":"select 1"}]}'

  it('reads plain, fenced and surrounded JSON', () => {
    for (const text of [body, '```json\n' + body + '\n```', 'Here you go:\n' + body + '\nThanks.']) {
      const s = parseSuggestions(text)
      expect(s).toHaveLength(1)
      expect(s![0]).toEqual({ title: 'Use EXISTS', target: { statement: 3 }, rationale: 'r', expectedEffect: 'e', risk: 'low', rewrittenSql: 'select 1' })
    }
  })

  it('normalizes missing and odd fields', () => {
    const s = parseSuggestions('{"suggestions":[{"title":"","target":{"statement":"7"}},{"target":null},42]}')!
    expect(s).toHaveLength(2)
    expect(s[0]!.target?.statement).toBe(7)
    expect(s[0]!.title).toBeUndefined()
    expect(s[1]!.target?.statement).toBeNull()
  })

  it('returns null for anything else', () => {
    expect(parseSuggestions('no json here')).toBeNull()
    expect(parseSuggestions('{"ideas":[]}')).toBeNull()
    expect(parseSuggestions('{"suggestions":[]}')).toEqual([])
  })
})

describe('unmaskText', () => {
  const mask = { T1: 'sales.orders', T12: 'crm.customer', C1: 'amount', "'S1'": "'PAID'" }

  it('restores whole aliases only, longest first', () => {
    expect(unmaskText('Filter T1.C1 before joining T12', mask)).toBe('Filter sales.orders.amount before joining crm.customer')
    expect(unmaskText("where status = 'S1'", mask)).toBe("where status = 'PAID'")
    expect(unmaskText('T123 and XT1 and T1_x stay', mask)).toBe('T123 and XT1 and T1_x stay')
  })

  it('leaves text alone without a mask', () => {
    expect(unmaskText('T1', {})).toBe('T1')
    expect(unmaskText('T1', null)).toBe('T1')
  })
})

describe('targetStatement and labels', () => {
  const exists = (id: number): boolean => id >= 0 && id < 5

  it('prefers the suggestion target, then the scope statement', () => {
    expect(targetStatement({ target: { statement: 2 } }, exists, 4)).toBe(2)
    expect(targetStatement({ target: { statement: 9 } }, exists, 4)).toBe(4)
    expect(targetStatement({}, exists, null)).toBeNull()
  })

  it('slices the source text of a range', () => {
    const text = 'select 1;\nselect 2;'
    expect(sliceRange(text, { start: { offset: 10, line: 2, column: 1 }, end: { offset: 19, line: 2, column: 10 } })).toBe('select 2;')
  })

  it('never words a check as equivalent or safe', () => {
    expect(checkLabelText('STRUCTURE_PRESERVED')).toBe('Structure preserved')
    expect(checkLabelText('STRUCTURE_CHANGED')).toBe('Structure changed')
    expect(checkLabelText('INVALID_SQL')).toBe('Invalid SQL')
    for (const l of ['STRUCTURE_PRESERVED', 'STRUCTURE_CHANGED', 'INVALID_SQL', 'OTHER']) expect(checkLabelText(l)).not.toMatch(/equivalent|safe/i)
  })
})

describe('parseSuggestions with real-model quirks', () => {
  it('skips a reasoning block and trailing objects', () => {
    const out = '<think>Let me look at {the} query.</think>\n{"suggestions":[{"title":"Use NOT EXISTS","rewrittenSql":"select 1 where \\"}\\""}]}\n{"note":"extra"}'
    const s = parseSuggestions(out)
    expect(s?.[0]?.title).toBe('Use NOT EXISTS')
    expect(s?.[0]?.rewrittenSql).toBe('select 1 where "}"')
  })
  it('accepts a bare list in a code fence (qwen2.5-coder did this)', () => {
    const s = parseSuggestions('```json\n[{"title":"Use NOT EXISTS","target":{"statement":14},"rewrittenSql":"select 1"}]\n```')
    expect(s?.[0]?.title).toBe('Use NOT EXISTS')
    expect(s?.[0]?.target?.statement).toBe(14)
  })
  it('finds balanced objects', () => {
    expect(balancedObjects('a {"x":{"y":1}} b {"z":"}"}')).toEqual(['{"x":{"y":1}}', '{"z":"}"}'])
  })
})

describe('conversation', () => {
  it('continues the same chat with earlier turns as user/assistant pairs', async () => {
    const { conversationMessages, estimateTokens } = await import('./ai')
    const first = conversationMessages('SYS', [], 'CONTEXT')
    expect(first).toEqual([
      { role: 'system', content: 'SYS' },
      { role: 'user', content: 'CONTEXT' }
    ])
    const turns = [
      { prompt: 'CONTEXT', answer: '{"suggestions":[]}' },
      { prompt: 'try T1 again', answer: '' }, // cancelled: left out
      { prompt: 'C2 is nullable', answer: '{"suggestions":[{"title":"x"}]}' }
    ]
    expect(conversationMessages('SYS', turns, 'prefer readability').map((m) => [m.role, m.content])).toEqual([
      ['system', 'SYS'],
      ['user', 'CONTEXT'],
      ['assistant', '{"suggestions":[]}'],
      ['user', 'C2 is nullable'],
      ['assistant', '{"suggestions":[{"title":"x"}]}'],
      ['user', 'prefer readability']
    ])
    expect(estimateTokens([{ content: 'abcd' }, { content: 'abcde' }])).toBe(3)
  })
})

describe('streaming answers', () => {
  it('appends fragments once', () => {
    expect(['{"sug', 'gestions":[]}'].reduce(appendDelta, '')).toBe('{"suggestions":[]}')
  })
  it('never mutates the answer inside a state updater (StrictMode doubled the text twice before)', async () => {
    const source = (await import('../components/DetailsPanel.tsx?raw')).default
    expect(source).toContain('streamRef')
    const updaters = [...source.matchAll(/setPhase\(\(p\) => \{([\s\S]*?)\n {6}\}\)/g)]
    expect(updaters.length).toBeGreaterThan(0)
    for (const updater of updaters) {
      expect(updater[1]).not.toMatch(/outputRef\.current\s*(\+=|=)/)
    }
  })
})
