/**
 * Side panel widths that fit the window: the saved widths are kept when there is room, and shrunk
 * (right panel first, then the outline, never below their minimums) so the center keeps
 * `minCenter` pixels. Splitters take `gutter` pixels each.
 */
export function fitPanes(
  windowWidth: number,
  left: number,
  right: number,
  rightVisible: boolean,
  { minLeft = 180, minRight = 260, minCenter = 320, gutter = 4 } = {}
): { left: number; right: number } {
  const splitters = gutter * (rightVisible ? 2 : 1)
  let l = Math.max(minLeft, left)
  let r = rightVisible ? Math.max(minRight, right) : 0
  let excess = l + r + splitters + minCenter - windowWidth
  if (excess > 0 && rightVisible) {
    const take = Math.min(excess, r - minRight)
    r -= take
    excess -= take
  }
  if (excess > 0) {
    const take = Math.min(excess, l - minLeft)
    l -= take
  }
  return { left: l, right: rightVisible ? r : right }
}
