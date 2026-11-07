import { describe, expect, it } from 'vitest'
import { fitPanes } from './panes'

describe('fitPanes', () => {
  it('keeps saved widths when the window has room', () => {
    expect(fitPanes(1920, 280, 340, true)).toEqual({ left: 280, right: 340 })
  })
  it('shrinks the right panel first, then the outline, keeping the center usable', () => {
    // 960 px window with both panels dragged to their maximum.
    const f = fitPanes(960, 520, 620, true)
    expect(f.right).toBe(260)
    expect(f.left + f.right + 8 + 320).toBeLessThanOrEqual(960)
    expect(f.left).toBeGreaterThanOrEqual(180)
  })
  it('never goes below the minimums, even in a tiny window', () => {
    expect(fitPanes(600, 520, 620, true)).toEqual({ left: 180, right: 260 })
  })
  it('ignores the right panel while it is hidden', () => {
    expect(fitPanes(960, 520, 620, false)).toEqual({ left: 520, right: 620 })
    expect(fitPanes(800, 520, 620, false).left).toBe(800 - 4 - 320)
  })
})
