// Records the Amber Tutorial video end to end (macOS only). Usually started through Maven:
//   mvn -f backend/pom.xml -pl amber-core -Ptutorial package -DskipTests            real recording
//   mvn -f backend/pom.xml -pl amber-core -Ptutorial package -DskipTests -Dtutorial.dry=true   dry run
// or directly: node run.mjs --engine <engine.jar> --work <dir> --out <mp4> [--dry]
//
// Steps: check prerequisites → start the desktop app (dev build) with a clean settings profile → configure it
// (English, dark theme, OpenAI, a local MySQL connection) → size the window to 16:9 → record with the real
// mouse (or a dry run through the DevTools protocol) → quit the app → compose the video.
// Secrets are read, never printed: OPENAI_API_KEY from the environment or backend/.env, and the MySQL
// connection from the git-ignored backend/amber-core/src/test/resources/online-it.properties (mysql.*).
// Music: AMBER_TUTORIAL_BGM (environment or backend/.env), an audio file you are allowed to use; without it the
// light loop synthesized by bgm.mjs.
import { spawn, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'

const HERE = path.dirname(new URL(import.meta.url).pathname)
const APP = path.resolve(HERE, '../..')
const REPO = path.resolve(APP, '../..')
const arg = (name, fallback) => {
  const i = process.argv.indexOf(name)
  return i >= 0 && process.argv[i + 1] && !process.argv[i + 1].startsWith('--') ? process.argv[i + 1] : fallback
}
const DRY = process.argv.includes('--dry') || /^(1|true|yes)$/i.test(process.env.TUTORIAL_DRY ?? '')
const ENGINE = path.resolve(arg('--engine', path.join(REPO, 'backend/amber-core/target/engine/engine.jar')))
const WORK = path.resolve(arg('--work', path.join(REPO, 'backend/amber-core/target/tutorial')))
const OUT = path.resolve(arg('--out', path.join(REPO, 'docs/assets/amber-tutorial.mp4')))
const PORT = 9360
const SQL_DIR = '/tmp/amber-tutorial' // short path without a user name, typed into the Open dialog on screen
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const fail = (msg) => {
  console.error(`\n✗ ${msg}`)
  process.exit(1)
}

function dotenv(file) {
  const out = {}
  if (!fs.existsSync(file)) return out
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/)
    if (m) out[m[1]] = m[2].replace(/^["']|["']$/g, '')
  }
  return out
}
function properties(file) {
  const out = {}
  if (!fs.existsSync(file)) return out
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const m = line.match(/^\s*([^#=\s][^=]*?)\s*=\s*(.*)$/)
    if (m) out[m[1]] = m[2].trim()
  }
  return out
}
const jxa = (code) => spawnSync('osascript', ['-l', 'JavaScript', '-e', code], { encoding: 'utf8' })
const reachable = (host, port) =>
  new Promise((resolve) => {
    const s = net.connect({ host, port, timeout: 2000 }, () => (s.destroy(), resolve(true)))
    s.on('error', () => resolve(false))
    s.on('timeout', () => (s.destroy(), resolve(false)))
  })

function newestJar(dir) {
  try {
    const versions = fs.readdirSync(dir).sort((a, b) => a.localeCompare(b, 'en', { numeric: true }))
    for (const v of versions.reverse()) {
      const jar = fs.readdirSync(path.join(dir, v)).find((f) => f.endsWith('.jar') && !f.includes('sources'))
      if (jar) return path.join(dir, v, jar)
    }
  } catch { /* none */ }
  return null
}

// ---------- prerequisites ----------
console.log(`Amber Tutorial — ${DRY ? 'dry run (no real mouse, no recording)' : 'REAL recording'}`)
if (process.platform !== 'darwin') fail('recording needs macOS (real mouse events and screen capture).')
if (!fs.existsSync(ENGINE)) fail(`engine not found: ${ENGINE} (build it with mvn package first).`)
if (spawnSync('ffmpeg', ['-version']).status !== 0) fail('ffmpeg is not on the PATH.')
const env = { ...dotenv(path.join(REPO, 'backend/.env')), ...process.env }
if (!env.OPENAI_API_KEY) fail('OPENAI_API_KEY is not set (environment or backend/.env); the AI part needs it.')
const props = properties(path.join(REPO, 'backend/amber-core/src/test/resources/online-it.properties'))
const mysqlUrl = (props['mysql.url'] ?? '').match(/^jdbc:mysql:\/\/([^:/]+)(?::(\d+))?\/(\w+)/)
if (!mysqlUrl) fail('mysql.url is missing in online-it.properties (git-ignored test config); the online part needs it.')
const mysql = { host: mysqlUrl[1], port: Number(mysqlUrl[2] ?? 3306), database: mysqlUrl[3], user: props['mysql.user'], password: props['mysql.password'] ?? '' }
// Amber bundles no JDBC driver: mysql.driver from the test config, else the newest one in the local Maven repository.
const mysqlDriver = props['mysql.driver'] || newestJar(path.join(process.env.HOME ?? '', '.m2/repository/com/mysql/mysql-connector-j'))
if (!mysqlDriver) fail('no MySQL JDBC driver: set mysql.driver in online-it.properties (path to mysql-connector-j-*.jar).')
if (!(await reachable(mysql.host, mysql.port))) fail(`MySQL at ${mysql.host}:${mysql.port} does not answer (for a Docker container: docker start <name>).`)
if (!DRY) {
  const trusted = jxa("ObjC.import('ApplicationServices'); $.AXIsProcessTrusted()").stdout.trim()
  if (trusted !== 'true') fail('this terminal needs Accessibility permission (System Settings → Privacy & Security → Accessibility) to move the mouse.')
}
let bgm = env.AMBER_TUTORIAL_BGM ?? ''
// The outro card credits the music: AMBER_TUTORIAL_BGM_CREDIT for your own file.
let credit = bgm ? env.AMBER_TUTORIAL_BGM_CREDIT ?? '' : ''


// ---------- the script the viewer opens ----------
fs.mkdirSync(SQL_DIR, { recursive: true })
fs.copyFileSync(path.join(HERE, 'nightly_orders.sql'), path.join(SQL_DIR, 'nightly_orders.sql'))
fs.rmSync(WORK, { recursive: true, force: true })
fs.mkdirSync(WORK, { recursive: true })
if (!bgm) {
  bgm = path.join(WORK, 'bgm.wav')
  credit = 'Music: synthesized for this tutorial'
  if (spawnSync('node', [path.join(HERE, 'bgm.mjs'), '--out', bgm], { stdio: 'inherit' }).status !== 0) fail('synthesizing the music failed.')
}

// ---------- start the app with a clean profile ----------
const app = spawn('npm', ['run', 'dev', '--', '--remoteDebuggingPort', String(PORT)], {
  cwd: APP,
  detached: true,
  stdio: ['ignore', fs.openSync(path.join(WORK, 'app.log'), 'w'), fs.openSync(path.join(WORK, 'app.log'), 'a')],
  env: { ...process.env, AMBER_DEV_USER_DATA: path.join(WORK, 'profile'), AMBER_ENGINE_JAR: ENGINE }
})
const stopApp = () => {
  try {
    process.kill(-app.pid, 'SIGTERM')
  } catch {
    // already gone
  }
}
process.on('exit', stopApp)
let page = null
for (let i = 0; i < 120 && !page; i++) {
  await sleep(1000)
  try {
    page = (await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()).find((t) => t.type === 'page')
  } catch {
    // not up yet
  }
}
if (!page) fail('the desktop app did not start (see app.log in the work folder).')
await sleep(3000)

async function evaluate(expression) {
  const ws = new WebSocket(page.webSocketDebuggerUrl)
  await new Promise((r) => ws.addEventListener('open', r))
  const result = await new Promise((resolve) => {
    ws.addEventListener('message', (e) => {
      const m = JSON.parse(e.data)
      if (m.id === 1) resolve(m.result)
    })
    ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, awaitPromise: true, returnByValue: true } }))
  })
  ws.close()
  if (result?.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? 'evaluation failed')
  return result?.result?.value
}

// ---------- configure the clean profile (values go in through the app's own settings API) ----------
const configured = await evaluate(`(async () => {
  const s = window.amber.settings
  await s.update({ language: 'en', theme: 'amber-dark', defaultDialect: 'mysql', activeAiProvider: 'openai',
    ai: { openai: { enabled: true, model: 'gpt-6-luna' } }, online: { enabled: false, connectionIds: [] } })
  await s.setAiApiKey('openai', ${JSON.stringify(env.OPENAI_API_KEY)})
  await s.saveConnection({ id: 'local-mysql', name: 'Local MySQL', dialect: 'mysql', host: ${JSON.stringify(mysql.host)},
    port: ${mysql.port}, database: ${JSON.stringify(mysql.database)}, user: ${JSON.stringify(mysql.user ?? '')}, hasPassword: false, driverPath: ${JSON.stringify(mysqlDriver)} },
    ${JSON.stringify(mysql.password)})
  const db = await window.amber.db.test('local-mysql')
  const ai = await window.amber.ai.test('openai')
  return { db: db.ok, dbError: db.ok ? null : db.error?.message ?? null, ai: ai.ok }
})()`)
if (!configured.db) fail(`the app could not connect to MySQL with the test configuration${configured.dbError ? ': ' + configured.dbError : ''}.`)
if (!configured.ai) fail('the app could not reach OpenAI with OPENAI_API_KEY.')
// The default dialect applies at start: restart once so the opened script is read as MySQL right away.
await evaluate('location.reload(); 1').catch(() => 1)
await sleep(3000)

// ---------- a 16:9 window as large as the visible screen allows, and the matching crop of the capture ----------
const screen = JSON.parse(jxa(`ObjC.import('AppKit'); const s = $.NSScreen.mainScreen; const f = s.frame; const v = s.visibleFrame;
  JSON.stringify({ h: f.size.height, vx: v.origin.x, vy: v.origin.y, vw: v.size.width, vh: v.size.height, scale: s.backingScaleFactor })`).stdout)
let w = Math.min(screen.vw, Math.floor((screen.vh * 16) / 9))
let h = Math.floor((w * 9) / 16)
w = w - (w % 2)
h = h - (h % 2)
const x = Math.round(screen.vx + (screen.vw - w) / 2)
const top = Math.round(screen.h - (screen.vy + screen.vh))
spawnSync('osascript', ['-e', `tell application "System Events" to tell process "Electron" to set position of window 1 to {${x}, ${top}}`,
  '-e', `tell application "System Events" to tell process "Electron" to set size of window 1 to {${w}, ${h}}`])
await sleep(800)
const even = (n) => Math.round(n * screen.scale) - (Math.round(n * screen.scale) % 2)
fs.writeFileSync(path.join(WORK, 'meta.json'), JSON.stringify({ leadSeconds: 1.5, crop: { x: even(x), y: even(top), w: even(w), h: even(h) } }, null, 1))
console.log(`window ${w}x${h} at (${x}, ${top}), capture crop ${JSON.stringify(JSON.parse(fs.readFileSync(path.join(WORK, 'meta.json'))).crop)}`)

// ---------- record ----------
const list = spawnSync('ffmpeg', ['-hide_banner', '-f', 'avfoundation', '-list_devices', 'true', '-i', ''], { encoding: 'utf8' }).stderr
const screenIndex = (list.match(/\[(\d+)\] Capture screen 0/) ?? [])[1] ?? '1'
if (!DRY) {
  console.log('\nThe real recording uses your mouse and keyboard for about 2 minutes.')
  console.log('Turn on Do Not Disturb, and do not touch the mouse or keyboard until it says "done".')
  // Text is typed through the input source of the app in front; record.mjs switches Amber to an English keyboard
  // layout (input-source.jxa) and switches back afterwards.
  console.log('The input source is switched to an English keyboard layout while recording, and back afterwards.')
  for (let i = 10; i > 0; i--) {
    process.stdout.write(`\rstarting in ${i} s … `)
    await sleep(1000)
  }
  process.stdout.write('\n')
}
const rec = spawnSync('node', [path.join(HERE, 'record.mjs'), '--port', String(PORT), '--file', path.join(SQL_DIR, 'nightly_orders.sql'),
  '--out', WORK, '--screen', screenIndex, ...(DRY ? [] : ['--real'])], { stdio: 'inherit' })
stopApp()
if (rec.status !== 0) fail('recording failed; nothing was composed.')
console.log('done with the app.')

// ---------- compose ----------
if (DRY) {
  console.log(`dry run ok: one screenshot per subtitle in ${path.join(WORK, 'dry')}`)
} else {
  const c = spawnSync('node', [path.join(HERE, 'compose.mjs'), '--work', WORK, '--out', OUT, ...(bgm ? ['--bgm', bgm, '--credit', credit] : [])], { stdio: 'inherit' })
  if (c.status !== 0) fail('composing failed.')
}
process.exit(0)
