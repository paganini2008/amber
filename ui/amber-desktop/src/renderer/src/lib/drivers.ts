import { useEffect, useSyncExternalStore } from 'react'
import type { DriverStatus } from '@shared/api'
import { driverForDialect, type DriverArtifact } from '@shared/drivers'
import { fmt, t } from '@/i18n'
import { dialectName } from './kinds'
import { api } from './api'

/**
 * Page-side state of the on-demand JDBC drivers: which are downloaded (asked from main), and the one
 * download dialog of the app, which any place that needs a driver can open.
 */

type Statuses = Record<string, DriverStatus>

let statuses: Statuses = {}
let loaded = false
const statusListeners = new Set<() => void>()

function emitStatuses(next: Statuses): void {
  statuses = next
  for (const l of statusListeners) l()
}

/** Asks main for every driver's status again (after a download or a removal, or on first use). */
export async function refreshDrivers(): Promise<Statuses> {
  loaded = true
  const list = await api().drivers.list()
  emitStatuses(Object.fromEntries(list.map((s) => [s.id, s])))
  return statuses
}

export function setDriverStatus(status: DriverStatus): void {
  emitStatuses({ ...statuses, [status.id]: status })
}

/** Driver statuses by id (= dialect); loaded on first use. */
export function useDriverStatuses(): Statuses {
  const value = useSyncExternalStore(
    (l) => {
      statusListeners.add(l)
      return () => statusListeners.delete(l)
    },
    () => statuses,
    () => statuses
  )
  useEffect(() => {
    if (!loaded && typeof window !== 'undefined' && window.amber) void refreshDrivers().catch(() => undefined)
  }, [])
  return value
}

export function isDriverInstalled(all: Statuses, dialect: string): boolean {
  return Boolean(all[dialect]?.installed)
}

/** True when a connection of this dialect has no jar of its own and no downloaded driver to fall back on. */
export function needsDriverDownload(all: Statuses, dialect: string, driverPath: string | undefined): boolean {
  return Boolean(driverForDialect(dialect)) && !(driverPath ?? '').trim() && !isDriverInstalled(all, dialect)
}

// The download dialog: one for the whole app, opened from the connection editor, the Drivers list,
// a failed connection test or the online-mode panel.
export interface DriverDownloadRequest {
  id: string
  onDone?: (status: DriverStatus) => void
}

let request: DriverDownloadRequest | null = null
const requestListeners = new Set<() => void>()

export function openDriverDownload(id: string, onDone?: (status: DriverStatus) => void): void {
  request = { id, onDone }
  for (const l of requestListeners) l()
}

export function closeDriverDownload(): void {
  request = null
  for (const l of requestListeners) l()
}

export function useDriverDownloadRequest(): DriverDownloadRequest | null {
  return useSyncExternalStore(
    (l) => {
      requestListeners.add(l)
      return () => requestListeners.delete(l)
    },
    () => request,
    () => request
  )
}

/** Decimal sizes as download pages show them: "6.6 MB", "785 kB". */
export function driverSize(bytes: number): string {
  if (bytes >= 1_000_000) return fmt.number(bytes / 1_000_000, { style: 'unit', unit: 'megabyte', unitDisplay: 'short', maximumFractionDigits: 1, minimumFractionDigits: 1 })
  return fmt.number(Math.max(1, Math.round(bytes / 1000)), { style: 'unit', unit: 'kilobyte', unitDisplay: 'short' })
}

/** "Download IBM JCC 12.1.4.0 (6.6 MB)" */
export function downloadLabel(d: DriverArtifact): string {
  return t('settings.drivers.downloadNamed', { name: d.name, version: d.version, size: driverSize(d.size) })
}

/** "PostgreSQL driver 42.7.13 installed" */
export function installedLabel(d: DriverArtifact): string {
  return t('settings.drivers.installedNamed', { database: dialectName(d.id), version: d.version })
}

/** The host of a repository URL, for "From". */
export function repositoryHost(url: string): string {
  try {
    return new URL(url).host
  } catch {
    return url
  }
}
