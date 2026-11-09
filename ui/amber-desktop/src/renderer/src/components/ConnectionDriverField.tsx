import { CircleCheck, Download, FolderOpen } from 'lucide-react'
import { driverForDialect } from '@shared/drivers'
import { t } from '@/i18n'
import { dialectName } from '@/lib/kinds'
import { downloadLabel, installedLabel } from '@/lib/drivers'
import { Button, Field, inputClass } from './ui'

/** True for every dialect Amber can download a driver for (Amber bundles none). */
export function needsDriverJar(dialect: string): boolean {
  return Boolean(driverForDialect(dialect))
}

/** True when the connection cannot work yet: no jar of its own and no downloaded driver for its dialect. */
export function missingDriverJar(dialect: string, driverPath: string | undefined, installed = false): boolean {
  return needsDriverJar(dialect) && !installed && !(driverPath ?? '').trim()
}

/**
 * "JDBC driver (.jar)" in the connection editor. Amber bundles no driver: the one downloaded for the
 * dialect is used when the path is empty, so the field shows whether it is installed and offers the
 * download (which opens the license dialog) when it is not. A jar of the user's own, chosen with
 * Browse… or typed, always wins. A missing driver is a warning; the connection can still be saved.
 */
export function ConnectionDriverField({
  dialect,
  value,
  installed,
  onChange,
  onBrowse,
  onDownload
}: {
  dialect: string
  value: string | undefined
  /** The driver for this dialect is downloaded. */
  installed: boolean
  onChange: (path: string) => void
  onBrowse: () => void
  onDownload: () => void
}): React.JSX.Element | null {
  const driver = driverForDialect(dialect)
  if (!driver) return null
  const own = (value ?? '').trim()
  const missing = missingDriverJar(dialect, value, installed)
  return (
    <Field
      label={t('settings.connections.driver')}
      htmlFor="c-driver"
      hint={
        <>
          {missing ? (
            <span id="c-driver-error" role="alert" className="mb-0.5 block text-risk-high">
              {t('settings.connections.driverRequired', { database: dialectName(dialect) })}
            </span>
          ) : null}
          {t('settings.connections.driverHint')}
        </>
      }
    >
      <div className="flex gap-2">
        <input
          id="c-driver"
          className={`${inputClass} code ${missing ? 'border-risk-high' : ''}`}
          value={value ?? ''}
          autoComplete="off"
          spellCheck={false}
          placeholder={installed ? driver.file : '/…/driver.jar'}
          title={installed && !own ? t('settings.drivers.usesDownloaded') : undefined}
          aria-invalid={missing}
          aria-describedby={missing ? 'c-driver-error' : undefined}
          onChange={(e) => onChange(e.target.value)}
        />
        <Button onClick={onBrowse}>
          <FolderOpen size={13} /> {t('settings.connections.driverBrowse')}
        </Button>
      </div>
      <div className="mt-1.5 flex min-h-6 flex-wrap items-center gap-2">
        {installed ? (
          <>
            <span className="inline-flex items-center gap-1.5 text-xs text-ink">
              <CircleCheck size={13} className="text-amber-ink" aria-hidden />
              {installedLabel(driver)}
            </span>
            {own ? (
              <Button size="sm" variant="ghost" onClick={() => onChange('')}>
                {t('settings.drivers.useDownloaded')}
              </Button>
            ) : null}
          </>
        ) : (
          <Button size="sm" variant={own ? 'default' : 'primary'} onClick={onDownload}>
            <Download size={12} /> {downloadLabel(driver)}
          </Button>
        )}
      </div>
    </Field>
  )
}
