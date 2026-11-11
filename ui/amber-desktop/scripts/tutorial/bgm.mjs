// Synthesizes the Tutorial video's music: a light, cheerful loop in C major (112 BPM, C–G–Am–F), written
// sample by sample, so it needs no audio file or license. Deterministic: the same file every run.
//   node bgm.mjs --out <file.wav>
// Arrangement (32 bars, about 69 s; compose.mjs loops and fades it): bars 1–8 plucked arpeggio, bass and pad
// with light hats; bars 9–24 add kick, clap and a bell melody; bars 25–32 drop the melody again, so the end
// leads back into the start.
import fs from 'node:fs'
import path from 'node:path'

const arg = (name, fallback) => {
  const i = process.argv.indexOf(name)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}
const OUT = path.resolve(arg('--out', 'bgm.wav'))

const RATE = 44100
const BPM = 112
const BEAT = 60 / BPM
const BAR = 4 * BEAT
const BARS = 32
const LENGTH = Math.ceil((BARS * BAR + 1.5) * RATE) // a short tail for the last notes and echoes
const TAU = 2 * Math.PI

const hz = (midi) => 440 * Math.pow(2, (midi - 69) / 12)

// Deterministic noise (mulberry32).
let seed = 0x2f6b9a1d
const noise = () => {
  seed = (seed + 0x6d2b79f5) | 0
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
  return (((t ^ (t >>> 14)) >>> 0) / 4294967296) * 2 - 1
}

// Buses: dry left/right, and a send to the echo.
const L = new Float32Array(LENGTH)
const R = new Float32Array(LENGTH)
const SEND = new Float32Array(LENGTH)

/** Adds a voice: fn(t) for t in seconds since the note's start, panned (-1 left … 1 right). */
function add(start, seconds, fn, gain, pan = 0, send = 0) {
  const from = Math.round(start * RATE)
  const n = Math.min(Math.round(seconds * RATE), LENGTH - from)
  const gl = gain * Math.cos(((pan + 1) * Math.PI) / 4)
  const gr = gain * Math.sin(((pan + 1) * Math.PI) / 4)
  for (let i = 0; i < n; i++) {
    const v = fn(i / RATE)
    L[from + i] += v * gl
    R[from + i] += v * gr
    SEND[from + i] += v * gain * send
  }
}

const attack = (t, a) => (t < a ? t / a : 1)

// ---------- instruments ----------
const pluck = (f) => (t) =>
  attack(t, 0.004) * (Math.sin(TAU * f * t) * Math.exp(-t * 5.5) + 0.45 * Math.sin(TAU * 2 * f * t) * Math.exp(-t * 9) + 0.15 * Math.sin(TAU * 3 * f * t) * Math.exp(-t * 14))

const bell = (f) => (t) =>
  attack(t, 0.003) * (Math.sin(TAU * f * t) * Math.exp(-t * 2.6) + 0.35 * Math.sin(TAU * 2 * f * t) * Math.exp(-t * 4.5) + 0.12 * Math.sin(TAU * 4.01 * f * t) * Math.exp(-t * 7))

const bass = (f) => (t) => attack(t, 0.006) * Math.exp(-t * 3.2) * (Math.sin(TAU * f * t) + 0.25 * Math.sin(TAU * 2 * f * t))

const tri = (x) => (2 / Math.PI) * Math.asin(Math.sin(x))
const pad = (freqs, seconds) => (t) => {
  const env = Math.min(1, t / 0.5) * Math.min(1, (seconds - t) / 0.4)
  let v = 0
  for (const f of freqs) v += tri(TAU * f * 0.9985 * t) + tri(TAU * f * 1.0015 * t)
  return (env * v) / (2 * freqs.length)
}

const kick = (t) => {
  const phase = TAU * (48 * t + (70 / 32) * (1 - Math.exp(-t * 32)))
  return Math.sin(phase) * Math.exp(-t * 11)
}

let lastClap = 0
const clap = (t) => {
  const n = noise()
  const hp = n - lastClap // crude high-pass: keeps the bright part of the noise
  lastClap = n
  const bursts = t < 0.03 ? 0.6 + 0.4 * Math.cos(TAU * 110 * t) : 1
  return hp * bursts * Math.exp(-t * 22)
}

let lastHat = 0
const hat = (t) => {
  const n = noise()
  const hp = n - lastHat
  lastHat = n
  return hp * Math.exp(-t * 70)
}

// ---------- score ----------
// C – G – Am – F; chord tones around middle C, bass an octave and more below.
const CHORDS = [
  { tones: [60, 64, 67], bass: 48 },
  { tones: [59, 62, 67], bass: 43 },
  { tones: [57, 60, 64], bass: 45 },
  { tones: [57, 60, 65], bass: 41 }
]
const ARP = [0, 1, 2, 3, 2, 1, 2, 3] // eighths; 3 = the root an octave up

// Eight bars of melody: [bar, beat, midi, length in beats].
const MELODY = [
  [0, 0, 76, 1], [0, 1, 79, 1], [0, 2, 81, 1], [0, 3, 79, 1],
  [1, 0, 74, 1], [1, 1, 76, 1], [1, 2, 74, 1], [1, 3, 71, 1],
  [2, 0, 72, 1], [2, 1, 76, 1], [2, 2, 81, 1.5], [2, 3.5, 79, 0.5],
  [3, 0, 81, 2], [3, 2, 79, 1], [3, 3, 76, 1],
  [4, 0, 79, 1], [4, 1, 84, 1], [4, 2, 81, 1], [4, 3, 79, 1],
  [5, 0, 74, 1], [5, 1, 79, 1], [5, 2, 83, 1], [5, 3, 81, 1],
  [6, 0, 84, 1], [6, 1, 81, 1], [6, 2, 76, 1], [6, 3, 81, 1],
  [7, 0, 79, 1], [7, 1, 77, 1], [7, 2, 76, 1], [7, 3, 74, 1]
]

for (let bar = 0; bar < BARS; bar++) {
  const t0 = bar * BAR
  const chord = CHORDS[bar % 4]
  const full = bar >= 8 && bar < 24
  const drums = bar >= 4 && bar < 28

  ARP.forEach((k, i) => {
    const midi = k === 3 ? chord.tones[0] + 12 : chord.tones[k]
    add(t0 + (i * BEAT) / 2, 0.7, pluck(hz(midi)), i % 2 ? 0.1 : 0.13, -0.35, 0.35)
  })

  add(t0, BEAT * 1.4, bass(hz(chord.bass)), 0.3)
  add(t0 + 2 * BEAT, BEAT * 1.4, bass(hz(chord.bass)), 0.26)
  add(t0 + 3.5 * BEAT, BEAT * 0.5, bass(hz(chord.bass + 12)), 0.18)

  add(t0, BAR + 0.05, pad(chord.tones.map(hz), BAR + 0.05), full ? 0.06 : 0.075, 0, 0.2)

  for (let e = 0; e < 8; e++) if (e % 2 === 1 || full) add(t0 + (e * BEAT) / 2, 0.08, hat, e % 2 ? 0.07 : 0.035, 0.3)
  if (drums) {
    add(t0, 0.35, kick, 0.5)
    add(t0 + 2 * BEAT, 0.35, kick, 0.42)
    if (full) {
      add(t0 + BEAT, 0.25, clap, 0.16, 0.1, 0.2)
      add(t0 + 3 * BEAT, 0.25, clap, 0.16, 0.1, 0.2)
    }
  }

  if (full) {
    for (const [b, beat, midi, len] of MELODY) {
      if (b !== (bar - 8) % 8) continue
      add(t0 + beat * BEAT, len * BEAT + 0.9, bell(hz(midi)), 0.13, 0.3, 0.45)
    }
  }
}

// ---------- echo (ping-pong, dotted eighth / quarter) and master ----------
const dl = Math.round(0.75 * BEAT * RATE)
const dr = Math.round(BEAT * RATE)
const el = new Float32Array(LENGTH)
const er = new Float32Array(LENGTH)
for (let i = 0; i < LENGTH; i++) {
  el[i] = (i >= dl ? SEND[i - dl] + 0.35 * er[i - dl] : 0)
  er[i] = (i >= dr ? SEND[i - dr] + 0.35 * el[i - dr] : 0)
}
let peak = 0
for (let i = 0; i < LENGTH; i++) {
  L[i] = Math.tanh(1.1 * (L[i] + 0.3 * el[i]))
  R[i] = Math.tanh(1.1 * (R[i] + 0.3 * er[i]))
  peak = Math.max(peak, Math.abs(L[i]), Math.abs(R[i]))
}
const norm = 0.89 / peak

// ---------- 16-bit stereo WAV ----------
const data = Buffer.alloc(LENGTH * 4)
for (let i = 0; i < LENGTH; i++) {
  data.writeInt16LE(Math.round(L[i] * norm * 32767), i * 4)
  data.writeInt16LE(Math.round(R[i] * norm * 32767), i * 4 + 2)
}
const header = Buffer.alloc(44)
header.write('RIFF', 0)
header.writeUInt32LE(36 + data.length, 4)
header.write('WAVEfmt ', 8)
header.writeUInt32LE(16, 16)
header.writeUInt16LE(1, 20)
header.writeUInt16LE(2, 22)
header.writeUInt32LE(RATE, 24)
header.writeUInt32LE(RATE * 4, 28)
header.writeUInt16LE(4, 32)
header.writeUInt16LE(16, 34)
header.write('data', 36)
header.writeUInt32LE(data.length, 40)
fs.mkdirSync(path.dirname(OUT), { recursive: true })
fs.writeFileSync(OUT, Buffer.concat([header, data]))
console.log(`music: ${OUT} (${(LENGTH / RATE).toFixed(1)} s, synthesized)`)
