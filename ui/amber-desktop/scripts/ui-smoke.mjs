// UI smoke test over the Chrome DevTools Protocol, against a running DEVELOPMENT instance.
//
//   npm run dev -- --remoteDebuggingPort 9334          (in one terminal)
//   node scripts/ui-smoke.mjs --port 9334 [--theme amber-dark] [--languages en,zh-CN,es] [--zoom 1,0.8,1.5 (app zoom)]
//        [--reduced-motion] [--keyboard] [--big huge.sql] [--timeout ms] [--no-samples] [extra.sql ...]
//
// Per language (default: the current one) and zoom factor it also opens the bottom panels, every
// Settings tab and the About dialog once per size; --keyboard tabs through the window and reports
// controls without a visible focus ring; --big records how long opening and analyzing a large
// script takes (and checks its views at 1280×800 only).
// Opens every bundled sample plus the given files, visits every view at 960×600, 1280×800 and
// 1920×1080, and fails (exit code 1) on console errors, uncaught exceptions or unhandled rejections,
// horizontal page overflow, clipped tab bars and toolbars, or a renderer that stops answering.
// Needs the dev-only `__amberDev` hook, so it does not run against a packaged app. It never changes
// settings except the theme and the language, and puts both back at the end.
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
const languages = (opt('languages', null) ?? opt('language', '') ?? '').split(',').filter(Boolean)
const zooms = opt('zoom', '1').split(',').map(Number).filter((z) => z > 0)
const big = opt('big', null)
const flag = (name) => {
  const i = args.indexOf(`--${name}`)
  if (i >= 0) args.splice(i, 1)
  return i >= 0
}
const reducedMotion = flag('reduced-motion')
const keyboard = flag('keyboard')
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
const VIEWS = ['outline', 'lifecycle', 'dataflow', 'lineage', 'er', 'statement', 'structure', 'playback']
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
const originalLanguage = await ev('window.amber.settings.get().then((s) => s.language ?? "en")')
if (reducedMotion) await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] })

const dialectOf = (file) => Object.keys(DIALECTS).find((d) => basename(file).toLowerCase().startsWith(d)) ?? 'postgresql'
const overflow = () => ev('Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - window.innerWidth')
// Tab bars and toolbars that cut off their own content (longer labels in other languages).
const clipped = () =>
  ev(`[...document.querySelectorAll('[role=tablist], [role=toolbar]')]
    .filter((el) => el.offsetParent && el.scrollWidth > el.clientWidth + 1 && !/auto|scroll/.test(getComputedStyle(el).overflowX))
    .map((el) => (el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 60))`)
// Buttons, tabs and menu items whose label does not fit (and is not deliberately truncated).
const clippedLabels = () =>
  ev(`(() => {
    const out = []
    for (const el of document.querySelectorAll('button, [role=tab], [role=menuitem], [role=radio]')) {
      if (!el.offsetParent || !el.textContent.trim()) continue
      const cs = getComputedStyle(el)
      const wide = el.scrollWidth > el.clientWidth + 1 && !/auto|scroll/.test(cs.overflowX)
      const tall = el.scrollHeight > el.clientHeight + 2 && cs.height !== 'auto' && !/auto|scroll/.test(cs.overflowY)
      if (!wide && !tall) continue
      const ellipsis = (n) => getComputedStyle(n).textOverflow === 'ellipsis'
      if (ellipsis(el) || [...el.querySelectorAll('*')].some(ellipsis)) continue
      out.push((wide ? '' : '(wraps) ') + (el.getAttribute('aria-label') || el.textContent).trim().replace(/\\s+/g, ' ').slice(0, 50))
    }
    return [...new Set(out)]
  })()`)
const results = []
const check = async (label) => {
  const over = await overflow()
  if (over > 1) problems.push(`${context}: horizontal page overflow by ${over}px`)
  for (const c of await clipped()) problems.push(`${context}: clipped bar "${c}"`)
  for (const c of await clippedLabels()) problems.push(`${context}: clipped label "${c}"${label ? ` (${label})` : ''}`)
}
// App zoom as Electron applies it: the same window has a CSS viewport of width / zoom at a device
// pixel ratio of zoom (a CSS `zoom` on <html> would not change the viewport and misreport overflow).
let currentZoom = 1
const setSize = (w, h) =>
  send('Emulation.setDeviceMetricsOverride', { width: Math.round(w / currentZoom), height: Math.round(h / currentZoom), deviceScaleFactor: currentZoom, mobile: false })
const store = (code) => ev(`(async () => { const S = () => window.__amberDev.store.getState(); ${code} })()`)

for (const language of languages.length ? languages : [null]) {
  if (language) {
    await ev(`window.amber.settings.update({ language: ${JSON.stringify(language)} }).then(() => 1)`)
    await wait(400)
  }
  for (const zoom of zooms) {
    currentZoom = zoom
    const tag = `${language ?? 'current'}${zoom === 1 ? '' : ` zoom ${Math.round(zoom * 100)}%`}`
    let first = true
    for (const file of files) {
      try {
        await checkFile(file, tag, first)
        first = false
      } catch (error) {
        problems.push(`${context}: ${error instanceof Error ? error.message : error}`)
        // A renderer that stopped answering makes every later step fail too.
        if (/timed out/.test(String(error))) break
      }
    }
    if (big) await checkBig(big, tag)
    if (zoom === 1 && languages.length > 1) await checkLiveSwitch(tag, languages[(languages.indexOf(language) + 1) % languages.length])
    if (keyboard && zoom === 1) await checkKeyboard(tag)
  }
  currentZoom = 1
}

async function open(file) {
  const t0 = Date.now()
  const status = await ev(`(async () => {
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
  return { status, ms: Date.now() - t0 }
}

async function checkFile(file, tag, panels) {
  context = `${tag} ${basename(file)} (opening)`
  const { status: opened, ms: loadMs } = await open(file)
  if (opened === 'timeout' || String(opened).startsWith('open failed')) problems.push(`${context}: ${opened}`)
  for (const [w, h] of SIZES) {
    await setSize(w, h)
    for (const view of VIEWS) {
      context = `${tag} ${basename(file)} @${w}x${h} ${view}`
      await ev(`(window.__amberDev.store.getState().setView(${JSON.stringify(view)}), 1)`)
      await wait(view === 'dataflow' || view === 'lineage' ? 700 : 250)
      await check()
    }
    if (panels) await checkPanels(`${tag} ${basename(file)} @${w}x${h}`)
  }
  results.push({ run: tag, file: basename(file), status: opened, loadMs })
}

/** Bottom panels, every Settings tab and the About dialog. */
async function checkPanels(prefix) {
  await store(`S().setView('outline'); const st = S().analysis?.statements?.[0]; if (st) S().selectStatement(st.id, 'editor'); return 1`)
  for (const panel of ['findings', 'fixes', 'syntax']) {
    context = `${prefix} bottom panel ${panel}`
    await store(`S().setBottomPanel(${JSON.stringify(panel)}); return 1`)
    await wait(300)
    await check()
  }
  await store(`S().setBottomPanel(null); return 1`)
  for (const tab of ['general', 'analysis', 'rules', 'format', 'ai', 'connections', 'updates']) {
    context = `${prefix} settings ${tab}`
    await store(`S().openDialog('settings', ${JSON.stringify(tab)}); return 1`)
    await wait(tab === 'rules' ? 900 : 300)
    await check()
  }
  await store(`S().closeDialog('settings'); return 1`)
  context = `${prefix} about`
  await store(`S().openDialog('about'); return 1`)
  await wait(500)
  await check()
  await store(`S().closeDialog('about'); return 1`)
  await wait(200)
}

/** A large script: time to open and analyze it, and its views at one size. */
async function checkBig(file, tag) {
  await setSize(1280, 800)
  context = `${tag} ${basename(file)} (opening, big)`
  const { status, ms } = await open(file)
  if (status === 'timeout' || String(status).startsWith('open failed')) problems.push(`${context}: ${status}`)
  const timings = { open: ms }
  for (const view of VIEWS) {
    context = `${tag} ${basename(file)} @1280x800 ${view} (big)`
    const t0 = Date.now()
    await ev(`(window.__amberDev.store.getState().setView(${JSON.stringify(view)}), 1)`)
    // Wait for the renderer to paint and settle: two animation frames after the view switch.
    await ev(`new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r(1))))`, timeoutMs)
    await wait(view === 'dataflow' || view === 'lineage' ? 1500 : 300)
    timings[view] = Date.now() - t0
    await check()
  }
  results.push({ run: tag, file: basename(file), status, loadMs: ms, ...Object.fromEntries(Object.entries(timings).map(([k, v]) => [`${k}Ms`, v])) })
}

/** Switching the language while a view and a dialog are open re-renders both, without errors. */
async function checkLiveSwitch(tag, next) {
  context = `${tag} live switch to ${next}`
  await setSize(1280, 800)
  await store(`S().setView('lineage'); S().openDialog('settings', 'rules'); return 1`)
  await wait(600)
  const before = await ev(`document.querySelector('[role=dialog] h2')?.textContent ?? ''`)
  await ev(`window.amber.settings.update({ language: ${JSON.stringify(next)} }).then(() => 1)`)
  await wait(700)
  const after = await ev(`JSON.stringify({ title: document.querySelector('[role=dialog] h2')?.textContent ?? '', lang: document.documentElement.lang })`)
  const { title, lang } = JSON.parse(after)
  if (lang !== next) problems.push(`${context}: <html lang> is "${lang}"`)
  if (title === before && next !== 'en' && before !== '') problems.push(`${context}: dialog title stayed "${title}"`)
  await check()
  await ev(`window.amber.settings.update({ language: ${JSON.stringify(tag.split(' ')[0])} }).then(() => 1)`)
  await store(`S().closeDialog('settings'); return 1`)
  await wait(400)
}

/** Keyboard only: Tab through the window; every focused control must show a focus ring. */
async function checkKeyboard(tag) {
  await setSize(1280, 800)
  await store(`S().setView('outline'); document.activeElement?.blur(); return 1`)
  const seen = new Set()
  for (let i = 0; i < 80; i++) {
    for (const type of ['keyDown', 'keyUp']) await send('Input.dispatchKeyEvent', { type, key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 })
    const r = await ev(`(() => {
      const el = document.activeElement
      if (!el || el === document.body) return null
      const cs = getComputedStyle(el)
      const ring = (cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0) || (cs.boxShadow && cs.boxShadow !== 'none')
      const editor = el.classList.contains('cm-content')
      return JSON.stringify({ label: (el.getAttribute('aria-label') || el.textContent || el.tagName).trim().replace(/\\s+/g, ' ').slice(0, 40), ring: ring || editor })
    })()`)
    if (!r) continue
    const { label, ring } = JSON.parse(r)
    if (!ring && !seen.has(label)) problems.push(`${tag} keyboard: no visible focus on "${label}"`)
    seen.add(label)
  }
  results.push({ run: tag, file: '(keyboard)', status: `${seen.size} controls reached`, loadMs: 0 })
}

await send('Emulation.clearDeviceMetricsOverride')
await ev(`window.amber.settings.update({ theme: ${JSON.stringify(originalTheme)}, language: ${JSON.stringify(originalLanguage)} }).then(() => 1)`)
ws.close()
console.table(results)
if (problems.length) {
  console.error(`\n${problems.length} problem(s):`)
  for (const p of problems) console.error(`  - ${p}`)
  process.exit(1)
}
console.log(`\nUI smoke passed (${languages.join(', ') || 'current language'}; zoom ${zooms.join(', ')}${reducedMotion ? '; reduced motion' : ''}): ${files.length} scripts × ${SIZES.length} sizes × ${VIEWS.length} views, panels and dialogs, no errors, no overflow, no clipped labels.`)
