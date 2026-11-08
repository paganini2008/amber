import clsx from 'clsx'
import { Crosshair, ExternalLink, Search, X } from 'lucide-react'
import { useMemo } from 'react'
import type { Occurrence, ReferenceQuery } from '@shared/protocol'
import { t } from '@/i18n'
import { KindIcon, kindText, objectKindText } from '@/lib/kinds'
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
    { label: t('references.goToDefinition', { name: label }), hint: 'F12', onSelect: () => void st.findReferences(query, { goToDefinition: true }) },
    { label: t('references.findReferences', { name: label }), hint: '⇧F12', onSelect: () => void st.findReferences(query) }
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
      label={t('references.findButton', { name: queryLabel(query), shortcut: isMac ? '⇧F12' : 'Shift+F12' })}
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
  const source = useStore((s) => (s.doc && !s.doc.edited ? s.doc.text : undefined))
  const p = snippetParts(o, source)
  return (
    <div role="listitem" className="flex items-center gap-2 pr-3 hover:bg-surface-hover">
      <button
        type="button"
        onClick={() => {
          if (index?.byId.has(o.statement)) selectStatement(o.statement, 'view', false)
          markRange({ from: o.range.start.offset, to: o.range.end.offset })
        }}
        className="flex min-w-0 flex-1 items-center gap-2.5 py-1 pl-6 text-left"
        title={o.via ? t('references.rowTitleVia', { access: markFor(o.access).label, via: o.via, line: p.lineNo }) : t('references.rowTitle', { access: markFor(o.access).label, line: p.lineNo })}
      >
        <AccessBadge access={o.access} />
        <span className="code w-10 shrink-0 text-right text-[12px] text-ink-faint tabular-nums">{p.lineNo}</span>
        <span className="code min-w-0 flex-1 truncate text-[12.5px] text-ink-muted">
          {p.before.trimStart()}
          <mark className="ref-hit">{p.match}</mark>
          {p.after}
        </span>
        {definition ? <span className="shrink-0 text-xs font-medium text-amber-ink">{t('references.definition')}</span> : null}
      </button>
      {o.via ? (
        <span className="via-badge" title={t('references.viaTitle', { via: o.via })}>
          {t('references.via')} <RoutineLink name={o.via} className="text-[12px]" />
        </span>
      ) : null}
    </div>
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
        <span className="text-ink">{t('references.header')}</span>
        <span className="code text-[13px] font-medium text-ink">{refs.result?.name ?? label}</span>
        {refs.result?.kind ? (
          <span className="text-xs text-ink-muted">{refs.result.kind === 'COLUMN' ? t('references.column') : objectKindText(refs.result.kind)}</span>
        ) : null}
        {grouped ? (
          <span className="text-xs text-ink-muted">{t('references.count', { places: grouped.total, statements: grouped.groups.length })}</span>
        ) : null}
        <span className="flex-1" />
        <span className="text-xs text-ink-faint">{t('references.shortcuts')}</span>
        <IconButton label={t('references.close')} className="h-6 w-6" onClick={close}>
          <X size={13} />
        </IconButton>
      </div>
      {refs.status === 'loading' ? (
        <p className="flex items-center gap-2 px-3 py-2 text-ink-muted">
          <Spinner size={12} /> {t('references.looking', { name: label })}
        </p>
      ) : refs.status === 'error' ? (
        <p className="px-3 py-2 text-risk-high">{refs.error}</p>
      ) : refs.status === 'idle' || !grouped ? (
        <p className="px-3 py-2 text-ink-muted">{t('references.idle')}</p>
      ) : grouped.total === 0 ? (
        <p className="px-3 py-2 text-ink-muted">{t('references.nothing', { name: label })}</p>
      ) : (
        <div role="list" aria-label={t('references.listAria', { name: label })} className="pb-1">
          {grouped.definition ? (
            <OccurrenceRow o={grouped.definition} definition />
          ) : (
            <p className="flex items-center gap-2 py-1.5 pr-3 pl-6 text-xs text-ink-muted">
              <ExternalLink size={12} /> {t('references.external')}
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
                  <span className="truncate font-medium text-ink">{s?.summary || (s ? kindText(s.kind) : t('references.statement', { number: g.statement + 1 }))}</span>
                  {s ? <span className="shrink-0">{t('common.line', { line: s.range.start.line })}</span> : null}
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

/**
 * The name of a routine defined in the script (a `via`), as a link to its definition (like F12).
 */
export function RoutineLink({ name, className }: { name: string; className?: string }): React.JSX.Element {
  const findReferences = useStore((s) => s.findReferences)
  return (
    <button
      type="button"
      className={clsx('code text-[12.5px] text-amber-ink hover:underline', className)}
      title={t('references.routineTitle', { name })}
      onClick={(e) => {
        e.stopPropagation()
        void findReferences({ object: name }, { goToDefinition: true })
      }}
      onContextMenu={onReferenceContextMenu({ object: name })}
    >
      {name}
    </button>
  )
}
