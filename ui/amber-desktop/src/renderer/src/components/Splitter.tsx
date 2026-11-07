import clsx from 'clsx'
import { useCallback, useState } from 'react'

const read = (key: string, fallback: number): number => {
  try {
    const v = Number(localStorage.getItem(key))
    return Number.isFinite(v) && v > 0 ? v : fallback
  } catch {
    return fallback
  }
}

/** A size remembered per viewer (layout convenience only). */
export function usePaneSize(key: string, fallback: number): [number, (v: number) => void] {
  const [size, setSize] = useState(() => read(key, fallback))
  const update = useCallback(
    (v: number) => {
      setSize(v)
      try {
        localStorage.setItem(key, String(Math.round(v)))
      } catch {
        // Storage can be unavailable; the size just is not remembered.
      }
    },
    [key]
  )
  return [size, update]
}

/**
 * Drag handle between panes. `direction` is the axis the handle moves along; `invert` for panes
 * that grow when the handle moves left/up (right and bottom panes).
 */
export function Splitter({
  direction,
  size,
  onChange,
  min,
  max,
  invert,
  label
}: {
  direction: 'x' | 'y'
  size: number
  onChange: (v: number) => void
  min: number
  max: number
  invert?: boolean
  label: string
}): React.JSX.Element {
  const onPointerDown = (event: React.PointerEvent): void => {
    event.preventDefault()
    const start = direction === 'x' ? event.clientX : event.clientY
    const startSize = size
    const target = event.currentTarget as HTMLElement
    target.setPointerCapture(event.pointerId)
    const move = (e: PointerEvent): void => {
      const delta = (direction === 'x' ? e.clientX : e.clientY) - start
      onChange(Math.min(max, Math.max(min, startSize + (invert ? -delta : delta))))
    }
    const up = (): void => {
      target.removeEventListener('pointermove', move)
      target.removeEventListener('pointerup', up)
    }
    target.addEventListener('pointermove', move)
    target.addEventListener('pointerup', up)
  }
  const onKeyDown = (event: React.KeyboardEvent): void => {
    const step = event.shiftKey ? 40 : 10
    const dec = direction === 'x' ? 'ArrowLeft' : 'ArrowUp'
    const inc = direction === 'x' ? 'ArrowRight' : 'ArrowDown'
    if (event.key !== dec && event.key !== inc) return
    event.preventDefault()
    const sign = (event.key === inc ? 1 : -1) * (invert ? -1 : 1)
    onChange(Math.min(max, Math.max(min, size + sign * step)))
  }
  return (
    <div
      role="separator"
      aria-label={label}
      aria-orientation={direction === 'x' ? 'vertical' : 'horizontal'}
      aria-valuenow={Math.round(size)}
      aria-valuemin={min}
      aria-valuemax={max}
      tabIndex={0}
      onPointerDown={onPointerDown}
      onKeyDown={onKeyDown}
      className={clsx(
        'group relative z-10 shrink-0 bg-line outline-none',
        direction === 'x' ? 'w-px cursor-col-resize' : 'h-px cursor-row-resize'
      )}
    >
      <div
        className={clsx(
          'absolute transition-colors group-hover:bg-amber/60 group-focus-visible:bg-amber',
          direction === 'x' ? 'inset-y-0 -left-[2px] w-[5px]' : 'inset-x-0 -top-[2px] h-[5px]'
        )}
      />
    </div>
  )
}
