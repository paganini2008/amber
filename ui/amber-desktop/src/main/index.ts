import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { BrowserWindow, Menu, app, nativeTheme, safeStorage, session, shell } from 'electron'
import { autoUpdater } from 'electron-updater'
import type { MenuCommand, Settings } from '../shared/api'
import { resolveTheme } from '../shared/themes'
import { loadDevEnv, logs, readConfig } from './config'
import { EngineSupervisor, resolveEngineCommand } from './engine/supervisor'
import { isSafeExternalUrl, registerIpc } from './ipc'
import { mainLanguage, setMainLanguage } from './i18n'
import { buildMenu } from './menu'
import { SettingsStore } from './settings/store'
import { AutoUpdates, canInstallUpdates } from './updates/auto'
import { isPrerelease } from './updates/check'

// Development only: ui/amber-desktop/.env supplies dev wiring and defaults, never overriding the
// real environment. A packaged app does not read any .env.
loadDevEnv(join(app.getAppPath(), '.env'), app.isPackaged)
const config = readConfig(process.env, app.isPackaged)
if (config.devCdpPort) app.commandLine.appendSwitch('remote-debugging-port', String(config.devCdpPort))
// Development only: a separate settings folder, so a recording or test never shows or changes the real one.
if (config.devUserData) app.setPath('userData', config.devUserData)

const isMac = process.platform === 'darwin'
const TITLE_BAR_HEIGHT = 40

let mainWindow: BrowserWindow | null = null

const settings = new SettingsStore(
  join(app.getPath('userData'), 'settings.json'),
  join(app.getPath('userData'), 'secrets.json'),
  {
    // Async safeStorage: a keychain prompt must not freeze the app's main process.
    available: () => safeStorage.isAsyncEncryptionAvailable(),
    encrypt: async (plain) => (await safeStorage.encryptStringAsync(plain)).toString('base64'),
    decrypt: async (cipher) => (await safeStorage.decryptStringAsync(Buffer.from(cipher, 'base64'))).result
  },
  { ollamaBaseUrl: config.defaultOllamaUrl, openaiBaseUrl: config.defaultOpenAiUrl, aiTimeoutSec: config.defaultAiTimeoutSec }
)

const engine = new EngineSupervisor({
  command: resolveEngineCommand({
    packaged: app.isPackaged,
    resourcesPath: process.resourcesPath,
    appRoot: app.getAppPath(),
    platform: process.platform,
    env: process.env
  }),
  maxRestarts: config.engineMaxRestarts,
  restartWindowMs: config.engineRestartWindowSec * 1000,
  // Engine stderr carries logs only; it never echoes SQL text.
  log: (line) => {
    if (!app.isPackaged && logs(config, 'info')) console.log(`[engine] ${line}`)
  }
})

engine.on('status', (status) => {
  mainWindow?.webContents.send('engine:status', status)
})

function applyTheme(value: Settings): void {
  nativeTheme.themeSource =
    value.theme === 'system' ? 'system' : value.theme === 'amber-light' ? 'light' : 'dark'
  const theme = resolveTheme(value.theme, nativeTheme.shouldUseDarkColors)
  if (!mainWindow) return
  mainWindow.setBackgroundColor(theme.tokens.canvas)
  if (!isMac) {
    try {
      mainWindow.setTitleBarOverlay({
        color: theme.tokens.chrome,
        symbolColor: theme.tokens.chromeInk,
        height: TITLE_BAR_HEIGHT
      })
    } catch {
      // Not supported on this platform/window.
    }
  }
}

function installMenu(): void {
  Menu.setApplicationMenu(buildMenu({ isMac, isDev: !app.isPackaged, send: sendMenu, repo: config.updateRepo }))
}

/** Main-process messages follow Settings → General → Language; the native menu is rebuilt live. */
function applyLanguage(value: Settings): void {
  if (value.language === mainLanguage()) return
  setMainLanguage(value.language)
  installMenu()
}

function sendMenu(command: MenuCommand): void {
  mainWindow?.webContents.send('menu', command)
}

function createWindow(): void {
  const current = settings.get()
  const theme = resolveTheme(current.theme, nativeTheme.shouldUseDarkColors)
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 960,
    minHeight: 600,
    show: false,
    title: 'Amber',
    backgroundColor: theme.tokens.canvas,
    titleBarStyle: isMac ? 'hiddenInset' : 'hidden',
    trafficLightPosition: isMac ? { x: 14, y: 13 } : undefined,
    titleBarOverlay: isMac
      ? undefined
      : { color: theme.tokens.chrome, symbolColor: theme.tokens.chromeInk, height: TITLE_BAR_HEIGHT },
    icon: app.isPackaged ? undefined : join(app.getAppPath(), 'build', 'icon.png'),
    webPreferences: {
      preload: join(import.meta.dirname, '../preload/index.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      allowRunningInsecureContent: false,
      spellcheck: false
    }
  })

  mainWindow.on('ready-to-show', () => mainWindow?.show())
  mainWindow.on('closed', () => {
    mainWindow = null
  })

  // External links open in the system browser; the app never navigates away.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (isSafeExternalUrl(url)) void shell.openExternal(url)
    return { action: 'deny' }
  })
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (url !== mainWindow?.webContents.getURL()) event.preventDefault()
  })

  const devUrl = process.env.ELECTRON_RENDERER_URL
  if (!app.isPackaged && devUrl) {
    void mainWindow.loadURL(devUrl)
  } else {
    void mainWindow.loadFile(join(import.meta.dirname, '../renderer/index.html'))
  }
}

app.enableSandbox()
// The packaged app gets its name and icon from electron-builder; in development the Electron binary
// would show "Electron" and its own icon in the Dock.
app.setName('Amber')

app.on('web-contents-created', (_event, contents) => {
  contents.on('will-attach-webview', (event) => event.preventDefault())
})

/** In-app updates on Windows and Linux .deb/.rpm; null on macOS and in development (see updates/auto.ts). */
function createAutoUpdates(): AutoUpdates | null {
  const resources = process.resourcesPath
  const packageTypeFile = join(resources, 'package-type')
  const packageType = existsSync(packageTypeFile) ? readFileSync(packageTypeFile, 'utf8').trim() : null
  if (!canInstallUpdates(process.platform, app.isPackaged, packageType, existsSync(join(resources, 'app-update.yml')))) return null
  const [owner = 'paganini2008', repo = 'amber'] = config.updateRepo.split('/')
  autoUpdater.setFeedURL({ provider: 'github', owner, repo })
  autoUpdater.logger = null
  return new AutoUpdates(autoUpdater, (status) => mainWindow?.webContents.send('app:updateStatus', status), isPrerelease(app.getVersion()))
}

void app.whenReady().then(() => {
  if (isMac && !app.isPackaged) app.dock?.setIcon(join(app.getAppPath(), 'build', 'icon.png'))
  app.setAboutPanelOptions({ applicationName: 'Amber', copyright: 'Apache License 2.0' })
  // No permission (camera, notifications, …) is ever needed.
  session.defaultSession.setPermissionRequestHandler((_wc, _permission, callback) => callback(false))

  setMainLanguage(settings.get().language)
  registerIpc({ config, engine, settings, getWindow: () => mainWindow, applyTheme, applyLanguage, updates: createAutoUpdates() })
  installMenu()
  createWindow()
  applyTheme(settings.get())
  void engine.start().catch(() => undefined)

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (!isMac) app.quit()
})

let quitting = false
app.on('before-quit', (event) => {
  if (quitting) return
  quitting = true
  event.preventDefault()
  void engine.stop().finally(() => app.quit())
})
