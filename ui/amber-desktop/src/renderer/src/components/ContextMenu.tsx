import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useStore } from '@/store'

/** A small context menu at the pointer, fed by `openContextMenu` (editor, chips, rows). */
export function ContextMenu(): React.JSX.Element | null {
  const menu = useStore((s) => s.contextMenu)
  const close = useStore((s) => s.closeContextMenu)
  const ref = useRef<HTMLDivElement>(null)
  // Placed at the pointer, then moved inside the window once its real size is known.
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null)
  useLayoutEffect(() => {
    if (!menu || !ref.current) {
      setPos(null)
      return
    }
    const r = ref.current.getBoundingClientRect()
    setPos({
      left: Math.max(8, Math.min(menu.x, window.innerWidth - r.width - 8)),
      top: Math.max(8, Math.min(menu.y, window.innerHeight - r.height - 8))
    })
  }, [menu])

  useEffect(() => {
    if (!menu) return
    ref.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
    const onDown = (e: MouseEvent): void => {
      if (!ref.current?.contains(e.target as Node)) close()
    }
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') {
        e.preventDefault()
        close()
      } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault()
        const items = [...(ref.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])]
        const i = items.indexOf(document.activeElement as HTMLButtonElement)
        items[(i + (e.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length]?.focus()
      }
    }
    window.addEventListener('mousedown', onDown, true)
    window.addEventListener('keydown', onKey, true)
    window.addEventListener('blur', close)
    window.addEventListener('resize', close)
    return () => {
      window.removeEventListener('mousedown', onDown, true)
      window.removeEventListener('keydown', onKey, true)
      window.removeEventListener('blur', close)
      window.removeEventListener('resize', close)
    }
  }, [menu, close])

  if (!menu) return null
  return (
    <div
      ref={ref}
      role="menu"
      style={{ left: pos?.left ?? menu.x, top: pos?.top ?? menu.y, visibility: pos ? 'visible' : 'hidden' }}
      className="fixed z-50 max-w-[320px] min-w-56 rounded-[6px] border border-line bg-surface p-1 text-ink shadow-[0_8px_28px_rgba(0,0,0,0.18)]"
    >
      {menu.items.map((item) => (
        <button
          key={item.label}
          type="button"
          role="menuitem"
          disabled={item.disabled}
          onClick={() => {
            close()
            item.onSelect()
          }}
          className="flex h-7 w-full items-center gap-3 rounded-[4px] px-2 text-left text-sm outline-none hover:bg-surface-hover focus:bg-surface-hover disabled:opacity-45"
        >
          <span className="min-w-0 flex-1 truncate">{item.label}</span>
          {item.hint ? <span className="text-xs text-ink-muted">{item.hint}</span> : null}
        </button>
      ))}
    </div>
  )
}
