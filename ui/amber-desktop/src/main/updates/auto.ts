import type { UpdateStatus } from '../../shared/api'

/**
 * Downloading and installing updates in the app (electron-updater), where the platform allows it.
 *
 * - Windows (NSIS) and Linux (.deb / .rpm): the update is downloaded in the background and installed
 *   on restart, or when Amber quits.
 * - macOS: Squirrel.Mac only installs updates signed with an Apple Developer ID. Amber is not signed,
 *   so it only shows that a new version exists and links to the download page.
 *
 * electron-updater itself is passed in, so this file has no Electron dependency and can be tested.
 */

/** What Amber needs from electron-updater's `autoUpdater`. */
export interface Updater {
  autoDownload: boolean
  autoInstallOnAppQuit: boolean
  allowPrerelease: boolean
  checkForUpdates(): Promise<unknown>
  quitAndInstall(isSilent?: boolean, isForceRunAfter?: boolean): void
  on(event: 'download-progress', listener: (info: { percent: number }) => void): unknown
  on(event: 'update-downloaded', listener: (info: { version: string }) => void): unknown
  on(event: 'error', listener: (error: Error) => void): unknown
}

/**
 * Whether this installation can update itself.
 *
 * @param packageType contents of `resources/package-type`, written by electron-builder for .deb / .rpm
 * @param hasUpdateConfig whether `resources/app-update.yml` exists (it does when the build had a publish config)
 */
export function canInstallUpdates(platform: NodeJS.Platform, isPackaged: boolean, packageType: string | null, hasUpdateConfig: boolean): boolean {
  if (!isPackaged || !hasUpdateConfig) return false
  if (platform === 'win32') return true
  if (platform === 'linux') return packageType === 'deb' || packageType === 'rpm'
  return false
}

export class AutoUpdates {
  private status: UpdateStatus = { state: 'idle' }
  private target: string | null = null

  constructor(
    private readonly updater: Updater,
    private readonly publish: (status: UpdateStatus) => void,
    prerelease: boolean
  ) {
    updater.autoDownload = true
    updater.autoInstallOnAppQuit = true
    // A release-candidate build follows the next candidates; a release build only follows releases.
    updater.allowPrerelease = prerelease
    updater.on('download-progress', (p) => {
      if (this.target) this.set({ state: 'downloading', version: this.target, percent: Math.max(0, Math.min(100, Math.round(p.percent))) })
    })
    updater.on('update-downloaded', (info) => this.set({ state: 'ready', version: info.version || this.target || '' }))
    updater.on('error', (error) => {
      this.target = null
      this.set({ state: 'error', message: error.message })
    })
  }

  current(): UpdateStatus {
    return this.status
  }

  /** Starts downloading `version` unless it is already downloading or downloaded. */
  async download(version: string): Promise<void> {
    if (this.target === version && (this.status.state === 'downloading' || this.status.state === 'ready')) return
    this.target = version
    this.set({ state: 'downloading', version, percent: 0 })
    try {
      await this.updater.checkForUpdates()
    } catch (error) {
      // The 'error' event usually reports it as well; make sure the status never stays on "downloading".
      if (this.status.state === 'downloading') {
        this.target = null
        this.set({ state: 'error', message: error instanceof Error ? error.message : String(error) })
      }
    }
  }

  /** Quits Amber, installs the downloaded update and starts the new version. */
  install(): boolean {
    if (this.status.state !== 'ready') return false
    this.updater.quitAndInstall(false, true)
    return true
  }

  private set(status: UpdateStatus): void {
    this.status = status
    this.publish(status)
  }
}
