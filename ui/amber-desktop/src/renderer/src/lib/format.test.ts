import { describe, expect, it } from 'vitest'
import type { Range, Statement } from '@shared/protocol'
import formatJson from './__fixtures__/format.json'
import { applyChanges, formatSummary, minimalChanges } from './format'

interface Result {
  text: string
  changed: boolean
  warnings: string[]
  skipped: Range[]
}
const fx = formatJson as unknown as { before: string; whole: Result; selection: { start: number; end: number; result: Result }; badBefore: string; bad: Result }

describe('minimalChanges', () => {
  it('turns the engine result into small changes that rebuild it exactly', () => {
    for (const after of [fx.whole.text, fx.selection.result.text, fx.bad.text]) {
      const before = after === fx.bad.text ? fx.badBefore : fx.before
      const changes = minimalChanges(before, after)
      expect(applyChanges(before, changes)).toBe(after)
      // Ascending, not overlapping.
      for (let i = 1; i < changes.length; i++) expect(changes[i]!.from).toBeGreaterThanOrEqual(changes[i - 1]!.to)
    }
  })
  it('keeps untouched lines out of the changes', () => {
    const changes = minimalChanges(fx.before, fx.whole.text)
    // Lines the formatter left alone (the header comment, unchanged lines) are not rewritten.
    const touched = changes.reduce((n, c) => n + (c.to - c.from), 0)
    expect(touched).toBeLessThan(fx.before.length)
    expect(changes[0]!.from).toBeGreaterThan(fx.before.indexOf('\n'))
    expect(changes.length).toBeGreaterThan(1)
    expect(minimalChanges('a\nb\n', 'a\nb\n')).toEqual([])
    expect(minimalChanges('a\nb\nc', 'a\nB\nc')).toEqual([{ from: 2, to: 4, insert: 'B\n' }])
    expect(applyChanges('x', minimalChanges('x', ''))).toBe('')
    expect(applyChanges('', minimalChanges('', 'select 1;\n'))).toBe('select 1;\n')
  })
})

describe('formatSummary', () => {
  const st = (start: number, end: number): Statement => ({ range: { start: { offset: start, line: 1, column: 1 }, end: { offset: end, line: 1, column: 1 } } }) as Statement
  const statements = [st(0, 27), st(29, 57)]
  it('counts formatted and kept statements', () => {
    expect(formatSummary(statements, fx.bad.skipped, null, true)).toBe('Formatted 1 statement · 1 kept as written')
    expect(formatSummary(statements, [], null, true)).toBe('Formatted 2 statements')
    expect(formatSummary(statements, [], { from: 3, to: 5 }, true)).toBe('Formatted 1 statement')
    expect(formatSummary(statements, [], { from: 30, to: 30 }, true)).toBe('Formatted 1 statement')
  })
  it('does not count statements of a script that does not parse', () => {
    expect(formatSummary([], [], null, true)).toBe('Formatted the script')
    expect(formatSummary([], fx.bad.skipped, null, true)).toBe('Formatted the script · 1 kept as written')
  })
  it('says so when nothing changed', () => {
    expect(formatSummary(statements, [], null, false)).toBe('Already formatted: nothing to change.')
    expect(formatSummary(statements, [], { from: 0, to: 4 }, false)).toBe('The selected statements are already formatted.')
  })
})
