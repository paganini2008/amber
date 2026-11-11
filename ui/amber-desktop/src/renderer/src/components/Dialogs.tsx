import { useEffect, useState } from 'react'
import type { AppInfo } from '@shared/api'
import logo from '@/assets/logo.png'
import { t } from '@/i18n'
import { api } from '@/lib/api'
import { platformName, versionInfo } from '@/lib/about'
import { useStore } from '@/store'
import { exportHandover, generateHandover } from '@/actions'
import { Button, Dialog, Spinner, inputClass } from './ui'
import { UpdateCheck } from './UpdateCheck'

type Links = Awaited<ReturnType<ReturnType<typeof api>['app']['links']>>

/** About Amber: what it is, versions, updates, links, author, license and third-party notices. */
export function AboutDialog(): React.JSX.Element {
  const open = useStore((s) => s.dialogs.about)
  const closeDialog = useStore((s) => s.closeDialog)
  const toast = useStore((s) => s.toast)
  const engine = useStore((s) => s.engine)
  const [info, setInfo] = useState<AppInfo | null>(null)
  const [links, setLinks] = useState<Links | null>(null)
  const [notices, setNotices] = useState<{ open: boolean; text: string | null; error: string | null }>({ open: false, text: null, error: null })
  useEffect(() => {
    if (!open) return
    if (!info) void api().app.info().then(setInfo)
    if (!links) void api().app.links().then(setLinks)
  }, [open, info, links])

  const openNotices = async (): Promise<void> => {
    setNotices({ open: true, text: notices.text, error: null })
    if (notices.text) return
    const r = await api().app.thirdPartyNotices()
    setNotices({ open: true, text: r.ok ? r.value : null, error: r.ok ? null : r.error.message })
  }
  const external = (url: string | undefined): void => {
    if (url) void api().app.openExternal(url)
  }
  const engineText = engine.info
    ? t('dialogs.about.engineOnJava', { version: engine.info.version, java: engine.info.java })
    : engine.state === 'failed'
      ? t('dialogs.about.notRunning')
      : t('dialogs.about.starting')

  return (
    <>
      <Dialog open={open} onOpenChange={(o) => !o && closeDialog('about')} title={t('dialogs.about.title')} width={600}>
        <div className="space-y-5 px-6 py-5">
          <div className="chrome hero relative -mx-6 -mt-5 flex items-center gap-5 overflow-hidden px-6 py-6">
            <div className="hero-glow" aria-hidden style={{ left: -200, width: 420, height: 420 }} />
            <img src={logo} alt="" className="hero-logo relative h-20 w-20 shrink-0" draggable={false} />
            <div className="relative min-w-0">
              <div className="text-2xl font-semibold tracking-[-0.01em] text-ink">Amber</div>
              <div className="text-[15px] leading-[22px] text-ink">{t('dialogs.about.slogan')}</div>
              <div className="mt-1 text-sm text-ink-muted">{t('dialogs.about.version', { version: info?.version ?? '…' })}</div>
            </div>
          </div>

          <p className="text-sm text-ink">
            {t('dialogs.about.privacy')}
          </p>

          <section>
            <div className="mb-1.5 flex items-center gap-3">
              <h3 className="text-sm font-semibold">{t('dialogs.about.versions')}</h3>
              <button
                type="button"
                className="text-xs text-amber-ink hover:underline"
                onClick={() =>
                  void navigator.clipboard.writeText(versionInfo(info, engine.info)).then(
                    () => toast(t('dialogs.about.copiedInfo')),
                    () => toast(t('dialogs.about.copyFailed'), 'error')
                  )
                }
              >
                {t('dialogs.about.copyInfo')}
              </button>
            </div>
            <dl className="grid grid-cols-[110px_1fr] gap-x-4 gap-y-1 text-sm">
              <dt className="text-ink-muted">Amber</dt>
              <dd>{info?.version ?? '…'}</dd>
              <dt className="text-ink-muted">{t('dialogs.about.engine')}</dt>
              <dd>{engineText}</dd>
              <dt className="text-ink-muted">Electron</dt>
              <dd>
                {info ? `${info.electron} (Chromium ${info.chrome}, Node.js ${info.node})` : '…'}
              </dd>
              <dt className="text-ink-muted">{t('dialogs.about.system')}</dt>
              <dd>{info ? `${platformName(info.platform)} ${info.arch}` : '…'}</dd>
            </dl>
          </section>

          <section>
            <UpdateCheck size="sm" />
          </section>

          <section>
            <h3 className="mb-1.5 text-sm font-semibold">{t('dialogs.about.links')}</h3>
            <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
              <LinkButton onClick={() => external(links?.project)}>{t('dialogs.about.project')}</LinkButton>
              <LinkButton onClick={() => external(links?.website)}>{t('dialogs.about.website')}</LinkButton>
              <LinkButton onClick={() => external(links?.releases)}>{t('dialogs.about.releases')}</LinkButton>
              <LinkButton onClick={() => external(links?.issue)}>{t('dialogs.about.issue')}</LinkButton>
            </div>
          </section>

          <dl className="grid grid-cols-[110px_1fr] gap-x-4 gap-y-1 text-sm">
            <dt className="text-ink-muted">{t('dialogs.about.author')}</dt>
            <dd>
              {links?.author.name ?? 'Fred Feng'}
              {links ? (
                <>
                  {' · '}
                  <LinkButton onClick={() => external(links.author.url)}>{links.author.url.replace('https://', '')}</LinkButton>
                </>
              ) : null}
            </dd>
            <dt className="text-ink-muted">{t('dialogs.about.license')}</dt>
            <dd>
              Apache License 2.0 ·{' '}
              <LinkButton onClick={() => void openNotices()}>{t('dialogs.about.notices')}</LinkButton>
            </dd>
          </dl>
        </div>
      </Dialog>
      <Dialog open={notices.open} onOpenChange={(o) => setNotices({ ...notices, open: o })} title={t('dialogs.about.notices')} description={t('dialogs.about.noticesLead')} width={760}>
        <div className="h-[62vh] px-5 py-4">
          {notices.error ? (
            <p className="text-sm text-risk-high">{t('dialogs.about.noticesFailed', { reason: notices.error })}</p>
          ) : notices.text === null ? (
            <p className="flex items-center gap-2 text-sm text-ink-muted">
              <Spinner size={12} /> {t('common.loading')}
            </p>
          ) : (
            <pre className="code selectable h-full overflow-auto rounded-[6px] border border-line bg-canvas p-3 text-[12.5px] leading-[19px] whitespace-pre-wrap text-ink">{notices.text}</pre>
          )}
        </div>
      </Dialog>
    </>
  )
}

function LinkButton({ children, onClick }: { children: React.ReactNode; onClick: () => void }): React.JSX.Element {
  return (
    <button type="button" className="text-amber-ink underline-offset-2 hover:underline" onClick={onClick}>
      {children}
    </button>
  )
}

export function PasteDialog(): React.JSX.Element {
  const open = useStore((s) => s.dialogs.paste)
  const closeDialog = useStore((s) => s.closeDialog)
  const loadDocument = useStore((s) => s.loadDocument)
  const [text, setText] = useState('')
  const [name, setName] = useState('pasted.sql')

  const analyze = (): void => {
    if (!text.trim()) return
    loadDocument({ path: null, name: name.trim() || 'pasted.sql', text, encoding: 'UTF-8' })
    closeDialog('paste')
    setText('')
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => !o && closeDialog('paste')}
      title={t('dialogs.paste.title')}
      description={t('dialogs.paste.lead')}
      width={720}
      footer={
        <>
          <Button variant="ghost" onClick={() => closeDialog('paste')}>
            {t('common.cancel')}
          </Button>
          <Button variant="primary" onClick={analyze} disabled={!text.trim()}>
            {t('app.analysis.analyze')}
          </Button>
        </>
      }
    >
      <div className="space-y-3 px-5 py-4">
        <label className="flex items-center gap-3 text-sm">
          <span className="w-12 shrink-0 text-ink-muted">{t('dialogs.paste.name')}</span>
          <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <textarea
          aria-label={t('dialogs.paste.text')}
          className="code selectable h-[46vh] w-full resize-none rounded-[5px] border border-line bg-canvas p-3 text-[13px] text-ink outline-none focus:border-amber"
          spellCheck={false}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) analyze()
          }}
          placeholder="CREATE TABLE …"
          autoFocus
        />
      </div>
    </Dialog>
  )
}

/**
 * Preview of the handover document. The engine's HTML is one self-contained page without
 * scripts; it is shown in a sandboxed iframe (no scripts, no same-origin access).
 */
export function HandoverDialog(): React.JSX.Element {
  const open = useStore((s) => s.dialogs.handover)
  const close = useStore((s) => s.closeDialog)
  const analysis = useStore((s) => s.analysis)
  const [state, setState] = useState<{ status: 'loading' } | { status: 'ready'; html: string } | { status: 'error'; message: string }>({ status: 'loading' })

  useEffect(() => {
    if (!open) return
    let cancelled = false
    setState({ status: 'loading' })
    void generateHandover('html').then((r) => {
      if (!cancelled) setState(r.ok ? { status: 'ready', html: r.text } : { status: 'error', message: r.message })
    })
    return () => {
      cancelled = true
    }
  }, [open, analysis])

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => !o && close('handover')}
      title={t('dialogs.handover.title')}
      description={t('dialogs.handover.lead')}
      width={980}
      footer={
        <>
          <Button variant="ghost" onClick={() => close('handover')}>
            {t('common.close')}
          </Button>
          <Button onClick={() => void exportHandover('markdown')}>{t('app.export.markdown')}</Button>
          <Button variant="primary" onClick={() => void exportHandover('html')}>
            {t('app.export.html')}
          </Button>
        </>
      }
    >
      <div className="h-[68vh] bg-canvas p-3">
        {state.status === 'loading' ? (
          <div className="flex h-full items-center justify-center gap-2 text-sm text-ink-muted">
            <Spinner /> {t('dialogs.handover.writing')}
          </div>
        ) : state.status === 'error' ? (
          <p className="p-4 text-sm text-risk-high">{state.message}</p>
        ) : (
          <iframe
            title={t('dialogs.handover.preview')}
            sandbox=""
            srcDoc={state.html}
            className="h-full w-full rounded-[6px] border border-line bg-white"
          />
        )}
      </div>
    </Dialog>
  )
}
