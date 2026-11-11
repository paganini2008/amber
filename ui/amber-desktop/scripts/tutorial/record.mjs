// Drives the Amber window through the Tutorial and records it (started by run.mjs).
//   node record.mjs --port <cdp> --file <sql> --out <dir> [--real]
// Without --real it drives the app through the DevTools protocol only (no real mouse, no recording) and saves a
// screenshot per subtitle in <out>/dry/. With --real it moves the real mouse and keyboard (CGEvents through
// mouse.jxa) and captures the screen with ffmpeg into <out>/raw.mov. Always writes <out>/marks.json.
import { spawn, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'

const HERE = path.dirname(new URL(import.meta.url).pathname)
const arg = (name, fallback) => {
  const i = process.argv.indexOf(name)
  return i >= 0 ? process.argv[i + 1] : fallback
}
const PORT = Number(arg('--port', '9360'))
const REAL = process.argv.includes('--real')
const FILE = arg('--file', '/tmp/amber-tutorial/nightly_orders.sql')
const DIR = arg('--out', path.join(HERE, 'out'))
const SCREEN = arg('--screen', '1') // avfoundation index of the main screen
fs.mkdirSync(DIR, { recursive: true })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ---------- CDP ----------
const page = (await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()).find((t) => t.type === 'page')
const ws = new WebSocket(page.webSocketDebuggerUrl)
await new Promise((r) => ws.addEventListener('open', r))
let seq = 0
const pending = new Map()
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id) }
})
const send = (method, params = {}) => new Promise((r) => { const id = ++seq; pending.set(id, r); ws.send(JSON.stringify({ id, method, params })) })
const PRELUDE = `
const vis=(e)=>!!(e&&e.offsetParent!==null&&e.getBoundingClientRect().width>0);
const pick=(t,sel='button,[role=tab],[role=menuitem],[role=menuitemcheckbox],[role=option],a,[role=button],[role=treeitem],li,label,summary')=>[...document.querySelectorAll(sel)].filter(vis).filter(e=>e.textContent.replace(/\\s+/g,' ').trim().startsWith(t)).sort((a,b)=>a.textContent.length-b.textContent.length)[0];
const exact=(t,sel='button')=>[...document.querySelectorAll(sel)].filter(vis).find(e=>e.textContent.trim()===t);
const tabEl=(n)=>[...document.querySelectorAll('[role=tab]')].find(e=>e.textContent.trim()===n);
const step=(t)=>[...document.querySelectorAll('[role=tree][aria-label=Statements] [role=treeitem]')].filter(vis).find(e=>e.textContent.replace(/\\s+/g,' ').trim().startsWith(t));
const panelBtn=(p)=>[...document.querySelectorAll('button[aria-expanded]')].filter(vis).find(e=>(e.getAttribute('aria-label')||'').startsWith(p));
const label=(l)=>[...document.querySelectorAll('[aria-label]')].filter(vis).find(e=>e.getAttribute('aria-label')===l);
`
async function ev(js) {
  const r = await send('Runtime.evaluate', { expression: `(async()=>{${PRELUDE}\n${js}})()`, awaitPromise: true, returnByValue: true })
  if (r.result?.exceptionDetails) throw new Error(`eval failed: ${js}\n${r.result.exceptionDetails.exception?.description}`)
  return r.result?.result?.value
}
// Rect of the element an expression returns, in viewport CSS px.
async function rectOf(expr) {
  const r = await ev(`const e=(${expr}); if(!e) return null; e.scrollIntoView({block:'nearest',inline:'nearest'}); const b=e.getBoundingClientRect(); return {x:b.x,y:b.y,w:b.width,h:b.height}`)
  if (!r) throw new Error(`not found: ${expr}`)
  return r
}
async function waitFor(js, timeout = 15000, what = js) {
  const end = Date.now() + timeout
  while (Date.now() < end) { if (await ev(`return !!(${js})`)) return; await sleep(150) }
  throw new Error(`timeout waiting for ${what}`)
}

// ---------- input: real (CGEvents via JXA) or CDP ----------
const win = await ev('return [screenX, screenY]')
let cursor = { x: 676, y: 400 } // viewport coords, for the dry run
function jxa(actions) {
  const r = spawnSync('osascript', ['-l', 'JavaScript', path.join(HERE, 'mouse.jxa'), JSON.stringify(actions)], { encoding: 'utf8' })
  if (r.status !== 0) throw new Error(`mouse.js: ${r.stderr}`)
}
async function moveTo(x, y, ms) {
  if (REAL) jxa([{ move: [win[0] + x, win[1] + y], ms }])
  else { await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y }); await sleep(ms ?? 400) }
  cursor = { x, y }
}
async function clickHere() {
  if (REAL) jxa([{ click: true }])
  else {
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: cursor.x, y: cursor.y, button: 'left', clickCount: 1 })
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: cursor.x, y: cursor.y, button: 'left', clickCount: 1 })
  }
}
// Move to an element (slightly off-centre, as a hand would) and optionally click it.
async function go(expr, { click = true, dx = 0, dy = 0, ms, pause = 220 } = {}) {
  const r = await rectOf(expr)
  const jx = (Math.random() - 0.5) * Math.min(10, r.w * 0.2)
  const jy = (Math.random() - 0.5) * Math.min(4, r.h * 0.2)
  await moveTo(r.x + r.w / 2 + dx + jx, r.y + r.h / 2 + dy + jy, ms)
  await sleep(pause)
  if (click) await clickHere()
  return r
}
async function typeText(text, cps = 13) {
  if (REAL) jxa([{ type: text, cps }])
  else { await send('Input.insertText', { text }); await sleep(text.length * 60) }
}
async function key(k, mods = {}) {
  if (REAL) jxa([{ key: k, ...mods }])
}
async function scrollAt(expr, lines) {
  const r = await rectOf(expr)
  await moveTo(r.x + r.w / 2, r.y + Math.min(r.h / 2, 80), 500)
  if (REAL) jxa([{ scroll: lines }])
  else await send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: cursor.x, y: cursor.y, deltaX: 0, deltaY: -lines * 40 })
}
// Look around a bit: hover over a few elements without clicking.
async function glance(exprs, pause = 650) {
  for (const e of exprs) { await go(e, { click: false, pause }) }
}

// ---------- recording and subtitles ----------
let ff = null
let t0 = Date.now()
const marks = []
let shotNo = 0
async function snap() {
  if (REAL) return
  fs.mkdirSync(path.join(DIR, 'dry'), { recursive: true })
  const r = await send('Page.captureScreenshot', { format: 'png', clip: { x: 0, y: 0, width: 1352, height: 760, scale: 0.6 } })
  fs.writeFileSync(path.join(DIR, 'dry', String(++shotNo).padStart(2, '0') + '.png'), Buffer.from(r.result.data, 'base64'))
}
function sub(text) {
  void snap()
  const t = (Date.now() - t0) / 1000
  marks.push({ t, text })
  console.log(`[${t.toFixed(1)}s] ${text}`)
}
async function startRecording() {
  if (!REAL) { t0 = Date.now(); return }
  const out = path.join(DIR, 'raw.mov')
  ff = spawn('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 'avfoundation', '-capture_cursor', '1', '-capture_mouse_clicks', '1',
    '-framerate', '30', '-pixel_format', 'nv12', '-i', `${SCREEN}:none`, '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '16', '-pix_fmt', 'yuv420p', '-y', out],
  { stdio: ['pipe', 'inherit', 'inherit'] })
  await sleep(1500) // let capture start before the first mark
  t0 = Date.now()
}
async function stopRecording() {
  if (!ff) return
  ff.stdin.write('q')
  await new Promise((r) => ff.on('exit', r))
}

// ---------- input source ----------
let savedInput = null
function inputSource(arg) {
  const r = spawnSync('osascript', ['-l', 'JavaScript', path.join(HERE, 'input-source.jxa'), arg], { encoding: 'utf8' })
  return r.status === 0 ? r.stdout.trim() : null
}

// ---------- the tutorial ----------
// Import by pasting: the native Open dialog would show the folders and file names of whoever records.
async function openScript() {
  const sql = fs.readFileSync(FILE, 'utf8')
  const saved = REAL ? spawnSync('pbpaste', [], { encoding: 'utf8' }).stdout : null // restored right after the paste
  if (REAL) spawnSync('pbcopy', [], { input: sql })
  await go(`pick('Paste SQL')`)
  await waitFor(`document.querySelector('[role=dialog] textarea')`)
  await sleep(500)
  await go(`document.querySelector('[role=dialog] input')`)
  if (REAL) await key('a', { cmd: true })
  else await ev(`const el=document.querySelector('[role=dialog] input');Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value').set.call(el,'');el.dispatchEvent(new Event('input',{bubbles:true}));return 1`)
  await typeText(path.basename(FILE), 12)
  await sleep(300)
  await go(`document.querySelector('[role=dialog] textarea')`)
  if (REAL) {
    await key('v', { cmd: true })
    await sleep(300)
    spawnSync('pbcopy', [], { input: saved ?? '' })
  }
  else await ev(`const el=document.querySelector('[role=dialog] textarea');Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value').set.call(el,${JSON.stringify(sql)});el.dispatchEvent(new Event('input',{bubbles:true}));return 1`)
  await sleep(1200)
  await go(`[...document.querySelectorAll('[role=dialog] button')].find(e=>e.textContent.trim()==='Analyze')`)
  await waitFor(`tabEl('Outline')`, 20000, 'analysis')
}

async function run() {
  if (REAL) {
    spawnSync('osascript', ['-e', 'tell application "System Events" to set frontmost of process "Electron" to true'])
    await sleep(500)
    // Typed text goes through the input source of the app in front: an input method (e.g. Pinyin) would change it.
    savedInput = inputSource('get')
    const english = inputSource('english')
    if (!english?.startsWith('com.apple.keylayout.')) throw new Error(`could not switch to an English keyboard layout (now ${english})`)
    console.log(`  input source: ${english} (was ${savedInput})`)
    jxa([{ move: [win[0] + 676, win[1] + 380], ms: 300 }])
    await sleep(800)
  }
  await startRecording()
  sub('Amber helps you maintain legacy SQL scripts. Start by bringing in the script you inherited: open the file, or paste it.')
  await sleep(1800)
  await openScript()
  await sleep(1200)

  sub('Everything you see next is offline. Amber reads the script; it never runs your SQL.')
  await glance([`step('Drop temp table tmp_day_orders')`, `step('Create temp table tmp_day_or')`, `step('Insert into report_region')`], 700)
  sub('The Outline: every step of the script in one line, in the order it runs.')
  await go(`step('Create temp table tmp_day_or')`)
  await sleep(2600)

  sub('Lifecycle: where each table is created, read, written and dropped.')
  await go(`tabEl('Lifecycle')`)
  await sleep(4200)

  sub('Structure: a nested query as boxes, in the order SQL actually evaluates it.')
  await go(`step('Query demo.orders, demo.cu')`)
  await sleep(700)
  await go(`tabEl('Structure')`)
  await sleep(4500)

  sub('Lineage: where every column comes from, hop by hop.')
  await go(`tabEl('Lineage')`)
  await sleep(1500)
  await go(`[...document.querySelectorAll('button,[role=option],li')].filter(vis).find(e=>e.textContent.trim().startsWith('total'))`, { pause: 300 })
  await sleep(3800)

  sub('Risks are found offline too: NOT IN with a subquery, functions on filtered columns, a correlated subquery.')
  await go(`panelBtn('Findings')`)
  await sleep(1200)
  await glance([`pick('Function on a filtered column')`, `pick('NOT IN with a subquery') || pick('Correlated subquery in the select list')`], 900)
  await sleep(1600)
  await go(`panelBtn('Findings')`)
  await sleep(600)

  sub('The ER diagram is inferred from the joins: orders → customers, many to one.')
  await go(`tabEl('ER diagram')`)
  await sleep(2500)
  await go(`document.querySelector('.react-flow__edge-interaction, .react-flow__edge path')`, { pause: 400 })
  await sleep(3000)
  await go(`label('Close') || exact('Close')`, { pause: 200 }).catch(() => {})
  await sleep(600)

  sub('Online mode is optional and read-only: it reads table metadata from your database, never data.')
  await go(`label('Connection mode')`)
  await sleep(1100)
  await go(`[...document.querySelectorAll('[role^=menuitem]')].find(e=>e.textContent.includes('Local MySQL'))`)
  await sleep(700)
  if (!(await ev(`return !!document.querySelector('[role=menu]')`))) { await go(`label('Connection mode')`); await sleep(900) }
  await go(`[...document.querySelectorAll('[role^=menuitem]')].find(e=>e.textContent.trim()==='Online')`)
  await waitFor(`label('Connection mode') && label('Connection mode').textContent.startsWith('Online · 1')`, 20000, 'online with 1 connection')
  await waitFor(`document.body.innerText.includes('Database metadata confirmed')`, 30000, 'metadata loaded')
  await sleep(2500)
  sub('Real keys and NOT NULL columns: the relationship is confirmed, and the NOT IN warning is gone.')
  await glance([`[...document.querySelectorAll('[role=status],div')].filter(vis).filter(e=>e.textContent.trim().startsWith('Database metadata confirmed')).sort((a,b)=>a.textContent.length-b.textContent.length)[0]`, `panelBtn('Findings')`], 1400)
  await sleep(2400)

  sub('Suggestions: every finding with its fix. Amber fixes what it can prove; the rest goes to the AI.')
  await go(`tabEl('Suggestions')`)
  await waitFor(`exact('Refactor script')`, 20000, 'Suggestions view')
  await waitFor(`!document.body.innerText.includes('Looking for fixes')`, 30000, 'fixes').catch(() => {})
  await sleep(1500)
  await glance([`pick('Fixable here')`, `pick('Needs AI')`], 900)
  await sleep(1500)

  sub('Refactor script: every rewrite that keeps the result the same, pass after pass, then a logic check.')
  await go(`exact('Refactor script')`)
  await waitFor(`document.querySelector('[role=dialog]')`, 10000, 'refactor dialog')
  await sleep(2200)
  // keep the layout, so Change impact shows only the rewrites
  const layout = `document.querySelector('[role=dialog] input[type=checkbox]')`
  if (await ev(`return !!(${layout})?.checked`)) { await go(layout); await sleep(700) }
  await go(`[...document.querySelectorAll('[role=dialog] button')].find(e=>e.textContent.trim()==='Refactor')`)
  await waitFor(`exact('Apply to editor')`, 60000, 'refactoring in Change impact')
  await sleep(3500)
  sub('Same conditions, values, sources and outputs as before. Apply is one step you can undo; nothing runs.')
  await glance([`[...document.querySelectorAll('p,div,span')].filter(vis).filter(e=>e.textContent.trim().startsWith('Logic check:')).sort((a,b)=>a.textContent.length-b.textContent.length)[0]`], 2600)
  await go(`exact('Apply to editor')`)
  await waitFor(`exact('Refactor script')`, 15000, 'back in Suggestions')
  await sleep(2400)

  sub('Want a second opinion? Ask AI: a local Ollama model or any OpenAI-compatible service.')
  await waitFor(`document.querySelector('textarea[placeholder^="For example"]')`, 10000, 'AI panel in Suggestions')
  await scrollAt(`document.querySelector('textarea[placeholder^="For example"]')`, -2).catch(() => {})
  await sleep(1200)
  await go(`document.querySelector('textarea[placeholder^="For example"]')`)
  await typeText('orders grows by about 1M rows a day.', 14)
  await sleep(500)
  sub('Preview exactly what is sent. Names are masked, and the mapping stays on your computer.')
  await go(`exact('Preview context')`)
  await waitFor(`exact('Send')`, 30000, 'context preview')
  await sleep(2200)
  const box = `exact('Send').parentElement.querySelector('input[type=checkbox]')`
  if (await ev(`return !!(${box})`)) { await go(box); await sleep(600) }
  console.log('  send enabled:', await ev(`return !exact('Send').disabled`))
  await go(`exact('Send')`)
  sub('Suggestions stream in, with risks spelled out. Amber never runs AI-generated SQL.')
  await sleep(4000)
  await waitFor(`document.body.innerText.match(/\\d+ suggestions?/)`, 60000, 'AI answer').catch(() => {})
  await sleep(1500)
  await scrollAt(`document.querySelector('textarea[placeholder^="For example"]')`, -6).catch(() => {})
  await sleep(4000)
  sub('')
  await sleep(500)
}

try {
  await run()
} catch (e) {
  console.error('FAILED:', e.message)
  process.exitCode = 1
} finally {
  await stopRecording()
  if (savedInput) inputSource(savedInput)
  fs.writeFileSync(path.join(DIR, 'marks.json'), JSON.stringify(marks, null, 1))
  ws.close()
}
