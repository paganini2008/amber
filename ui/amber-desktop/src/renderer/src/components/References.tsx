import clsx from 'clsx'
import { Crosshair, ExternalLink, Search, X } from 'lucide-react'
import { useMemo } from 'react'
import type { Occurrence, ReferenceQuery } from '@shared/protocol'
import { KindIcon, kindText } from '@/lib/kinds'
import { markFor } from '@/lib/lifecycle'
import { groupOccurrences, queryLabel, snippetParts, WRITE_LIKE } from '@/lib/references'
import { useStore, type ContextMenuItem } from '@/store'
import { IconButton, Spinner } from './ui'

const isMac = navigator.userAgent.includes('Mac')

/** Context-menu items for an object or column: go to definition, find references. */
export function referenceMenuItems(query: ReferenceQuery): ContextMenuItem[] {
  const st = useStore.getState()
  const label = queryLabel(query)
  return [
    { label: `Go to definition of ${label}`, hint: 'F12', onSelect: () => void st.findReferences(query, { goToDefinition: true }) },
    { label: `Find references to ${label}`, hint: '⇧F12', onSelect: () => void st.findReferences(query) }
  ]
}

/** Opens the reference menu at the pointer; use as an onContextMenu handler. */
export function onReferenceContextMenu(query: ReferenceQuery) {
  return (e: React.MouseEvent): void => {
    e.preventDefault()
    e.stopPropagation()
    useStore.getState().openContextMenu(e.clientX, e.clientY, referenceMenuItems(query))
  }
}

/** A small "Find references" button for object rows and headers. */
export function FindReferencesButton({ query, className }: { query: ReferenceQuery; className?: string }): React.JSX.Element {
  const findReferences = useStore((s) => s.findReferences)
  return (
    <IconButton
      label={`Find references to ${queryLabel(query)} (${isMac ? '⇧F12' : 'Shift+F12'})`}
      className={clsx('h-6 w-6', className)}
      onClick={(e) => {
        e.stopPropagation()
        void findReferences(query)
      }}
    >
      <Search size={13} />
    </IconButton>
  )
}

function AccessBadge({ access }: { access: string }): React.JSX.Element {
  const m = markFor(access)
  return (
    <span className={clsx('mark', WRITE_LIKE.has(access) ? 'mark-write' : m.cls === 'read' ? 'mark-read' : `mark-${m.cls}`)} title={m.label}>
      {access === 'WRITE' ? 'W' : m.letter}
    </span>
  )
}

function OccurrenceRow({ o, definition }: { o: Occurrence; definition?: boolean }): React.JSX.Element {
  const markRange = useStore((s) => s.markRange)
  const selectStatement = useStore((s) => s.selectStatement)
  const index = useStore((s) => s.index)
  const p = snippetParts(o)
  return (
    <button
      type="button"
      role="listitem"
      onClick={() => {
        if (index?.byId.has(o.statement)) selectStatement(o.statement, 'view', false)
        markRange({ from: o.range.start.offset, to: o.range.end.offset })
      }}
      className="flex w-full items-center gap-2.5 py-1 pr-3 pl-6 text-left hover:bg-surface-hover"
      title={`${markFor(o.access).label} · line ${p.lineNo}`}
    >
      <AccessBadge access={o.access} />
      <span className="code w-10 shrink-0 text-right text-[12px] text-ink-faint tabular-nums">{p.lineNo}</span>
      <span className="code min-w-0 flex-1 truncate text-[12.5px] text-ink-muted">
        {p.before.trimStart()}
        <mark className="ref-hit">{p.match}</mark>
        {p.after}
      </span>
      {definition ? <span className="shrink-0 text-xs font-medium text-amber-ink">definition</span> : null}
    </button>
  )
}

/** Bottom panel tab: every place the script touches an object or column, the definition first. */
export function ReferencesPanel(): React.JSX.Element {
  const refs = useStore((s) => s.references)
  const index = useStore((s) => s.index)
  const close = useStore((s) => s.closeReferences)
  const selectStatement = useStore((s) => s.selectStatement)
  const grouped = useMemo(() => (refs.result ? groupOccurrences(refs.result) : null), [refs.result])
  const label = refs.query ? queryLabel(refs.query) : ''

  return (
    <div className="text-sm">
      <div className="sticky top-0 z-[1] flex items-center gap-2 border-b border-line bg-surface px-3 py-1.5">
        <Crosshair size={13} className="text-ink-muted" />
        <span className="text-ink">References to</span>
        <span className="code text-[13px] font-medium text-ink">{refs.result?.name ?? label}</span>
        {refs.result?.kind ? <span className="text-xs text-ink-muted">{kindText(refs.result.kind)}</span> : null}
        {grouped ? (
          <span className="text-xs text-ink-muted">
            {grouped.total} {grouped.total === 1 ? 'place' : 'places'}
            {grouped.groups.length ? ` in ${grouped.groups.length} ${grouped.groups.length === 1 ? 'statement' : 'statements'}` : ''}
          </span>
        ) : null}
        <span className="flex-1" />
        <span className="text-xs text-ink-faint">F12 go to definition · ⇧F12 find references</span>
        <IconButton label="Close references" className="h-6 w-6" onClick={close}>
          <X size={13} />
        </IconButton>
      </div>
      {refs.status === 'loading' ? (
        <p className="flex items-center gap-2 px-3 py-2 text-ink-muted">
          <Spinner size={12} /> Looking for {label}
        </p>
      ) : refs.status === 'error' ? (
        <p className="px-3 py-2 text-risk-high">{refs.error}</p>
      ) : refs.status === 'idle' || !grouped ? (
        <p className="px-3 py-2 text-ink-muted">
          Before you change a table or column, see everywhere it is used: put the cursor on a name and press F12 or Shift+F12, or right-click it.
        </p>
      ) : grouped.total === 0 ? (
        <p className="px-3 py-2 text-ink-muted">Nothing named {label} was found in this script.</p>
      ) : (
        <div role="list" aria-label={`References to ${label}`} className="pb-1">
          {grouped.definition ? (
            <OccurrenceRow o={grouped.definition} definition />
          ) : (
            <p className="flex items-center gap-2 py-1.5 pr-3 pl-6 text-xs text-ink-muted">
              <ExternalLink size={12} /> Defined outside this script: no CREATE or WITH for it here.
            </p>
          )}
          {grouped.groups.map((g) => {
            const s = index?.byId.get(g.statement)
            return (
              <div key={g.statement}>
                <button
                  type="button"
                  onClick={() => selectStatement(g.statement)}
                  className="flex w-full items-center gap-2 px-3 pt-1.5 pb-0.5 text-left text-xs text-ink-muted hover:text-ink"
                >
                  {s ? <KindIcon kind={s.kind} size={12} /> : null}
                  <span className="truncate font-medium text-ink">{s?.summary || (s ? kindText(s.kind) : `Statement ${g.statement + 1}`)}</span>
                  {s ? <span className="shrink-0">line {s.range.start.line}</span> : null}
                </button>
                {g.items.map((o) => (
                  <OccurrenceRow key={o.range.start.offset} o={o} />
                ))}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
