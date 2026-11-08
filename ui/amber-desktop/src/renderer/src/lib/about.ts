import type { AppInfo } from '@shared/api'
import type { EngineInfo } from '@shared/protocol'

/** The text "Copy version info" puts on the clipboard, for bug reports. */
export function versionInfo(app: AppInfo | null, engine: EngineInfo | undefined): string {
  return [
    `Amber ${app?.version ?? 'unknown'}`,
    `Engine ${engine?.version ?? 'not running'}${engine?.java ? ` on Java ${engine.java}` : ''}`,
    `Electron ${app?.electron ?? '?'} (Chromium ${app?.chrome ?? '?'}, Node.js ${app?.node ?? '?'})`,
    `${platformName(app?.platform)} ${app?.arch ?? ''}`.trim()
  ].join('\n')
}

export function platformName(platform: string | undefined): string {
  switch (platform) {
    case 'darwin':
      return 'macOS'
    case 'win32':
      return 'Windows'
    case 'linux':
      return 'Linux'
    default:
      return platform ?? 'unknown system'
  }
}
