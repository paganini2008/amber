import { CircleCheck, ExternalLink, TriangleAlert } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import type { DriverStatus } from '@shared/api'
import type { RpcErrorObject } from '@shared/protocol'
import { coordinates, driverById, type DriverArtifact, type DriverFailure } from '@shared/drivers'
import { t } from '@/i18n'
import { api, newRequestId } from '@/lib/api'
import { closeDriverDownload, driverSize, repositoryHost, setDriverStatus, useDriverDownloadRequest, useDriverStatuses } from '@/lib/drivers'
import { Button, Dialog, Spinner } from './ui'

type Phase =
  | { kind: 'confirm' }
  | { kind: 'downloading'; requestId: string; received: number }
  | { kind: 'done'; status: DriverStatus }
  | { kind: 'failed'; error: RpcErrorObject }

const openLink = (e: React.MouseEvent<HTMLAnchorElement>, url: string): void => {
  e.preventDefault()
  void api().app.openExternal(url)
}

/**
 * What the user agrees to before Amber downloads a driver: the artifact, where it comes from, its size,
 * its pinned checksum, and the vendor's license (opened in the browser). Separate from the dialog so it
 * can be rendered and tested on its own.
 */
export function DriverConsent({ driver, repository, progress }: { driver: DriverArtifact; repository: string; progress?: number | null }): React.JSX.Element {
  const pct = progress == null ? null : Math.min(100, Math.round((progress / driver.size) * 100))
  return (
    <div className="space-y-4">
      <div className="chrome rounded-[6px] px-4 pt-3 pb-3.5">
        <div className="code text-[13px] break-all text-ink">{coordinates(driver)}</div>
        <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-xs">
          <dt className="text-ink-muted">{t('settings.drivers.dialog.file')}</dt>
          <dd className="code min-w-0 truncate text-ink">{driver.file}</dd>
          <dt className="text-ink-muted">{t('settings.drivers.dialog.from')}</dt>
          <dd className="code min-w-0 truncate text-ink" title={repository}>{repositoryHost(repository)}</dd>
          <dt className="text-ink-muted">{t('settings.drivers.dialog.size')}</dt>
          <dd className="text-ink">{driverSize(driver.size)}</dd>
          <dt className="text-ink-muted">{t('settings.drivers.dialog.checksum')}</dt>
          <dd className="min-w-0">
            <span className="code block text-[11px] leading-4 break-all text-ink">{driver.sha256}</span>
            <span className="text-ink-muted">{t('settings.drivers.dialog.checksumHint')}</span>
          </dd>
        </dl>
        {pct !== null ? (
          <div className="mt-3">
            <div
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={pct}
              aria-label={t('settings.drivers.dialog.downloading', { received: driverSize(progress!), total: driverSize(driver.size) })}
              className="h-1.5 overflow-hidden rounded-full bg-surface-2"
            >
              <div className="h-full rounded-full bg-amber" style={{ width: `${pct}%` }} />
            </div>
            <div className="mt-1 flex justify-between text-xs text-ink-muted">
              <span>{t('settings.drivers.dialog.downloading', { received: driverSize(progress!), total: driverSize(driver.size) })}</span>
              <span className="tabular-nums">{pct}%</span>
            </div>
          </div>
        ) : null}
      </div>
      <div className="border-l-2 border-amber pl-3">
        <div className="text-xs text-ink-muted">{t('settings.drivers.dialog.license')}</div>
        <a
          href={driver.license.url}
          target="_blank"
          rel="noreferrer"
          onClick={(e) => openLink(e, driver.license.url)}
          className="inline-flex items-center gap-1 text-sm font-medium text-amber-ink underline decoration-1 underline-offset-2 hover:decoration-2"
        >
          {driver.license.name}
          <ExternalLink size={12} aria-hidden />
        </a>
        <div className="text-xs text-ink-muted">
          {t('settings.drivers.dialog.vendor')}: {driver.vendor}
        </div>
      </div>
    </div>
  )
}

function failureReason(error: RpcErrorObject): DriverFailure | null {
  const reason = (error.data as { reason?: unknown } | undefined)?.reason
  return typeof reason === 'string' ? (reason as DriverFailure) : null
}

/** The failure, what probably caused it, and the manual way out. */
export function DriverFailureMessage({ error }: { error: RpcErrorObject }): React.JSX.Element {
  const reason = failureReason(error)
  const hint =
    reason === 'hash' || reason === 'size'
      ? t('settings.drivers.dialog.checksumFailedHint')
      : reason === 'offline' || reason === 'http' || reason === 'timeout'
        ? t('settings.drivers.dialog.offlineHint')
        : null
  return (
    <div role="alert" className="flex gap-2 rounded-[6px] border border-risk-high/40 px-3 py-2 text-sm">
      <TriangleAlert size={15} className="mt-0.5 shrink-0 text-risk-high" aria-hidden />
      <div className="min-w-0 space-y-1">
        <div className="font-medium text-ink">{t('settings.drivers.dialog.failed')}</div>
        <div className="break-words text-ink">{error.message}</div>
        {hint ? <div className="text-ink-muted">{hint}</div> : null}
        <div className="text-ink-muted">{t('settings.drivers.dialog.manual')}</div>
      </div>
    </div>
  )
}

/** The app's one driver download dialog: consent, progress with cancel, then success or a clear failure. */
export function DriverDownloadDialog(): React.JSX.Element | null {
  const request = useDriverDownloadRequest()
  const statuses = useDriverStatuses()
  const [phase, setPhase] = useState<Phase>({ kind: 'confirm' })
  const requestRef = useRef<string | null>(null)
  const driver = request ? driverById(request.id) : undefined

  useEffect(() => {
    setPhase({ kind: 'confirm' })
  }, [request])

  useEffect(
    () =>
      api().drivers.onProgress((p) => {
        if (p.requestId !== requestRef.current) return
        setPhase((ph) => (ph.kind === 'downloading' && ph.requestId === p.requestId ? { ...ph, received: p.received } : ph))
      }),
    []
  )

  if (!request || !driver) return null
  const repository = statuses[driver.id]?.repository ?? ''

  const start = async (): Promise<void> => {
    const requestId = newRequestId('driver')
    requestRef.current = requestId
    setPhase({ kind: 'downloading', requestId, received: 0 })
    const r = await api().drivers.download(driver.id, requestId)
    if (requestRef.current !== requestId) return
    requestRef.current = null
    if (r.ok) {
      setDriverStatus(r.value)
      setPhase({ kind: 'done', status: r.value })
      request.onDone?.(r.value)
    } else {
      setPhase({ kind: 'failed', error: r.error })
    }
  }

  const cancel = (): void => {
    const id = requestRef.current
    requestRef.current = null
    if (id) void api().drivers.cancel(id)
  }

  const close = (): void => {
    if (phase.kind === 'downloading') cancel()
    closeDriverDownload()
  }

  const footer =
    phase.kind === 'confirm' ? (
      <>
        <Button variant="ghost" onClick={close}>
          {t('common.cancel')}
        </Button>
        <Button variant="primary" onClick={() => void start()} disabled={!repository}>
          {t('settings.drivers.dialog.accept')}
        </Button>
      </>
    ) : phase.kind === 'downloading' ? (
      <Button onClick={close}>
        <Spinner size={12} /> {t('common.cancel')}
      </Button>
    ) : phase.kind === 'failed' ? (
      <>
        <Button variant="ghost" onClick={close}>
          {t('common.close')}
        </Button>
        <Button variant="primary" onClick={() => void start()}>
          {t('settings.drivers.dialog.retry')}
        </Button>
      </>
    ) : (
      <Button variant="primary" onClick={close}>
        {t('common.close')}
      </Button>
    )

  return (
    <Dialog open onOpenChange={(o) => !o && close()} title={t('settings.drivers.dialog.title', { name: driver.name })} width={540} footer={footer}>
      <div className="space-y-4 px-5 py-4">
        <DriverConsent driver={driver} repository={repository} progress={phase.kind === 'downloading' ? phase.received : null} />
        {phase.kind === 'confirm' || phase.kind === 'downloading' ? (
          <p className="text-sm text-ink">{t('settings.drivers.dialog.consent', { vendor: driver.vendor })}</p>
        ) : null}
        {phase.kind === 'done' ? (
          <div role="status" className="flex gap-2 text-sm">
            <CircleCheck size={16} className="mt-0.5 shrink-0 text-amber-ink" aria-hidden />
            <div className="min-w-0">
              <div className="font-medium text-ink">{t('settings.drivers.dialog.done')}</div>
              <div className="code text-[12px] break-all text-ink-muted">{t('settings.drivers.dialog.savedTo', { path: phase.status.path ?? '' })}</div>
            </div>
          </div>
        ) : null}
        {phase.kind === 'failed' ? <DriverFailureMessage error={phase.error} /> : null}
      </div>
    </Dialog>
  )
}
