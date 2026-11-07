import { describe, expect, it } from 'vitest'
import type { AnalysisResult } from '@shared/protocol'
import nightlyJson from './__fixtures__/nightly.json'
import { countBySeverity, ignoreHint, sortedFindings, worstByStatement } from './findings'

const nightly = nightlyJson as unknown as AnalysisResult

describe('findings', () => {
  const findings = nightly.findings!

  it('are present in the analysis result with the documented shape', () => {
    expect(findings.length).toBeGreaterThan(0)
    for (const f of findings) {
      expect(f.rule).toMatch(/^AMB-[A-Z]\d{3}$/)
      expect(['HIGH', 'MEDIUM', 'LOW', 'INFO']).toContain(f.severity)
      expect(typeof f.statement).toBe('number')
    }
  })

  it('sort most severe first and keep their original index', () => {
    const sorted = sortedFindings([
      { ...findings[0]!, severity: 'LOW' },
      { ...findings[0]!, severity: 'HIGH' },
      { ...findings[0]!, severity: 'INFO' }
    ])
    expect(sorted.map((s) => s.finding.severity)).toEqual(['HIGH', 'LOW', 'INFO'])
    expect(sorted.map((s) => s.index)).toEqual([1, 0, 2])
  })

  it('count per severity and mark the worst per statement', () => {
    const list = [
      { ...findings[0]!, statement: 3, severity: 'LOW' },
      { ...findings[0]!, statement: 3, severity: 'MEDIUM' },
      { ...findings[0]!, statement: 4, severity: 'INFO' }
    ]
    expect(countBySeverity(list)).toEqual({ HIGH: 0, MEDIUM: 1, LOW: 1, INFO: 1 })
    expect(worstByStatement(list).get(3)).toEqual({ severity: 'MEDIUM', count: 2 })
  })

  it('explain how to suppress a rule', () => {
    expect(ignoreHint('AMB-S002')).toBe('-- amber:ignore AMB-S002')
  })
})
