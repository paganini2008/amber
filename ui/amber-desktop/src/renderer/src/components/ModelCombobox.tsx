import clsx from 'clsx'
import { Check, ChevronDown, RefreshCw } from 'lucide-react'
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import type { ModelOption } from '@shared/aiModels'
import { t, type MessageKey } from '@/i18n'
import { Button, Spinner, inputClass } from './ui'

const GROUP_KEY: Record<ModelOption['group'], MessageKey> = {
  recommended: 'settings.models.recommended',
  available: 'settings.models.fromProvider',
  more: 'settings.models.more'
}

/** The preset notes (shared/aiModels.ts, English) in the UI language. */
const NOTE_KEY: Record<string, MessageKey> = {
  'reads long scripts well': 'settings.models.notes.longScripts',
  'faster and cheaper': 'settings.models.notes.faster',
  'reasoning model': 'settings.models.notes.reasoning',
  'general purpose': 'settings.models.notes.general',
  'small and fast': 'settings.models.notes.small',
  'code model, runs on a laptop': 'settings.models.notes.codeLaptop',
  'code model, more accurate': 'settings.models.notes.codeAccurate',
  'general model with reasoning': 'settings.models.notes.generalReasoning',
  'code model': 'settings.models.notes.code',
  'older code model': 'settings.models.notes.olderCode'
}

const noteText = (note: string): string => (NOTE_KEY[note] ? t(NOTE_KEY[note]) : note)

/**
 * A model picker that also takes any typed name: suggestions grouped (recommended, from the
 * provider, more), with whether the provider has each one. Shared by Ollama and OpenAI-compatible.
 */
export function ModelCombobox({
  label,
  value,
  onCommit,
  options,
  availableWord,
  missingHint,
  loading,
  onLoad,
  placeholder
}: {
  label: string
  value: string
  onCommit: (model: string) => void
  options: ModelOption[]
  /** "installed" for Ollama, "available" for an API. */
  availableWord: string
  /** Shown for a suggestion the provider does not list, e.g. how to install it. */
  missingHint: (id: string) => string
  loading: boolean
  onLoad: () => void
  placeholder?: string
}): React.JSX.Element {
  const listId = useId()
  const [text, setText] = useState(value)
  const [lastValue, setLastValue] = useState(value)
  if (value !== lastValue) {
    setLastValue(value)
    setText(value)
  }
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(-1)
  const inputRef = useRef<HTMLInputElement>(null)
  /**
   * The list is drawn in the dialog element (not inside the scrolling body, which would clip it),
   * placed under the input and kept inside the dialog's edges.
   */
  const [place, setPlace] = useState<{ host: HTMLElement; left: number; top?: number; bottom?: number; width: number; maxHeight: number } | null>(null)
  useEffect(() => {
    if (!open) return
    const measure = (): void => {
      const input = inputRef.current
      if (!input) return
      const host = (input.closest('[role="dialog"]') as HTMLElement | null) ?? document.body
      const box = host.getBoundingClientRect()
      const r = input.getBoundingClientRect()
      const width = Math.min(Math.max(r.width, 400), box.width - 24)
      const left = Math.max(12, Math.min(r.left - box.left, box.width - width - 12))
      // Open upward when there is clearly more room above the input than below it.
      const below = window.innerHeight - r.bottom - 16
      const above = r.top - 16
      if (below < 240 && above > below) setPlace({ host, left, bottom: box.bottom - r.top + 4, width, maxHeight: Math.min(320, above) })
      else setPlace({ host, left, top: r.bottom - box.top + 4, width, maxHeight: Math.min(320, Math.max(160, below)) })
    }
    measure()
    window.addEventListener('resize', measure)
    window.addEventListener('scroll', measure, true)
    return () => {
      window.removeEventListener('resize', measure)
      window.removeEventListener('scroll', measure, true)
    }
  }, [open])

  const q = text.trim().toLowerCase()
  // While the box shows the saved model, list everything; once the user types, filter.
  const shown = useMemo(() => (q && text !== value ? options.filter((o) => o.id.toLowerCase().includes(q)) : options), [options, q, text, value])

  const commit = (model: string): void => {
    const m = model.trim()
    setText(m)
    setOpen(false)
    setActive(-1)
    if (m !== value) onCommit(m)
  }

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>): void => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      setOpen(true)
      if (!shown.length) return
      setActive((i) => (e.key === 'ArrowDown' ? (i + 1) % shown.length : (i - 1 + shown.length) % shown.length))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      commit(open && active >= 0 && shown[active] ? shown[active].id : text)
    } else if (e.key === 'Escape') {
      if (open) {
        e.preventDefault()
        e.stopPropagation()
        setOpen(false)
        setText(value)
      }
    }
  }

  let lastGroup: string | null = null
  return (
    <div className="flex gap-2">
      <div className="relative min-w-0 flex-1">
        <input
          ref={inputRef}
          role="combobox"
          aria-label={label}
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={open && active >= 0 ? `${listId}-${active}` : undefined}
          className={clsx(inputClass, 'code pr-7 text-[13px]')}
          value={text}
          placeholder={placeholder}
          spellCheck={false}
          autoComplete="off"
          onFocus={() => setOpen(true)}
          onClick={() => setOpen(true)}
          onChange={(e) => {
            setText(e.target.value)
            setOpen(true)
            setActive(-1)
          }}
          onBlur={() => commit(text)}
          onKeyDown={onKeyDown}
        />
        <button
          type="button"
          tabIndex={-1}
          aria-label={open ? t('settings.models.hide') : t('settings.models.show')}
          className="absolute top-0 right-0 flex h-7 w-7 items-center justify-center text-ink-muted hover:text-ink"
          onMouseDown={(e) => {
            e.preventDefault()
            setOpen(!open)
            inputRef.current?.focus()
          }}
        >
          <ChevronDown size={14} />
        </button>
        {open && shown.length && place
          ? createPortal(
          <ul
            id={listId}
            role="listbox"
            aria-label={t('settings.models.suggestions', { label })}
            style={{ left: place.left, top: place.top, bottom: place.bottom, width: place.width, maxHeight: place.maxHeight }}
            className="absolute z-50 overflow-auto rounded-[6px] border border-line bg-surface py-1 shadow-[0_8px_28px_rgba(0,0,0,0.18)]"
          >
            {shown.map((o, i) => {
              const header = o.group !== lastGroup ? t(GROUP_KEY[o.group]) : null
              lastGroup = o.group
              return (
                <li key={o.id} role="presentation">
                  {header ? <div className="px-2.5 pt-1.5 pb-0.5 text-xs font-medium text-ink-muted">{header}</div> : null}
                  <div
                    id={`${listId}-${i}`}
                    role="option"
                    aria-selected={o.id === value}
                    onMouseDown={(e) => {
                      e.preventDefault()
                      commit(o.id)
                    }}
                    onMouseEnter={() => setActive(i)}
                    className={clsx('flex cursor-default items-start gap-2 px-2.5 py-1', i === active ? 'bg-surface-hover' : '')}
                  >
                    <span className="mt-0.5 w-3.5 shrink-0 text-amber-ink">{o.id === value ? <Check size={13} /> : null}</span>
                    <span className="min-w-0 flex-1">
                      <span className="code block truncate text-[13px] text-ink">{o.id}</span>
                      {o.note || o.available === false ? (
                        <span className="block truncate text-xs text-ink-muted">
                          {[o.note ? noteText(o.note) : null, o.available === false ? missingHint(o.id) : null].filter(Boolean).join(' · ')}
                        </span>
                      ) : null}
                    </span>
                    {o.available === true ? <span className="model-tag model-tag-yes">{availableWord}</span> : null}
                  </div>
                </li>
              )
            })}
          </ul>,
              place.host
            )
          : null}
      </div>
      <Button onClick={onLoad} disabled={loading}>
        {loading ? <Spinner size={12} /> : <RefreshCw size={13} />} {t('settings.models.load')}
      </Button>
    </div>
  )
}
