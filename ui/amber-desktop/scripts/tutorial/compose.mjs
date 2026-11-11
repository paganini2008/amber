// Composes the Tutorial video (started by run.mjs):
//   node compose.mjs --work <dir> --out <mp4> [--bgm <audio file> [--credit <music credit>]]
// Reads <work>/raw.mov, <work>/marks.json and <work>/meta.json (crop of the app window in the capture, and how
// long the capture ran before the first mark). Renders the title cards from cards.html with headless Chrome,
// joins intro card → app → outro card with cross-fades, burns in English subtitles and mixes the music.
// Also writes <out dir>/amber-tutorial-poster.jpg.
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'

const HERE = path.dirname(new URL(import.meta.url).pathname)
const arg = (name, fallback) => {
  const i = process.argv.indexOf(name)
  return i >= 0 ? process.argv[i + 1] : fallback
}
const WORK = arg('--work')
const OUT = arg('--out')
const BGM = arg('--bgm', '')
const CREDIT = arg('--credit', '') // the music credit on the outro card
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const INTRO = 6
const OUTRO = 7
const XF = 0.8
const MAX_SECONDS = 180

function run(cmd, args, quiet = false) {
  const r = spawnSync(cmd, args, { stdio: quiet ? 'ignore' : ['ignore', 'inherit', 'inherit'] })
  if (r.status !== 0) throw new Error(`${cmd} failed (${r.status})`)
}
function duration(file) {
  const r = spawnSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', file], { encoding: 'utf8' })
  return Number(r.stdout.trim())
}
const ts = (t) => {
  const v = Math.max(0, t)
  const h = Math.floor(v / 3600)
  const m = Math.floor((v % 3600) / 60)
  const s = (v % 60).toFixed(2).padStart(5, '0')
  return `${h}:${String(m).padStart(2, '0')}:${s}`
}

const raw = path.join(WORK, 'raw.mov')
const marks = JSON.parse(fs.readFileSync(path.join(WORK, 'marks.json'), 'utf8'))
const meta = JSON.parse(fs.readFileSync(path.join(WORK, 'meta.json'), 'utf8'))
const lead = meta.leadSeconds
const appStart = Math.max(0, lead - 0.3)
const appLen = duration(raw) - appStart
const offset = INTRO - XF
const total = INTRO + appLen + OUTRO - 2 * XF
console.log(`app part ${appLen.toFixed(1)} s, video ${total.toFixed(1)} s`)
if (total > MAX_SECONDS) throw new Error(`the video would be ${total.toFixed(0)} s, more than ${MAX_SECONDS} s`)

// Title cards: cards.html#intro and #outro at 1920x1080.
for (const card of ['intro', 'outro']) {
  run(CHROME, ['--headless=new', '--disable-gpu', '--hide-scrollbars', '--allow-file-access-from-files', '--force-device-scale-factor=1',
    '--window-size=1920,1080', `--screenshot=${path.join(WORK, `card-${card}.png`)}`, `file://${path.join(HERE, 'cards.html')}?music=${encodeURIComponent(BGM ? CREDIT : '')}#${card}`], true)
}

// Subtitles (ASS): bottom centre on a dark box; each one lasts until the next mark.
const events = []
marks.forEach((m, i) => {
  if (!m.text) return
  const start = offset + m.t + lead - appStart
  const next = i + 1 < marks.length ? marks[i + 1].t : m.t + 6
  const end = offset + next + lead - appStart - 0.15
  events.push(`Dialogue: 0,${ts(start)},${ts(end)},Sub,,0,0,0,,${m.text.replace(/[{}]/g, '').replace(/\\/g, '\\\\')}`)
})
const ass = `[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 0

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Sub,Helvetica Neue,42,&H00FFFFFF,&H00FFFFFF,&H1A102A33,&H00000000,0,0,0,0,100,100,0,0,3,14,0,2,160,160,46,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
${events.join('\n')}
`
const assFile = path.join(WORK, 'subs.ass')
fs.writeFileSync(assFile, ass)

const c = meta.crop
const vf = [
  `[0:v]scale=1920:1080,format=yuv420p,setsar=1,fps=30[intro]`,
  `[1:v]trim=start=${appStart},setpts=PTS-STARTPTS,crop=${c.w}:${c.h}:${c.x}:${c.y},scale=1920:1080:flags=lanczos,format=yuv420p,setsar=1,fps=30[app]`,
  `[2:v]scale=1920:1080,format=yuv420p,setsar=1,fps=30[outro]`,
  `[intro][app]xfade=transition=fade:duration=${XF}:offset=${INTRO - XF}[a1]`,
  `[a1][outro]xfade=transition=fade:duration=${XF}:offset=${INTRO + appLen - 2 * XF}[v0]`,
  `[v0]ass='${assFile}'[v]`
]
const inputs = ['-loop', '1', '-t', String(INTRO), '-framerate', '30', '-i', path.join(WORK, 'card-intro.png'), '-i', raw,
  '-loop', '1', '-t', String(OUTRO), '-framerate', '30', '-i', path.join(WORK, 'card-outro.png')]
const maps = ['-map', '[v]']
if (BGM && fs.existsSync(BGM)) {
  inputs.push('-i', BGM)
  vf.push(`[3:a]aloop=loop=-1:size=2e9,atrim=0:${total},volume=0.32,afade=t=in:st=0:d=2,afade=t=out:st=${total - 3.5}:d=3.5[a]`)
  maps.push('-map', '[a]', '-c:a', 'aac', '-b:a', '128k')
} else {
  console.log('no music (set AMBER_TUTORIAL_BGM to an audio file you may use)')
}
fs.mkdirSync(path.dirname(OUT), { recursive: true })
run('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...inputs, '-filter_complex', vf.join(';'), ...maps,
  '-c:v', 'libx264', '-preset', 'slow', '-crf', '23', '-profile:v', 'high', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', '-t', String(total), OUT])

// Poster: a frame from the ER diagram part, else from the middle.
const er = marks.find((m) => m.text.startsWith('The ER diagram'))
const at = offset + (er ? er.t + 5 : appLen / 2) + lead - appStart
run('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-ss', at.toFixed(2), '-i', OUT, '-frames:v', '1', '-q:v', '3',
  path.join(path.dirname(OUT), 'amber-tutorial-poster.jpg')])
console.log(`wrote ${OUT} (${(fs.statSync(OUT).size / 1e6).toFixed(1)} MB) and its poster`)
