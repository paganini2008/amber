import { describe, expect, it } from 'vitest'
import { addApplied, logicKept } from './refactor'

describe('refactor', () => {
  it('counts what each pass applied', () => {
    expect(addApplied(addApplied([], ['Remove ORDER BY', 'Remove DISTINCT']), ['Remove ORDER BY'])).toEqual([
      { title: 'Remove ORDER BY', count: 2 },
      { title: 'Remove DISTINCT', count: 1 }
    ])
  })

  it('accepts only a refactoring whose logic is unchanged', () => {
    expect(logicKept([])).toBe(true)
    // a removed sort or DISTINCT is a proven change of ordering words, not of the logic
    expect(logicKept(['Ordering and row limits: [DISTINCT, ORDER BY] → [ORDER BY]'])).toBe(true)
    for (const d of [
      "Conditions: only in the original [STATUS = 'A'], only in the rewrite [STATUS = 'B']",
      "Literal values: only in the original ['A'], only in the rewrite ['B']",
      'Source tables: [ORDERS] → [ORDERS, CUSTOMERS]',
      'Output columns: [ID] → [ID, NAME]',
      'Lineage of TOTAL: [ORDERS.AMOUNT] → [ORDERS.FEE]'
    ]) {
      expect(logicKept([d])).toBe(false)
    }
  })
})
