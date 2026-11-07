// UI smoke test over the Chrome DevTools Protocol, against a running DEVELOPMENT instance.
//
//   npm run dev -- --remoteDebuggingPort 9334          (in one terminal)
//   node scripts/ui-smoke.mjs --port 9334 [--theme amber-dark] [--timeout ms] [--no-samples] [extra.sql ...]
//
// Opens every bundled sample plus the given files, visits every view at 960×600, 1280×800 and
// 1920×1080, and fails (exit code 1) on console errors, uncaught exceptions or unhandled rejections,
// horizontal page overflow, or a renderer that stops answering. Needs the dev-only `__amberDev` hook,
// so it does not run against a packaged app. It never changes settings except the theme, and puts
// the theme back at the end.
import { readdirSync } from 'node:fs'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const args = process.argv.slice(2)
const opt = (name, fallback) => {
  const i = args.indexOf(`--${name}`)
  if (i < 0) return fallback
  const v = args[i + 1]
  args.splice(i, 2)
  return v
}
const port = Number(opt('port', '9333'))
const theme = opt('theme', null)
const timeoutMs = Number(opt('timeout', '90000'))
const samplesDir = join(root, 'src', 'renderer', 'src', 'samples')
const noSamples = args.includes('--no-samples')
if (noSamples) args.splice(args.indexOf('--no-samples'), 1)
const files = [...(noSamples ? [] : readdirSync(samplesDir).filter((f) => f.endsWith('.sql')).map((f) => join(samplesDir, f))), ...args.map((a) => resolve(a))]
const SIZES = [
  [960, 600],
  [1280, 800],
  [1920, 1080]
]
const VIEWS = ['outline', 'lifecycle', 'dataflow', 'lineage', 'statement', 'structure', 'playback']
const DIALECTS = { oracle: 'oracle', sqlserver: 'sqlserver', postgresql: 'postgresql', mysql: 'mysql', mariadb: 'mariadb', db2: 'db2', h2: 'h2', sqlite: 'sqlite' }

const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json()
const page = targets.find((t) => t.type === 'page' && !t.url.startsWith('devtools'))
if (!page) {
  console.error(`No app window on port ${port}. Start one with: npm run dev -- --remoteDebuggingPort ${port}`)
  process.exit(2)
}
const ws = new WebSocket(page.webSocketDebuggerUrl)
await new Promise((r) => ws.addEventListener('open', r))
let id = 0
const pending = new Map()
const problems = []
let context = 'start'
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) {
    pending.get(m.id)(m)
    pending.delete(m.id)
    return
  }
  if (m.method === 'Runtime.exceptionThrown') {
    const d = m.params.exceptionDetails
    problems.push(`${context}: uncaught ${d.exception?.description?.split('\n')[0] ?? d.text}`)
  } else if (m.method === 'Runtime.consoleAPICalled' && (m.params.type === 'error' || m.params.type === 'assert')) {
    const text = m.params.args.map((a) => a.value ?? a.description ?? '').join(' ')
    // React dev-only warnings are noise for this check; real errors are kept.
    if (!/^Warning: /.test(text)) problems.push(`${context}: console.error ${text.slice(0, 300)}`)
  }
})
const send = (method, params = {}, ms = timeoutMs) =>
  new Promise((resolveSend, reject) => {
    const i = ++id
    const timer = setTimeout(() => {
      pending.delete(i)
      reject(new Error(`${method} timed out after ${ms} ms (renderer not answering?)`))
    }, ms)
    pending.set(i, (m) => {
      clearTimeout(timer)
      resolveSend(m)
    })
    ws.send(JSON.stringify({ id: i, method, params }))
  })
const ev = async (expression, ms) => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }, ms)
  if (r.result?.exceptionDetails) throw new Error(r.result.exceptionDetails.exception?.description ?? r.result.exceptionDetails.text)
  return r.result?.result?.value
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms))

await send('Runtime.enable')
// Installs a listener for unhandled rejections inside the page; they are reported as console errors.
await ev(`(() => { if (!window.__smoke) { window.__smoke = true; window.addEventListener('unhandledrejection', (e) => console.error('unhandledrejection', String(e.reason && (e.reason.stack || e.reason)))) } return 1 })()`)
if (!(await ev('Boolean(window.__amberDev)'))) {
  console.error('The dev hook is missing: run a development instance (npm run dev), not a packaged app.')
  process.exit(2)
}
const originalTheme = await ev('window.amber.settings.get().then((s) => s.theme)')
if (theme) await ev(`window.amber.settings.update({ theme: ${JSON.stringify(theme)} }).then(() => 1)`)

const dialectOf = (file) => Object.keys(DIALECTS).find((d) => basename(file).toLowerCase().startsWith(d)) ?? 'postgresql'
const overflow = () => ev('Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - window.innerWidth')
const results = []

for (const file of files) {
  try {
    await checkFile(file)
  } catch (error) {
    problems.push(`${context}: ${error instanceof Error ? error.message : error}`)
    // A renderer that stopped answering makes every later step fail too.
    if (/timed out/.test(String(error))) break
  }
}

async function checkFile(file) {
  context = `${basename(file)} (opening)`
  const t0 = Date.now()
  const opened = await ev(`(async () => {
    const st = window.__amberDev.store.getState()
    const f = await window.amber.files.open(${JSON.stringify(file)})
    if (!f.ok) return 'open failed: ' + f.error.message
    st.setDialect(${JSON.stringify(dialectOf(file))})
    window.__amberDev.store.getState().loadDocument(f.value)
    for (let i = 0; i < ${Math.round(timeoutMs / 100)}; i++) {
      await new Promise((r) => setTimeout(r, 100))
      const s = window.__amberDev.store.getState()
      if (!s.analyzing && (s.analysis || s.analyzeError)) return s.analyzeError ? 'error: ' + s.analyzeError.message : s.analysis.status
    }
    return 'timeout'
  })()`)
  const loadMs = Date.now() - t0
  if (opened === 'timeout' || String(opened).startsWith('open failed')) problems.push(`${context}: ${opened}`)
  for (const [w, h] of SIZES) {
    await send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: false })
    for (const view of VIEWS) {
      context = `${basename(file)} @${w}x${h} ${view}`
      await ev(`(window.__amberDev.store.getState().setView(${JSON.stringify(view)}), 1)`)
      await wait(view === 'dataflow' || view === 'lineage' ? 700 : 250)
      const over = await overflow()
      if (over > 1) problems.push(`${context}: horizontal page overflow by ${over}px`)
    }
  }
  results.push({ file: basename(file), status: opened, loadMs })
}

await send('Emulation.clearDeviceMetricsOverride')
await ev(`window.amber.settings.update({ theme: ${JSON.stringify(originalTheme)} }).then(() => 1)`)
ws.close()
console.table(results)
if (problems.length) {
  console.error(`\n${problems.length} problem(s):`)
  for (const p of problems) console.error(`  - ${p}`)
  process.exit(1)
}
console.log(`\nUI smoke passed: ${files.length} scripts × ${SIZES.length} sizes × ${VIEWS.length} views, no errors, no horizontal overflow.`)
