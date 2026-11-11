import clsx from 'clsx'
import { ChevronDown, ChevronRight, Sparkles } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { AiProviderId } from '@shared/api'
import type { AiBuildContextParams, AiCheckParams, AiCheckResult, AiContext } from '@shared/protocol'
import { getLanguage, t } from '@/i18n'
import { appendDelta, checkLabelText, conversationMessages, estimateTokens, parseSuggestions, sliceRange, targetStatement, unmaskText, type AiSuggestion } from '@/lib/ai'
import { api, describeError, isNotAvailable, newRequestId } from '@/lib/api'
import type { AnalysisIndex } from '@/lib/model'
import { analyzeParams, currentMetadata, metadataParams, useStore } from '@/store'
import { ObjectDetails, StatementBody } from '@/views/StatementView'
import { CodeBlock } from './CodeBlock'
import { FindingCard, StatementFindings } from './FindingDetails'
import { OptimizedForStatement } from './Optimized'
import { Button, NotAvailable, Select, Spinner } from './ui'

export function DetailsPanel(): React.JSX.Element {
  const index = useStore((s) => s.index)
  const selection = useStore((s) => s.selection)
  const doc = useStore((s) => s.doc)
  const finding = useStore((s) => (s.selectedFinding !== null ? s.analysis?.findings?.[s.selectedFinding] : undefined))
  const statement = selection?.type === 'statement' ? index?.byId.get(selection.id) : undefined
  const snippet = useMemo(() => {
    if (!statement || !doc || doc.edited) return null
    const text = doc.text.slice(statement.range.start.offset, statement.range.end.offset)
    const lines = text.split('\n')
    return { text: lines.slice(0, 14).join('\n'), more: Math.max(0, lines.length - 14) }
  }, [statement, doc])

  return (
    <aside className="flex h-full min-h-0 flex-col bg-surface" aria-label={t('details.title')}>
      <header className="chrome flex h-9 shrink-0 items-center border-b border-line px-3">
        <h2 className="text-sm font-medium">{t('details.title')}</h2>
      </header>
      <div className="min-h-0 flex-1 overflow-auto px-4 py-4">
        {!index ? (
          <p className="text-sm text-ink-muted">{t('details.emptyNoScript')}</p>
        ) : statement ? (
          <div className="space-y-4">
            {finding ? <FindingCard finding={finding} /> : null}
            <StatementBody statement={statement} index={index} compact />
            <StatementFindings statementId={statement.id} />
            <OptimizedForStatement statementId={statement.id} />
            {snippet ? (
              <section>
                <h3 className="mb-1.5 text-sm font-semibold">{t('details.source')}</h3>
                <pre className="code selectable overflow-x-auto rounded-[5px] border border-line bg-canvas p-2 text-[12px] leading-[18px] whitespace-pre text-ink">
                  {snippet.text}
                  {snippet.more ? `\n${t('details.moreLines', { count: snippet.more })}` : ''}
                </pre>
              </section>
            ) : null}
          </div>
        ) : finding ? (
          <FindingCard finding={finding} />
        ) : selection?.type === 'object' ? (
          <ObjectDetails objectKey={selection.key} compact />
        ) : (
          <p className="text-sm text-ink-muted">{t('details.emptyHint')}</p>
        )}
      </div>
    </aside>
  )
}

type Phase =
  | { step: 'idle' }
  | { step: 'building' }
  | { step: 'unavailable' }
  | { step: 'error'; message: string }
  | { step: 'preview'; run: Run }
  | { step: 'chat'; run: Run; rounds: Round[] }

/** One context build and everything that follows from it. */
interface Run {
  context: Omit<AiContext, 'mask'>
  /** The statement the context was built for (statement scope), or null for the whole script. */
  scopeStatement: number | null
  /** The text the analysis ranges point into, captured when the context was built. */
  text: string
  /** Analyze params, so `ai.check` gets the same dialect and metadata. */
  params: AiBuildContextParams
  /** The notes that went into this context. */
  notes: string
}

/** One question and answer of the conversation. The first round sends the engine's context. */
interface Round {
  /** What the developer wrote (null for the first round, which sends the context). */
  question: string | null
  /** The user message as sent, masked. */
  prompt: string
  answer: string
  status: 'streaming' | 'checking' | 'done' | 'error'
  requestId: string
  suggestions: SuggestionView[] | null
  error?: string
}

type CheckOutcome = { ok: true; result: AiCheckResult } | { ok: false; message: string }

interface SuggestionView {
  suggestion: AiSuggestion
  statement: number | null
  check: CheckOutcome | null
}

/**
 * AI suggestions (optional, advisory), shown in the Suggestions view under the findings and their fixes.
 * The context comes from the engine (`ai.buildContext`), never
 * from the raw script; every rewritten SQL goes through `ai.check`. Provider calls run in main.
 * The alias map of masked names stays in this component and is never sent to the provider.
 */
export function AiPanel(): React.JSX.Element {
  const settings = useStore((s) => s.settings)
  const selection = useStore((s) => s.selection)
  const analysis = useStore((s) => s.analysis)
  const index = useStore((s) => s.index)
  const doc = useStore((s) => s.doc)
  const dialect = useStore((s) => s.dialect)
  const usedFallback = useStore((s) => s.usedFallback)
  const openDialog = useStore((s) => s.openDialog)
  const [scope, setScope] = useState<'statement' | 'script'>('statement')
  const [phase, setPhase] = useState<Phase>({ step: 'idle' })
  const [mask, setMask] = useState<Record<string, string>>({})
  const [confirmed, setConfirmed] = useState(false)
  // Notes are kept per document (by its load token) while the panel lives.
  const [notesByDoc, setNotesByDoc] = useState<Record<number, string>>({})
  const notes = doc ? (notesByDoc[doc.token] ?? '') : ''
  const outputRef = useRef('')
  /** The chat request being streamed; deltas of any other request are ignored. */
  const streamRef = useRef<string | null>(null)
  /** Bumped whenever the panel resets, so late results of an older run are dropped. */
  const runToken = useRef(0)
  const provider = settings?.activeAiProvider ?? null
  const external = provider === 'openai'
  const selectedStatement = selection?.type === 'statement' ? index?.byId.get(selection.id) : undefined

  useEffect(() => {
    return api().ai.onStream((event) => {
      if (event.requestId !== streamRef.current) return
      // Append OUTSIDE the state updater: React may run updaters twice (StrictMode), which doubled every
      // streamed fragment ("selectselect") and broke JSON parsing. Keep it this way.
      if (event.type === 'delta') outputRef.current = appendDelta(outputRef.current, event.text)
      const answer = outputRef.current
      setPhase((p) => {
        if (p.step !== 'chat') return p
        const last = p.rounds[p.rounds.length - 1]
        if (!last || last.requestId !== event.requestId || last.status !== 'streaming') return p
        let next: Round
        if (event.type === 'delta') {
          next = { ...last, answer }
        } else if (event.type === 'error') {
          next = { ...last, status: 'error', error: event.message }
        } else return p
        return { ...p, rounds: [...p.rounds.slice(0, -1), next] }
      })
    })
  }, [])

  // Requests still streaming from the provider; cancelled when the conversation is abandoned so
  // nothing keeps running (or costing tokens) for an answer nobody will see.
  const streaming = useRef<Set<string>>(new Set())
  useEffect(() => {
    if (phase.step !== 'chat') return
    for (const r of phase.rounds) {
      if (r.status === 'streaming') streaming.current.add(r.requestId)
      else streaming.current.delete(r.requestId)
    }
  }, [phase])
  useEffect(() => {
    const live = streaming.current
    return () => {
      for (const id of live) void api().ai.cancel(id)
    }
  }, [])

  useEffect(() => {
    runToken.current += 1
    for (const id of streaming.current) void api().ai.cancel(id)
    streaming.current.clear()
    setPhase({ step: 'idle' })
    setMask({})
  }, [analysis, scope, provider])

  const buildContext = async (): Promise<void> => {
    if (!doc || !index) return
    const token = ++runToken.current
    const scopeStatement = scope === 'statement' && selection?.type === 'statement' ? selection.id : null
    if (scope === 'statement' && scopeStatement === null) return
    setPhase({ step: 'building' })
    const params: AiBuildContextParams = {
      ...analyzeParams(doc, dialect, settings, usedFallback, currentMetadata(useStore.getState())),
      statements: scopeStatement === null ? [] : [scopeStatement],
      tokenBudget: settings?.ai.maxContextTokens,
      mask: provider ? settings?.ai[provider].maskIdentifiers : true,
      ...(notes.trim() ? { notes: notes.trim() } : {})
    }
    const r = await api().engine.call<AiContext>('ai.buildContext', params, newRequestId('ai'))
    if (token !== runToken.current) return
    if (!r.ok) {
      setPhase(isNotAvailable(r.error) ? { step: 'unavailable' } : { step: 'error', message: describeError(r.error) })
      return
    }
    const { mask: aliases, ...context } = r.value
    setMask(aliases ?? {})
    setConfirmed(false)
    setPhase({
      step: 'preview',
      run: {
        context: { ...context, trimmed: context.trimmed ?? [], statements: context.statements ?? [] },
        scopeStatement,
        text: doc.text,
        params,
        notes: notes.trim()
      }
    })
  }

  const check = async (run: Run, suggestion: AiSuggestion): Promise<SuggestionView> => {
    const statement = targetStatement(suggestion, (id) => Boolean(index?.byId.has(id)), run.scopeStatement)
    if (!suggestion.rewrittenSql) return { suggestion, statement, check: null }
    const s = statement !== null ? index?.byId.get(statement) : undefined
    if (!s) return { suggestion, statement, check: { ok: false, message: t('ai.notChecked.noStatement') } }
    const params: AiCheckParams = {
      dialect: run.params.dialect,
      original: sliceRange(run.text, s.range),
      rewritten: suggestion.rewrittenSql,
      mask,
      ...metadataParams(run.params)
    }
    const c = await api().engine.call<AiCheckResult>('ai.check', params, newRequestId('check'))
    if (c.ok) return { suggestion, statement, check: { ok: true, result: { ...c.value, differences: c.value.differences ?? [], diagnostics: c.value.diagnostics ?? [] } } }
    return {
      suggestion,
      statement,
      check: { ok: false, message: isNotAvailable(c.error) ? t('ai.notChecked.unavailable') : t('ai.notChecked.error', { reason: describeError(c.error) }) }
    }
  }

  /** Sends the next user message of the conversation and checks what comes back. */
  const ask = async (run: Run, previous: Round[], question: string | null, prompt: string, p: AiProviderId): Promise<void> => {
    const token = runToken.current
    const requestId = newRequestId('chat')
    outputRef.current = ''
    streamRef.current = requestId
    const round: Round = { question, prompt, answer: '', status: 'streaming', requestId, suggestions: null }
    setPhase({ step: 'chat', run, rounds: [...previous, round] })
    const messages = conversationMessages(run.context.system, previous, prompt)
    const r = await api().ai.chat(p, messages, requestId)
    if (token !== runToken.current) return
    // The reply carries the complete answer; streamed fragments may still be in flight.
    const answer = r.ok && typeof r.value === 'string' ? r.value : outputRef.current
    outputRef.current = answer
    const update = (patch: Partial<Round>): void =>
      setPhase((ph) => (ph.step === 'chat' ? { ...ph, rounds: ph.rounds.map((x) => (x.requestId === requestId ? { ...x, ...patch } : x)) } : ph))
    if (!r.ok) {
      update({ status: 'error', answer, error: r.error.message })
      return
    }
    const parsed = parseSuggestions(answer)
    if (!parsed) {
      update({ status: 'done', answer, suggestions: null })
      return
    }
    update({ status: 'checking', answer })
    const suggestions = await Promise.all(parsed.map((s) => check(run, s)))
    if (token !== runToken.current) return
    update({ status: 'done', suggestions })
  }

  const endpoint = external ? settings?.ai.openai.baseUrl : settings?.ai.ollama.baseUrl
  const stale = Boolean(doc?.edited)
  const busy = phase.step === 'building' || (phase.step === 'chat' && phase.rounds.some((r) => r.status === 'streaming' || r.status === 'checking'))
  const notesChanged = phase.step === 'preview' && phase.run.notes !== notes.trim()

  return (
    <section className="rounded-[8px] border border-line bg-surface" aria-label={t('ai.title')}>
      <div className="flex h-10 w-full items-center gap-2 overflow-hidden border-b border-line px-4 text-sm">
        <Sparkles size={14} className="text-amber-ink" />
        <h3 className="shrink-0 font-medium whitespace-nowrap">{t('ai.title')}</h3>
        <span className="ml-auto flex min-w-0 items-center gap-1 text-xs text-ink-muted" title={provider ? `${provider === 'ollama' ? 'Ollama' : t('ai.openaiCompatible')} · ${settings?.ai[provider].model || t('ai.noModel')}` : undefined}>
          {provider ? (
            <>
              <span className="min-w-0 truncate">{provider === 'ollama' ? 'Ollama' : t('ai.openaiCompatible')}</span>
              <span aria-hidden>·</span>
              <span className={clsx('code truncate text-[12px]', settings?.ai[provider].model ? 'text-ink' : 'text-risk-high')}>{settings?.ai[provider].model || t('ai.noModel')}</span>
            </>
          ) : (
            t('ai.off')
          )}
        </span>
      </div>
      <div className="space-y-3 px-4 py-3 text-sm">
          <ProviderSwitch provider={provider} settings={settings} onSettings={() => openDialog('settings', 'ai')} />
          {!provider ? (
            <div className="text-ink-muted">
              {t('ai.offText')}
              <div className="mt-2">
                <Button size="sm" onClick={() => openDialog('settings', 'ai')}>
                  {t('ai.setUp')}
                </Button>
              </div>
            </div>
          ) : !analysis || analysis.status === 'FAILED' ? (
            <p className="text-ink-muted">{t('ai.analyzeFirst')}</p>
          ) : (
            <>
              <div className="flex items-center gap-2">
                <Select
                  label={t('ai.scope')}
                  value={scope}
                  onChange={(v) => setScope(v as 'statement' | 'script')}
                  options={[
                    { value: 'statement', label: t('ai.scopeStatement') },
                    { value: 'script', label: t('ai.scopeScript') }
                  ]}
                  className="w-44"
                />
                <Button size="sm" onClick={() => void buildContext()} disabled={busy || stale || (scope === 'statement' && !selectedStatement)}>
                  {phase.step === 'building' ? <Spinner size={12} /> : null}
                  {phase.step === 'chat' ? t('ai.newConversation') : t('ai.previewContext')}
                </Button>
              </div>
              {stale ? <p className="text-xs text-ink-muted">{t('ai.stale')}</p> : null}
              {scope === 'statement' && !selectedStatement && !stale ? <p className="text-xs text-ink-muted">{t('ai.selectStatement')}</p> : null}
              {phase.step !== 'chat' ? (
                <div>
                  <label htmlFor="ai-notes" className="mb-1 block text-xs font-medium text-ink">
                    {t('ai.notesLabel')}
                  </label>
                  <textarea
                    id="ai-notes"
                    rows={3}
                    value={notes}
                    placeholder={t('ai.notesPlaceholder')}
                    onChange={(e) => doc && setNotesByDoc({ ...notesByDoc, [doc.token]: e.target.value })}
                    className="selectable w-full resize-y rounded-[5px] border border-line bg-surface px-2 py-1.5 text-[13px] leading-5 text-ink outline-none placeholder:text-ink-faint focus:border-amber"
                  />
                  <p className="text-xs text-ink-muted">{t('ai.notesHint')}</p>
                </div>
              ) : null}
              {phase.step === 'unavailable' ? (
                <NotAvailable feature={t('ai.unavailableFeature')}>
                  {t('ai.unavailableText')}
                </NotAvailable>
              ) : null}
              {phase.step === 'error' ? <p className="text-risk-high">{phase.message}</p> : null}
              {phase.step === 'preview' ? (
                <ContextPreview
                  run={phase.run}
                  endpoint={endpoint ?? ''}
                  masked={Object.keys(mask).length}
                  external={external}
                  confirmed={confirmed}
                  notesChanged={notesChanged}
                  onConfirm={setConfirmed}
                  onSend={() => void ask(phase.run, [], null, phase.run.context.user, provider)}
                />
              ) : null}
              {phase.step === 'chat' ? (
                <Conversation
                  run={phase.run}
                  rounds={phase.rounds}
                  mask={mask}
                  index={index}
                  onFollowUp={(question, prompt) => void ask(phase.run, phase.rounds, question, prompt, provider)}
                />
              ) : null}
            </>
          )}
      </div>
    </section>
  )
}

function Conversation({
  run,
  rounds,
  mask,
  index,
  onFollowUp
}: {
  run: Run
  rounds: Round[]
  mask: Record<string, string>
  index: AnalysisIndex | null
  onFollowUp: (question: string, prompt: string) => void
}): React.JSX.Element {
  const busy = rounds.some((r) => r.status === 'streaming' || r.status === 'checking')
  const tokens = estimateTokens([{ content: run.context.system }, ...rounds.flatMap((r) => [{ content: r.prompt }, { content: r.answer }])])
  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2 text-xs text-ink-muted">
        <span className="rounded-[3px] border border-line px-1">{t('ai.aiGenerated')}</span>
        <span>{t('ai.conversationStats', { rounds: rounds.length, tokens })}</span>
      </div>
      {rounds.map((r, i) => (
        <RoundView key={r.requestId} round={r} number={i + 1} latest={i === rounds.length - 1} mask={mask} index={index} />
      ))}
      {!busy ? <FollowUpBox mask={mask} onSend={onFollowUp} /> : null}
      <p className="text-xs text-ink-muted">{t('ai.neverRuns')}</p>
    </div>
  )
}

function RoundView({ round, number, latest, mask, index }: { round: Round; number: number; latest: boolean; mask: Record<string, string>; index: AnalysisIndex | null }): React.JSX.Element {
  const [expanded, setExpanded] = useState(latest)
  const [wasLatest, setWasLatest] = useState(latest)
  // Earlier rounds fold away when a new one starts; the reader can open them again.
  if (wasLatest !== latest) {
    setWasLatest(latest)
    setExpanded(latest)
  }
  const title = round.question ? t('ai.roundYou', { question: round.question }) : t('ai.roundContext')
  const count = round.suggestions?.length
  return (
    <div className="rounded-[6px] border border-line">
      <button type="button" className="flex w-full items-center gap-2 px-2.5 py-1.5 text-left hover:bg-surface-hover" onClick={() => setExpanded(!expanded)} aria-expanded={expanded}>
        {expanded ? <ChevronDown size={13} className="shrink-0 text-ink-muted" /> : <ChevronRight size={13} className="shrink-0 text-ink-muted" />}
        <span className="shrink-0 text-xs text-ink-muted">{t('ai.round', { number })}</span>
        <span className="min-w-0 flex-1 truncate text-sm text-ink">{title}</span>
        {round.status === 'streaming' ? (
          <span className="flex shrink-0 items-center gap-1 text-xs text-ink-muted">
            <Spinner size={11} /> {t('ai.receiving')}
          </span>
        ) : round.status === 'checking' ? (
          <span className="flex shrink-0 items-center gap-1 text-xs text-ink-muted">
            <Spinner size={11} /> {t('ai.checking')}
          </span>
        ) : count !== undefined ? (
          <span className="shrink-0 text-xs text-ink-muted">{t('ai.suggestionsCount', { count })}</span>
        ) : null}
      </button>
      {expanded ? (
        <div className="space-y-2 border-t border-line px-2.5 py-2">
          {round.status === 'streaming' ? (
            <div className="flex items-center gap-2 text-xs text-ink-muted">
              <button type="button" className="underline" onClick={() => void api().ai.cancel(round.requestId)}>
                {t('common.cancel')}
              </button>
            </div>
          ) : null}
          {round.status === 'error' ? <p className="text-risk-high">{round.error}</p> : null}
          {round.status === 'done' && round.suggestions ? (
            round.suggestions.length ? (
              round.suggestions.map((v, i) => <SuggestionCard key={i} view={v} mask={mask} index={index} />)
            ) : (
              <p className="text-ink-muted">{t('ai.noChanges')}</p>
            )
          ) : null}
          {round.status === 'done' && !round.suggestions ? (
            <p className="text-xs text-ink-muted">{t('ai.badJson')}</p>
          ) : null}
          {round.status === 'done' && round.suggestions ? (
            <details className="text-xs">
              <summary className="cursor-default text-ink-muted hover:text-ink">{t('ai.modelResponse')}</summary>
              <pre className="code selectable mt-1 max-h-60 overflow-auto rounded-[5px] border border-line bg-canvas p-2 text-[12.5px] whitespace-pre-wrap">
                {unmaskText(round.answer, mask)}
              </pre>
            </details>
          ) : round.answer ? (
            <pre className="code selectable max-h-60 overflow-auto rounded-[5px] border border-line bg-canvas p-2 text-[12.5px] whitespace-pre-wrap">{unmaskText(round.answer, mask)}</pre>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}

/** A remark or new question in the same conversation, masked with the context's aliases before it is sent. */
function FollowUpBox({ mask, onSend }: { mask: Record<string, string>; onSend: (question: string, prompt: string) => void }): React.JSX.Element {
  const [text, setText] = useState('')
  const [masked, setMasked] = useState<{ source: string; text: string } | null>(null)
  const [sending, setSending] = useState(false)
  const masking = Object.keys(mask).length > 0
  const trimmed = text.trim()

  useEffect(() => {
    if (!masking || !trimmed) return
    const timer = setTimeout(() => {
      void api()
        .engine.call<{ text: string }>('ai.maskText', { text: trimmed, mask }, newRequestId('mask'))
        .then((r) => setMasked({ source: trimmed, text: r.ok ? r.value.text : trimmed }))
    }, 350)
    return () => clearTimeout(timer)
  }, [trimmed, mask, masking])

  const send = async (): Promise<void> => {
    if (!trimmed) return
    let prompt = trimmed
    if (masking) {
      setSending(true)
      const r = await api().engine.call<{ text: string }>('ai.maskText', { text: trimmed, mask }, newRequestId('mask'))
      setSending(false)
      if (!r.ok) {
        useStore.getState().toast(t('ai.maskFailed', { reason: describeError(r.error) }), 'error')
        return
      }
      prompt = r.value.text
    }
    setText('')
    setMasked(null)
    onSend(trimmed, prompt)
  }

  return (
    <div>
      <label htmlFor="ai-followup" className="mb-1 block text-xs font-medium text-ink">
        {t('ai.followUp')}
      </label>
      <textarea
        id="ai-followup"
        rows={2}
        value={text}
        placeholder={t('ai.followUpPlaceholder')}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
            e.preventDefault()
            void send()
          }
        }}
        className="selectable w-full resize-y rounded-[5px] border border-line bg-surface px-2 py-1.5 text-[13px] leading-5 text-ink outline-none placeholder:text-ink-faint focus:border-amber"
      />
      {masking && trimmed && masked?.source === trimmed && masked.text !== trimmed ? (
        <p className="mt-0.5 text-xs text-ink-muted">
          {t('ai.sentAs')} <span className="code text-[12.5px] text-ink">{masked.text}</span>
        </p>
      ) : null}
      <div className="mt-1.5 flex items-center gap-2">
        <Button size="sm" variant="primary" disabled={!trimmed || sending} onClick={() => void send()}>
          {sending ? <Spinner size={11} /> : null} {t('ai.send')}
        </Button>
        <span className="text-xs text-ink-faint">{navigator.userAgent.includes('Mac') ? '⌘' : 'Ctrl+'}Enter</span>
      </div>
    </div>
  )
}

function ContextPreview({
  run,
  endpoint,
  masked,
  external,
  confirmed,
  notesChanged,
  onConfirm,
  onSend
}: {
  run: Run
  endpoint: string
  masked: number
  external: boolean
  confirmed: boolean
  notesChanged: boolean
  onConfirm: (confirmed: boolean) => void
  onSend: () => void
}): React.JSX.Element {
  const { context } = run
  return (
    <div className="space-y-2">
      <div className="text-xs text-ink-muted">
        {t('ai.preview.tokens', { tokens: context.tokenEstimate, endpoint })} {masked ? t('ai.preview.masked', { count: masked }) : t('ai.preview.notMasked')}
      </div>
      {context.trimmed.length ? (
        <div className="rounded-[5px] border border-dashed border-line-strong px-2 py-1.5 text-xs text-ink-muted">
          {t('ai.preview.trimmed', { items: context.trimmed.join(t('common.listSeparator')) })}
        </div>
      ) : null}
      <details className="text-xs">
        <summary className="cursor-default text-ink-muted hover:text-ink">{t('ai.preview.system')}</summary>
        <pre className="code selectable mt-1 max-h-32 overflow-auto rounded-[5px] border border-line bg-canvas p-2 text-[12.5px] whitespace-pre-wrap">{context.system}</pre>
      </details>
      <pre className="code selectable max-h-48 overflow-auto rounded-[5px] border border-line bg-canvas p-2 text-[12.5px] whitespace-pre-wrap">{context.user}</pre>
      {external ? (
        <label className="flex items-center gap-2 text-xs">
          <input type="checkbox" checked={confirmed} onChange={(e) => onConfirm(e.target.checked)} className="accent-[var(--amber)]" />
          {t('ai.preview.confirm')}
        </label>
      ) : null}
      {notesChanged ? <p className="text-xs text-ink-muted">{t('ai.preview.notesChanged')}</p> : null}
      <Button size="sm" variant="primary" disabled={(external && !confirmed) || notesChanged} onClick={onSend}>
        {t('ai.send')}
      </Button>
    </div>
  )
}

function SuggestionCard({ view, mask, index }: { view: SuggestionView; mask: Record<string, string>; index: AnalysisIndex | null }): React.JSX.Element {
  const markRange = useStore((s) => s.markRange)
  const applySql = useStore((s) => s.applyStatementSql)
  const edited = useStore((s) => Boolean(s.doc?.edited))
  const { suggestion, check } = view
  const text = (value: string | undefined): string | undefined => (value ? unmaskText(value, mask) : undefined)
  const target = view.statement !== null ? index?.byId.get(view.statement) : undefined
  // ai.check restores the names with the dialect's lexer; fall back to a display-only restore.
  const sql = check?.ok ? check.result.sql : suggestion.rewrittenSql ? unmaskText(suggestion.rewrittenSql, mask) : null
  const label = check?.ok ? check.result.label : null
  return (
    <article className="space-y-2 rounded-[6px] border border-line p-2.5 text-xs">
      <div className="flex items-start gap-2">
        <h4 className="min-w-0 flex-1 text-sm font-medium text-ink">{text(suggestion.title) ?? t('ai.suggestion.fallbackTitle')}</h4>
        {label ? (
          <span
            className={clsx(
              'shrink-0 rounded-[3px] border px-1.5 leading-[18px]',
              label === 'STRUCTURE_PRESERVED' && 'border-line-strong text-ink',
              label === 'STRUCTURE_CHANGED' && 'border-risk-medium text-risk-medium',
              label === 'INVALID_SQL' && 'border-risk-high text-risk-high'
            )}
          >
            {checkLabelText(label)}
          </span>
        ) : null}
      </div>
      {target ? (
        <button
          type="button"
          className="text-ink-muted hover:text-ink hover:underline"
          onClick={() => markRange({ from: target.range.start.offset, to: target.range.end.offset })}
        >
          {t('ai.suggestion.statementLine', { number: target.id + 1, line: target.range.start.line })}
        </button>
      ) : null}
      <dl className="grid grid-cols-[92px_1fr] gap-x-2 gap-y-1">
        {text(suggestion.rationale) ? (
          <>
            <dt className="text-ink-muted">{t('ai.suggestion.rationale')}</dt>
            <dd className="selectable text-ink">{text(suggestion.rationale)}</dd>
          </>
        ) : null}
        {text(suggestion.expectedEffect) ? (
          <>
            <dt className="text-ink-muted">{t('ai.suggestion.expectedEffect')}</dt>
            <dd className="selectable text-ink">{text(suggestion.expectedEffect)}</dd>
          </>
        ) : null}
        {text(suggestion.risk) ? (
          <>
            <dt className="text-ink-muted">{t('ai.suggestion.risk')}</dt>
            <dd className="selectable text-ink">{text(suggestion.risk)}</dd>
          </>
        ) : null}
      </dl>
      {check?.ok && check.result.label === 'STRUCTURE_PRESERVED' ? (
        <p className="text-ink-muted">{t('ai.suggestion.preservedNote')}</p>
      ) : null}
      {check?.ok && check.result.label === 'STRUCTURE_CHANGED' && check.result.differences.length ? (
        <div>
          <div className="text-ink-muted">{t('ai.suggestion.whatChanged')}</div>
          <ul className="mt-0.5 list-disc space-y-0.5 pl-4 text-ink">
            {check.result.differences.map((d, i) => (
              <li key={i} className="selectable">
                {d}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      {check?.ok && check.result.label === 'INVALID_SQL' && check.result.diagnostics.length ? (
        <ul className="space-y-0.5 text-risk-high">
          {check.result.diagnostics.map((d, i) => (
            <li key={i} className="selectable" title={d.message}>
              {d.range ? t('ai.suggestion.diagPosition', { line: d.range.start.line, column: d.range.start.column }) : ''}
              {d.message.length > 160 && d.offending ? t('ai.suggestion.unexpected', { token: d.offending }) : d.message.length > 160 ? `${d.message.slice(0, 159)}…` : d.message}
            </li>
          ))}
        </ul>
      ) : null}
      {check && !check.ok ? <p className="text-ink-muted">{check.message}</p> : null}
      {check?.ok && check.result.label === 'STRUCTURE_CHANGED' ? <p className="text-risk-medium">{t('suggestions.ai.changed')}</p> : null}
      {sql ? <CodeBlock code={sql} /> : null}
      {/* Only a rewrite in which the engine found no difference at all replaces the statement. */}
      {check?.ok && check.result.label === 'STRUCTURE_PRESERVED' && view.statement !== null && check.result.sql ? (
        <Button size="sm" variant="primary" disabled={edited} title={t('suggestions.ai.applyTitle')} onClick={() => applySql(view.statement!, check.result.sql)}>
          {t('suggestions.apply')}
        </Button>
      ) : null}
    </article>
  )
}

/** Whether a provider can be used right now, and why not. */
function providerReadiness(settings: ReturnType<typeof useStore.getState>['settings'], p: AiProviderId): string | null {
  if (!settings) return t('ai.readiness.loading')
  const s = settings.ai[p]
  if (!s.model) return t('ai.readiness.noModel')
  if (p === 'openai' && !s.hasApiKey) return t('ai.readiness.noKey')
  return null
}

/**
 * Pick the AI provider right in the panel: Ollama (local) or an OpenAI-compatible API. The choice is
 * saved in Settings; a provider that is not set up yet says what is missing and links to Settings.
 */
function ProviderSwitch({
  provider,
  settings,
  onSettings
}: {
  provider: AiProviderId | null
  settings: ReturnType<typeof useStore.getState>['settings']
  onSettings: () => void
}): React.JSX.Element {
  const choices: { id: AiProviderId; label: string }[] = [
    { id: 'ollama', label: 'Ollama' },
    { id: 'openai', label: 'OpenAI' }
  ]
  const missing = provider ? providerReadiness(settings, provider) : null
  return (
    <div className="space-y-1.5 pt-1">
      <div className="flex items-center gap-2">
        <span className="shrink-0 whitespace-nowrap text-xs text-ink-muted">{t('ai.provider')}</span>
        {/* A dropdown: one provider at a time, each with its model and a dot telling whether it is set up. */}
        <Select
          label={t('ai.providerAria')}
          value={provider ?? ''}
          onChange={(id) => void api().settings.update({ activeAiProvider: id as AiProviderId })}
          options={choices.map((c) => {
            const model = settings?.ai[c.id].model
            const notReady = providerReadiness(settings, c.id)
            return {
              value: c.id,
              label: model ? `${c.label} · ${model}` : c.label,
              icon: <span className={clsx('inline-block h-1.5 w-1.5 shrink-0 rounded-full', notReady ? 'bg-line-strong' : 'bg-amber')} aria-hidden />
            }
          })}
          className="h-7 min-w-0 flex-1 text-xs"
        />
        <button type="button" className="ml-auto shrink-0 whitespace-nowrap text-xs text-ink-muted underline hover:text-ink" onClick={onSettings}>
          {t('ai.settings')}
        </button>
      </div>
      {provider && missing ? (
        <p className="text-xs text-risk-high">
          {provider === 'ollama' ? 'Ollama' : 'OpenAI'}
          {t('common.colon')}
          {missing.toLocaleLowerCase(getLanguage())}.{' '}
          <button type="button" className="underline" onClick={onSettings}>
            {t('ai.setItUp')}
          </button>
        </p>
      ) : null}
    </div>
  )
}

