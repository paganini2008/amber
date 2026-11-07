import { describe, expect, it } from 'vitest'
import { SAMPLES } from './index'

describe('samples', () => {
  it('has one sample per database, covering every dialect id', () => {
    const dialects = SAMPLES.map((s) => s.dialect)
    expect(new Set(dialects).size).toBe(dialects.length)
    expect([...dialects].sort()).toEqual(['db2', 'h2', 'mariadb', 'mysql', 'oracle', 'postgresql', 'sqlite', 'sqlserver'])
    expect(dialects.slice(0, 3)).toEqual(['oracle', 'sqlserver', 'postgresql'])
    for (const s of SAMPLES) expect(s.text.trim().length).toBeGreaterThan(0)
    expect(new Set(SAMPLES.map((s) => s.name)).size).toBe(SAMPLES.length)
  })
})
