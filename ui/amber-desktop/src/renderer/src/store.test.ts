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
