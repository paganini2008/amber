import { useEffect, useState } from 'react'
import type { UpdateInfo, UpdateStatus } from '@shared/api'
import { t } from '@/i18n'
import { api } from '@/lib/api'
import { Button, Spinner } from './ui'

/** The state of an update Amber installs itself, kept in step with the main process. */
export function useUpdateStatus(): UpdateStatus {
  const [status, setStatus] = useState<UpdateStatus>({ state: 'idle' })
  useEffect(() => {
    let live = true
    void api()
      .app.updateStatus()
      .then((s) => live && setStatus(s))
    const off = api().app.onUpdateStatus(setStatus)
    return () => {
      live = false
      off()
    }
  }, [])
  return status
}

/** Restarts Amber into a downloaded update. */
export function restartToUpdate(): void {
  void api().app.installUpdate()
}

export type CheckResult = { kind: 'failed'; reason: string } | { kind: 'info'; info: UpdateInfo | null }

/** What to say after a check, and which button to offer: restart into the update, or open the download page. */
export interface UpdateView {
  message: string | null
  action: { kind: 'restart' } | { kind: 'download'; url: string; primary: boolean } | null
}

/** An update in progress or downloaded says more than the last check's answer. */
export function describeUpdate(status: UpdateStatus, result: CheckResult | null): UpdateView {
  const info = result?.kind === 'info' ? result.info : null
  if (status.state === 'downloading') return { message: t('dialogs.about.downloading', { version: status.version, percent: status.percent }), action: null }
  if (status.state === 'ready') return { message: t('dialogs.about.ready', { version: status.version }), action: { kind: 'restart' } }
  if (status.state === 'error' && info?.newer && info.autoInstall) {
    return { message: t('dialogs.about.downloadFailed', { reason: status.message }), action: { kind: 'download', url: info.url, primary: false } }
  }
  if (result?.kind === 'failed') return { message: t('dialogs.about.checkFailed', { reason: result.reason }), action: null }
  if (info?.newer) return { message: t('dialogs.about.available', { version: info.latest }), action: { kind: 'download', url: info.url, primary: true } }
  if (result) return { message: info ? t('dialogs.about.latestVersion', { version: info.current }) : t('dialogs.about.latest'), action: null }
  return { message: null, action: null }
}

/**
 * "Check for updates" with its answer, used in Settings → Updates and About Amber.
 * Windows and Linux: a newer version downloads by itself, then "Restart to update" appears.
 * macOS: the download page opens, since Amber cannot install unsigned updates there.
 */
export function UpdateCheck({ size }: { size?: 'sm' }): React.JSX.Element {
  const status = useUpdateStatus()
  const [checking, setChecking] = useState(false)
  const [result, setResult] = useState<CheckResult | null>(null)

  const check = async (): Promise<void> => {
    setChecking(true)
    setResult(null)
    const r = await api().app.checkForUpdates(true)
    setChecking(false)
    setResult(r.ok ? { kind: 'info', info: r.value } : { kind: 'failed', reason: r.error.message })
  }

  const { message, action } = describeUpdate(status, result)
  const busy = checking || status.state === 'downloading'
  return (
    <div className="flex flex-wrap items-center gap-3">
      <Button size={size} onClick={() => void check()} disabled={busy || status.state === 'ready'}>
        {busy ? <Spinner size={12} /> : null} {t('dialogs.about.checkUpdates')}
      </Button>
      {message ? (
        <span className="text-sm" role="status">
          {message}
        </span>
      ) : null}
      {action?.kind === 'restart' ? (
        <Button size={size} variant="primary" onClick={restartToUpdate}>
          {t('dialogs.about.restart')}
        </Button>
      ) : null}
      {action?.kind === 'download' ? (
        <Button size={size} variant={action.primary ? 'primary' : undefined} onClick={() => void api().app.openExternal(action.url)}>
          {t('dialogs.about.downloadPage')}
        </Button>
      ) : null}
    </div>
  )
}
