import { beforeAll, describe, expect, it } from 'vitest'
import type { AnalysisResult } from '@shared/protocol'
import nightlyJson from './lib/__fixtures__/nightly.json'

/** A fake preload API: analyze resolves when the test says so, so it can type in between. */
let finishAnalysis: (() => void) | null = null
beforeAll(() => {
  const result = { ok: true as const, value: nightlyJson as unknown as AnalysisResult }
  ;(globalThis as unknown as { window: unknown }).window = {
    amber: {
      engine: {
        analyze: () => new Promise((resolve) => (finishAnalysis = () => resolve(result))),
        call: async () => ({ ok: false, error: { code: -32601, message: 'not in this test' } }),
        cancel: async () => undefined
      },
      settings: { update: async (s: unknown) => s },
      db: { loadMetadata: async () => ({ ok: false, error: { code: -32601, message: '' } }) }
    }
  }
})

describe('analysis and editing', () => {
  it('keeps the document marked as edited when the text changed during the analysis', async () => {
    const { useStore } = await import('./store')
    const st = useStore.getState()
    st.loadDocument({ path: null, name: 'a.sql', text: 'select 1;', encoding: 'UTF-8' })
    // The user types while the engine is still analyzing the first text.
    useStore.getState().setDocText('select 1;\nselect 2;')
    finishAnalysis!()
    await new Promise((r) => setTimeout(r, 0))
    expect(useStore.getState().analysis).not.toBeNull()
    expect(useStore.getState().doc?.edited).toBe(true)

    // Analyzing the current text clears the flag.
    const run = useStore.getState().analyze()
    finishAnalysis!()
    await run
    expect(useStore.getState().doc?.edited).toBe(false)
  })
})

describe('rule settings', () => {
  it('go with every analyze-style call', async () => {
    const { analyzeParams } = await import('./store')
    const rules = { disabled: ['AMB-S009'], severity: { 'AMB-S008': 'HIGH' as const } }
    const settings = { encoding: 'auto', rules } as unknown as Parameters<typeof analyzeParams>[2]
    const doc = { path: null, name: 'a.sql', text: 'select 1;', edited: false, modified: true } as unknown as Parameters<typeof analyzeParams>[0]
    expect(analyzeParams(doc, 'oracle', settings, false).rules).toEqual(rules)
    expect(analyzeParams(doc, 'oracle', null, false).rules).toBeUndefined()
    const file = { ...doc, path: '/x/a.sql', modified: false } as typeof doc
    expect(analyzeParams(file, 'oracle', settings, false)).toMatchObject({ path: '/x/a.sql', rules })
  })
})
