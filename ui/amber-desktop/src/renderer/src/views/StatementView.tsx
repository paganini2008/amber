import clsx from 'clsx'
import { ChevronLeft, ChevronRight, CornerDownRight, MousePointerClick, Search } from 'lucide-react'
import type { Ref, Statement } from '@shared/protocol'
import { kindLabel, roleLabel } from '@/lib/dataflow'
import { KindIcon, accessText, categoryText, confidenceText, isMuted, kindText } from '@/lib/kinds'
import { markFor } from '@/lib/lifecycle'
import { ancestors, lineLabel, type AnalysisIndex } from '@/lib/model'
import { t } from '@/i18n'
import { useStore } from '@/store'
import { IconButton } from '@/components/ui'
import { ColumnMapping, ObjectColumns } from './ColumnMapping'
import { StatementFindings } from '@/components/FindingDetails'
import { StructureSummary } from './StructureView'
import { FindReferencesButton, onReferenceContextMenu, RoutineLink } from '@/components/References'
import { splitRefs } from '@/lib/via'

const ACCESS_ORDER = ['CREATE', 'ALTER', 'INSERT', 'UPDATE', 'MERGE', 'DELETE', 'TRUNCATE', 'DROP', 'READ', 'EXECUTE']

export function groupRefs(refs: Ref[]): [string, Ref[]][] {
  const groups = new Map<string, Ref[]>()
  for (const r of refs) {
    if (r.kind === 'CTE') continue
    const list = groups.get(r.access) ?? []
    if (!list.some((x) => x.key === r.key)) list.push(r)
    groups.set(r.access, list)
  }
  return [...groups.entries()].sort((a, b) => rank(a[0]) - rank(b[0]))
}

function rank(access: string): number {
  const i = ACCESS_ORDER.indexOf(access)
  return i < 0 ? 99 : i
}

/** Center view: the selected statement (or object) in full. */
export function StatementView(): React.JSX.Element {
  const index = useStore((s) => s.index)!
  const selection = useStore((s) => s.selection)
  const selectStatement = useStore((s) => s.selectStatement)

  if (selection?.type === 'object') return <ObjectDetails objectKey={selection.key} />
  const statement = selection?.type === 'statement' ? index.byId.get(selection.id) : undefined
  if (!statement) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-2 text-sm text-ink-muted">
        <MousePointerClick size={20} />
        {t('statement.empty')}
      </div>
    )
  }
  const total = index.order.length
  return (
    <div className="h-full overflow-auto">
      <div className="mx-auto max-w-[880px] px-8 py-6">
        <div className="mb-3 flex items-center gap-2">
          <Breadcrumb index={index} statement={statement} />
          <div className="ml-auto flex items-center gap-1 text-xs text-ink-muted">
            {t('statement.stepOf', { step: statement.id + 1, total })}
            <IconButton label={t('statement.previous')} disabled={statement.id === 0} onClick={() => selectStatement(statement.id - 1)}>
              <ChevronLeft size={15} />
            </IconButton>
            <IconButton label={t('statement.next')} disabled={statement.id >= total - 1} onClick={() => selectStatement(statement.id + 1)}>
              <ChevronRight size={15} />
            </IconButton>
          </div>
        </div>
        <StatementBody statement={statement} index={index} />
      </div>
    </div>
  )
}

function Breadcrumb({ index, statement }: { index: AnalysisIndex; statement: Statement }): React.JSX.Element | null {
  const selectStatement = useStore((s) => s.selectStatement)
  const path = ancestors(index, statement.id)
  if (!path.length) return <span className="text-xs text-ink-muted">{t('statement.topLevel')}</span>
  return (
    <nav aria-label={t('statement.containing')} className="flex min-w-0 flex-wrap items-center gap-1 text-xs text-ink-muted">
      {path.map((p, i) => (
        <span key={p.id} className="flex items-center gap-1">
          {i > 0 ? <ChevronRight size={11} /> : null}
          <button type="button" className="hover:text-ink hover:underline" onClick={() => selectStatement(p.id)}>
            {p.name ? `${kindText(p.kind)} ${p.name}` : kindText(p.kind)}
          </button>
        </span>
      ))}
    </nav>
  )
}

export function StatementBody({ statement, index, compact }: { statement: Statement; index: AnalysisIndex; compact?: boolean }): React.JSX.Element {
  const selectStatement = useStore((s) => s.selectStatement)
  const { direct, via: viaGroups } = splitRefs(statement.refs)
  const groups = groupRefs(direct)
  return (
    <div className={clsx('selectable', compact ? 'space-y-4' : 'space-y-6')}>
      <div>
        <div className="flex items-center gap-2 text-sm text-ink-muted">
          <KindIcon kind={statement.kind} size={compact ? 14 : 16} />
          {kindText(statement.kind)}
          <span aria-hidden>·</span>
          {lineLabel(statement)}
        </div>
        <h2 className={clsx('mt-1 font-semibold tracking-[-0.01em] [overflow-wrap:anywhere] text-ink', compact ? 'text-lg' : 'text-2xl', isMuted(statement.confidence) && 'italic')}>
          {statement.summary || kindText(statement.kind)}
        </h2>
      </div>

      <dl className={clsx('grid gap-x-6 gap-y-2 text-sm', compact ? 'grid-cols-[96px_1fr]' : 'grid-cols-[140px_1fr]')}>
        <dt className="text-ink-muted">{t('statement.category')}</dt>
        <dd>{['DML', 'DDL', 'DCL', 'TCL'].includes(statement.category) ? statement.category : categoryText(statement.category)}</dd>
        <dt className="text-ink-muted">{t('statement.confidence')}</dt>
        <dd className="flex items-center gap-2">
          <span className={`conf-line conf-${statement.confidence} text-ink-muted`} />
          {confidenceText(statement.confidence)}
        </dd>
        {statement.name ? (
          <>
            <dt className="text-ink-muted">{t('statement.defines')}</dt>
            <dd className="code">{statement.name}</dd>
          </>
        ) : null}
        {statement.ctes.length ? (
          <>
            <dt className="text-ink-muted">CTEs</dt>
            <dd className="flex flex-wrap gap-1.5">
              {statement.ctes.map((c) => (
                <span key={c} className="code rounded-[3px] border border-line px-1.5 text-[12px]">
                  {c}
                </span>
              ))}
            </dd>
          </>
        ) : null}
      </dl>

      {groups.length || viaGroups.length ? (
        <section>
          <h3 className="mb-2 text-base font-semibold">{t('statement.objects')}</h3>
          <div className="space-y-3">
            {groups.map(([access, refs]) => (
              <AccessGroup key={access} access={access} refs={refs} index={index} />
            ))}
            {viaGroups.map((g) => (
              <div key={g.routine} className="rounded-[6px] border border-dashed border-line-strong px-3 py-2">
                <div className="mb-1.5 flex flex-wrap items-center gap-x-1.5 text-sm text-ink-muted">
                  <CornerDownRight size={13} aria-hidden />
                  {t('statement.throughCall')} <RoutineLink name={g.routine} />
                </div>
                <div className="space-y-2">
                  {groupRefs(g.refs).map(([access, refs]) => (
                    <AccessGroup key={access} access={access} refs={refs} index={index} via={g.routine} />
                  ))}
                </div>
              </div>
            ))}
          </div>
        </section>
      ) : null}

      {compact ? null : <StatementFindings statementId={statement.id} />}
      <StructureSummary statement={statement} compact={compact} />
      <ColumnMapping statementId={statement.id} compact={compact} />

      {statement.children.length ? (
        <section>
          <h3 className="mb-2 text-base font-semibold">{t('statement.contains', { count: statement.children.length })}</h3>
          <ul className="divide-y divide-line rounded-[6px] border border-line">
            {statement.children.map((c) => (
              <li key={c.id}>
                <button
                  type="button"
                  onClick={() => selectStatement(c.id)}
                  className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm hover:bg-surface-hover"
                >
                  <KindIcon kind={c.kind} className="text-ink-muted" />
                  <span className="min-w-0 flex-1 truncate">{c.summary || kindText(c.kind)}</span>
                  {c.branch ? <span className="text-xs text-ink-muted">{c.branch}</span> : null}
                  <span className="code text-xs text-ink-faint">{c.range.start.line}</span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  )
}

export function ObjectDetails({ objectKey, compact }: { objectKey: string; compact?: boolean }): React.JSX.Element {
  const index = useStore((s) => s.index)!
  const selectStatement = useStore((s) => s.selectStatement)
  const l = index.lifecycleByKey.get(objectKey)
  if (!l) return <p className="p-4 text-sm text-ink-muted">{t('statement.object.untracked')}</p>
  const body = (
    <div className={clsx('selectable', compact ? 'space-y-4' : 'space-y-6')}>
      <div>
        <div className="text-sm text-ink-muted">
          {kindLabel(l)} · {roleLabel(l.role)}
        </div>
        <div className="mt-1 flex items-start gap-2">
          <h2 className={clsx('code min-w-0 font-semibold break-all text-ink', compact ? 'text-lg' : 'text-2xl')}>{l.name}</h2>
          <button
            type="button"
            onClick={() => void useStore.getState().findReferences({ object: l.name })}
            className="mt-0.5 flex shrink-0 items-center gap-1 rounded-[5px] border border-line px-1.5 py-0.5 text-xs text-ink hover:border-amber hover:bg-surface-hover"
            title={t('statement.object.findHint')}
          >
            <Search size={12} /> {t('statement.object.find')}
          </button>
        </div>
        <p className="mt-1 text-sm text-ink-muted">
          {t('statement.object.facts', {
            origin: l.createdInScript ? 'created' : 'existing',
            dropped: l.droppedInScript ? 'yes' : 'no',
            count: l.events.length
          })}
        </p>
      </div>
      <ObjectColumns objectKey={l.key} />
      <section>
        <h3 className="mb-2 text-base font-semibold">{t('statement.object.lifecycle')}</h3>
        <ol className="relative space-y-0.5 border-l border-line pl-4">
          {l.events.map((e, i) => {
            const s = index.byId.get(e.step)
            const m = markFor(e.access)
            return (
              <li key={`${e.step}-${i}`} className="relative">
                <span className="absolute top-2 -left-[19px] h-1.5 w-1.5 rounded-full bg-line-strong" aria-hidden />
                <button
                  type="button"
                  onClick={() => selectStatement(e.step)}
                  className="flex w-full items-center gap-2 rounded-[4px] px-1.5 py-1 text-left text-sm hover:bg-surface-hover"
                >
                  <span className={`mark mark-${m.cls}`}>{m.letter}</span>
                  <span className="w-16 shrink-0 text-ink-muted">{m.label}</span>
                  <span className="min-w-0 flex-1 truncate">{s?.summary ?? t('common.step', { step: e.step + 1 })}</span>
                  {e.via ? <span className="via-badge">{t('statement.via', { routine: e.via })}</span> : null}
                  <span className="code text-xs text-ink-faint">{e.range.start.line}</span>
                </button>
              </li>
            )
          })}
        </ol>
      </section>
    </div>
  )
  if (compact) return body
  return (
    <div className="h-full overflow-auto">
      <div className="mx-auto max-w-[880px] px-8 py-6">{body}</div>
    </div>
  )
}

/** Objects of one access ("Inserts into"), each with its role; `via` marks objects reached through a call. */
function AccessGroup({ access, refs, index, via }: { access: string; refs: Ref[]; index: AnalysisIndex; via?: string }): React.JSX.Element {
  const select = useStore((s) => s.select)
  return (
    <div>
      <div className="mb-1 flex items-center gap-1.5 text-sm text-ink-muted">
        <span className={`mark mark-${markFor(access).cls}`}>{markFor(access).letter}</span>
        {accessText(access, true)}
      </div>
      <ul className="space-y-0.5 pl-6">
        {refs.map((r) => {
          const l = index.lifecycleByKey.get(r.key)
          return (
            <li key={r.key} className="flex min-w-0 flex-wrap items-center gap-x-2 text-sm">
              <button
                type="button"
                className="code min-w-0 text-left text-[12.5px] [overflow-wrap:anywhere] text-ink hover:text-amber-ink hover:underline disabled:hover:no-underline"
                onClick={() => select({ type: 'object', key: r.key })}
                onContextMenu={onReferenceContextMenu({ object: r.name })}
                disabled={!l}
              >
                {r.name}
              </button>
              <span className="text-xs text-ink-muted">
                {kindLabel(r)}
                {l ? ` · ${roleLabel(l.role)}` : ''}
                {via ? ` · ${t('statement.via', { routine: via })}` : ''}
              </span>
              <FindReferencesButton query={{ object: r.name }} className="opacity-60 hover:opacity-100" />
              {r.confidence !== 'CERTAIN' && r.confidence !== 'INFERRED' ? (
                <span className={`conf-line conf-${r.confidence} text-ink-muted`} title={confidenceText(r.confidence)} />
              ) : null}
            </li>
          )
        })}
      </ul>
    </div>
  )
}
