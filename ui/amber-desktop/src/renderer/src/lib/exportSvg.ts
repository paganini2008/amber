import type { ThemeTokens } from '@shared/themes'
import sans400 from '@fontsource/ibm-plex-sans/files/ibm-plex-sans-latin-400-normal.woff2?url'
import sans600 from '@fontsource/ibm-plex-sans/files/ibm-plex-sans-latin-600-normal.woff2?url'
import mono400 from '@fontsource/ibm-plex-mono/files/ibm-plex-mono-latin-400-normal.woff2?url'
import type { FlowView, Trace } from './dataflow'
import type { LayoutResult } from './layout'
import type { LineageIndex } from './lineage'
import { CARD, rowAnchor, type LineageViewModel } from './lineageView'
import { cellKey, markFor, type MatrixModel } from './lifecycle'
import type { AnalysisIndex } from './model'
import { BOX, kindColorKey, structurePalette, type StructureBox } from './structure'

/**
 * Vector exports generated from the view models (not screenshots), styled with the current
 * theme's tokens. PNG is rasterized from the same SVG.
 */

const SANS = "'IBM Plex Sans', 'Segoe UI', Helvetica, Arial, sans-serif"
const MONO = "'IBM Plex Mono', Menlo, Consolas, monospace"

export function escapeXml(text: string): string {
  return text.replace(/[<>&"']/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' })[c]!)
}

function truncate(text: string, maxChars: number): string {
  return text.length > maxChars ? `${text.slice(0, Math.max(1, maxChars - 1))}…` : text
}

function mix(a: string, b: string, t: number): string {
  const pa = parseInt(a.slice(1), 16)
  const pb = parseInt(b.slice(1), 16)
  const ch = (p: number, s: number): number => (p >> s) & 255
  const m = (s: number): number => Math.round(ch(pa, s) * (1 - t) + ch(pb, s) * t)
  return `#${((m(16) << 16) | (m(8) << 8) | m(0)).toString(16).padStart(6, '0')}`
}

export function flowToSvg(view: FlowView, layout: LayoutResult, t: ThemeTokens, trace: Trace | null, title: string): string {
  const pad = 32
  const header = 40
  let maxX = 0
  let maxY = 0
  for (const n of view.nodes) {
    const p = layout.positions.get(n.id)
    if (!p) continue
    maxX = Math.max(maxX, p.x + n.width)
    maxY = Math.max(maxY, p.y + n.height)
  }
  const width = Math.ceil(maxX + pad * 2)
  const height = Math.ceil(maxY + pad * 2 + header)
  const edgeRest = mix(t.inkMuted, t.canvas, 0.55)
  const surface2 = mix(t.surface, t.ink, 0.08)
  const ox = pad
  const oy = pad + header
  const parts: string[] = []
  parts.push(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" font-family="${SANS}">`,
    `<defs>`,
    `<marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="${edgeRest}"/></marker>`,
    `<marker id="arrow-lit" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="${t.amber}"/></marker>`,
    `</defs>`,
    `<rect width="100%" height="100%" fill="${t.canvas}"/>`,
    `<text x="${pad}" y="${pad + 8}" font-size="16" font-weight="600" fill="${t.ink}">${escapeXml(title)}</text>`
  )
  const byId = new Map(view.nodes.map((n) => [n.id, n]))
  for (const e of view.edges) {
    const s = byId.get(e.source)
    const d = byId.get(e.target)
    const ps = layout.positions.get(e.source)
    const pd = layout.positions.get(e.target)
    if (!s || !d || !ps || !pd) continue
    const x1 = ox + ps.x + s.width
    const y1 = oy + ps.y + s.height / 2
    const x2 = ox + pd.x
    const y2 = oy + pd.y + d.height / 2
    const dx = Math.max(30, Math.abs(x2 - x1) / 2)
    const lit = trace ? e.members.some((m) => trace.edges.has(m)) : false
    const dim = trace && !lit
    const dash = e.confidence === 'PARTIAL' ? ' stroke-dasharray="6 4"' : e.confidence === 'UNRESOLVED' ? ' stroke-dasharray="2 4"' : ''
    parts.push(
      `<path d="M${x1},${y1} C${x1 + dx},${y1} ${x2 - dx},${y2} ${x2},${y2}" fill="none" stroke="${lit ? t.amber : edgeRest}" stroke-width="${lit ? 2.25 : 1.25}"${dash}${dim ? ' opacity="0.25"' : ''} marker-end="url(#${lit ? 'arrow-lit' : 'arrow'})"/>`
    )
  }
  for (const n of view.nodes) {
    const p = layout.positions.get(n.id)
    if (!p) continue
    const x = ox + p.x
    const y = oy + p.y
    const lit = trace ? (n.type === 'object' ? trace.objects.has(n.objectKey!) : n.statementIds.some((s) => trace.statements.has(s))) : false
    const dim = trace && !lit
    const isObject = n.type === 'object'
    const fill = isObject ? t.surface : surface2
    const stroke = lit ? t.amber : isObject ? mix(t.line, t.inkMuted, 0.4) : t.line
    const dash = n.confidence === 'PARTIAL' ? ' stroke-dasharray="5 3"' : n.confidence === 'UNRESOLVED' ? ' stroke-dasharray="2 3"' : ''
    const chars = Math.floor((n.width - 20) / 7.4)
    parts.push(`<g${dim ? ' opacity="0.32"' : ''}>`)
    if (n.type === 'group') parts.push(`<rect x="${x + 4}" y="${y + 4}" width="${n.width}" height="${n.height}" rx="3" fill="${surface2}" stroke="${t.line}"/>`)
    parts.push(
      `<rect x="${x}" y="${y}" width="${n.width}" height="${n.height}" rx="${isObject ? 6 : 3}" fill="${fill}" stroke="${stroke}" stroke-width="${lit ? 2 : 1.25}"${dash}/>`,
      `<text x="${x + 10}" y="${y + 19}" font-size="12.5" ${isObject ? `font-family="${MONO}"` : 'font-weight="600"'} fill="${lit && isObject ? t.amberInk : t.ink}">${escapeXml(truncate(n.label, chars))}</text>`,
      `<text x="${x + 10}" y="${y + 35}" font-size="11" fill="${t.inkMuted}">${escapeXml(truncate(n.sublabel, chars + 2))}</text>`,
      `</g>`
    )
  }
  parts.push('</svg>')
  return parts.join('\n')
}

export function matrixToSvg(matrix: MatrixModel, _index: AnalysisIndex, t: ThemeTokens, trace: Trace | null, title: string): string {
  const pad = 24
  const nameW = 260
  const colW = 26
  const rowH = 24
  const headH = 30
  const titleH = 36
  const width = pad * 2 + nameW + matrix.columns.length * colW
  const height = pad * 2 + titleH + headH + matrix.rows.length * rowH
  const ox = pad
  const oy = pad + titleH
  const parts: string[] = [
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" font-family="${SANS}">`,
    `<rect width="100%" height="100%" fill="${t.canvas}"/>`,
    `<text x="${pad}" y="${pad + 14}" font-size="16" font-weight="600" fill="${t.ink}">${escapeXml(title)}</text>`,
    `<rect x="${ox}" y="${oy}" width="${width - pad * 2}" height="${height - oy - pad}" fill="${t.surface}" stroke="${t.line}"/>`
  ]
  matrix.columns.forEach((step, i) => {
    const x = ox + nameW + i * colW + colW / 2
    const lit = trace?.statements.has(step)
    parts.push(
      `<text x="${x}" y="${oy + headH - 10}" font-size="10" font-family="${MONO}" text-anchor="middle" fill="${lit ? t.amberInk : t.inkMuted}">${step + 1}</text>`
    )
  })
  parts.push(`<line x1="${ox}" y1="${oy + headH}" x2="${width - pad}" y2="${oy + headH}" stroke="${t.line}"/>`)
  parts.push(`<line x1="${ox + nameW}" y1="${oy}" x2="${ox + nameW}" y2="${height - pad}" stroke="${t.line}"/>`)
  matrix.rows.forEach((row, r) => {
    const y = oy + headH + r * rowH
    if (row.type === 'header') {
      parts.push(`<text x="${ox + 10}" y="${y + 17}" font-size="12" font-weight="600" fill="${t.ink}">${escapeXml(row.label)} (${row.count})</text>`)
      return
    }
    const rowLit = trace?.objects.has(row.key)
    parts.push(
      `<text x="${ox + 10}" y="${y + 16}" font-size="11.5" font-family="${MONO}" fill="${rowLit ? t.amberInk : t.ink}">${escapeXml(truncate(row.label, 32))}</text>`
    )
    matrix.columns.forEach((step, i) => {
      const accesses = matrix.cells.get(cellKey(row.key, step))
      if (!accesses) return
      const m = markFor(accesses[0]!)
      const cx = ox + nameW + i * colW + colW / 2
      const lit = trace ? rowLit && trace.statements.has(step) : false
      const dim = trace && !lit
      const box = `x="${cx - 8}" y="${y + 4}" width="16" height="16" rx="3"`
      const op = dim ? ' opacity="0.3"' : ''
      if (m.cls === 'write') {
        parts.push(`<rect ${box} fill="${lit ? t.amber : t.ink}"${op}/>`)
        parts.push(`<text x="${cx}" y="${y + 16}" font-size="10.5" font-family="${MONO}" text-anchor="middle" fill="${lit ? '#1B1F24' : t.surface}"${op}>${m.letter}</text>`)
      } else {
        if (m.cls !== 'read') parts.push(`<rect ${box} fill="none" stroke="${lit ? t.amber : t.inkMuted}"${m.cls === 'exec' ? ' stroke-dasharray="2 2"' : ''}${op}/>`)
        parts.push(`<text x="${cx}" y="${y + 16}" font-size="10.5" font-family="${MONO}" text-anchor="middle" fill="${lit ? t.amberInk : m.cls === 'read' ? t.inkMuted : t.ink}"${op}>${m.letter}</text>`)
      }
    })
  })
  parts.push('</svg>')
  return parts.join('\n')
}

let fontCss: Promise<string> | null = null

/** @font-face rules with the bundled fonts inlined, so a rasterized SVG renders in IBM Plex. */
function embeddedFonts(): Promise<string> {
  if (!fontCss) {
    const load = async (url: string): Promise<string> => {
      const buf = await (await fetch(url)).arrayBuffer()
      let binary = ''
      const bytes = new Uint8Array(buf)
      for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
      return `data:font/woff2;base64,${btoa(binary)}`
    }
    fontCss = Promise.all([load(sans400), load(sans600), load(mono400)])
      .then(
        ([a, b, c]) =>
          `@font-face{font-family:'IBM Plex Sans';font-weight:400;src:url(${a}) format('woff2')}` +
          `@font-face{font-family:'IBM Plex Sans';font-weight:600;src:url(${b}) format('woff2')}` +
          `@font-face{font-family:'IBM Plex Mono';font-weight:400;src:url(${c}) format('woff2')}`
      )
      .catch(() => '')
  }
  return fontCss
}

const MAX_CANVAS_SIDE = 16384

/** Rasterizes an SVG string to PNG (base64, no data: prefix). */
export async function svgToPngBase64(svg: string, scale: 1 | 2): Promise<string> {
  const css = await embeddedFonts()
  const withFonts = css ? svg.replace(/<defs>|(<rect )/, (m) => (m === '<defs>' ? `<defs><style>${css}</style>` : `<defs><style>${css}</style></defs>${m}`)) : svg
  const match = /width="(\d+)" height="(\d+)"/.exec(svg)
  const w = Number(match?.[1] ?? 800)
  const h = Number(match?.[2] ?? 600)
  const factor = Math.min(scale, MAX_CANVAS_SIDE / w, MAX_CANVAS_SIDE / h)
  const url = URL.createObjectURL(new Blob([withFonts], { type: 'image/svg+xml' }))
  try {
    const img = new Image()
    img.decoding = 'async'
    img.src = url
    await img.decode()
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.floor(w * factor))
    canvas.height = Math.max(1, Math.floor(h * factor))
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('Canvas is not available.')
    ctx.scale(factor, factor)
    ctx.drawImage(img, 0, 0, w, h)
    return canvas.toDataURL('image/png').slice('data:image/png;base64,'.length)
  } finally {
    URL.revokeObjectURL(url)
  }
}

export function lineageToSvg(model: LineageViewModel, layout: LayoutResult, _index: LineageIndex, t: ThemeTokens, title: string): string {
  const pad = 32
  const header = 40
  let maxX = 0
  let maxY = 0
  for (const c of model.cards) {
    const p = layout.positions.get(c.id)
    if (!p) continue
    maxX = Math.max(maxX, p.x + c.width)
    maxY = Math.max(maxY, p.y + c.height)
  }
  const width = Math.ceil(maxX + pad * 2)
  const height = Math.ceil(maxY + pad * 2 + header)
  const ox = pad
  const oy = pad + header
  const edgeRest = mix(t.inkMuted, t.canvas, 0.55)
  const surface2 = mix(t.surface, t.ink, 0.06)
  const faint = mix(t.inkMuted, t.surface, 0.45)
  const parts: string[] = [
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" font-family="${SANS}">`,
    `<rect width="100%" height="100%" fill="${t.canvas}"/>`,
    `<text x="${pad}" y="${pad + 8}" font-size="16" font-weight="600" fill="${t.ink}">${escapeXml(title)}</text>`
  ]
  const cardPos = new Map(model.cards.map((c) => [c.id, layout.positions.get(c.id)]))
  // Edges first, so cards sit on top.
  const labels: string[] = []
  for (const e of [...model.edges].sort((a, b) => Number(a.traced) - Number(b.traced))) {
    const a = model.rowIndex.get(e.edge.from)
    const b = model.rowIndex.get(e.edge.to)
    const pa = cardPos.get(e.sourceCard)
    const pb = cardPos.get(e.targetCard)
    if (!a || !b || !pa || !pb) continue
    const s = rowAnchor({ x: ox + pa.x, y: oy + pa.y }, a.row, 'right')
    const dash = e.edge.kind !== 'DIRECT' ? ' stroke-dasharray="5 4"' : ''
    if (e.sourceCard === e.targetCard) {
      // A column that filters or joins its own owner's rows: a small arc on the card's right side.
      const d2 = rowAnchor({ x: ox + pb.x, y: oy + pb.y }, b.row, 'right')
      parts.push(
        `<path d="M${s.x},${s.y} C${s.x + 26},${s.y} ${d2.x + 26},${d2.y} ${d2.x},${d2.y}" fill="none" stroke="${e.traced ? t.amber : edgeRest}" stroke-width="${e.traced ? 1.75 : 1}"${dash}${e.traced ? '' : ' opacity="0.4"'}/>`
      )
      continue
    }
    const d = rowAnchor({ x: ox + pb.x, y: oy + pb.y }, b.row, 'left')
    const dx = Math.max(30, Math.abs(d.x - s.x) / 2)
    parts.push(
      `<path d="M${s.x},${s.y} C${s.x + dx},${s.y} ${d.x - dx},${d.y} ${d.x},${d.y}" fill="none" stroke="${e.traced ? t.amber : edgeRest}" stroke-width="${e.traced ? 2 : 1.2}"${dash}${e.traced ? '' : ' opacity="0.4"'}/>`
    )
    if (e.traced && e.label && e.label !== 'copy') {
      const mx = (s.x + d.x) / 2
      const my = (s.y + d.y) / 2
      const w = e.label.length * 6 + 10
      labels.push(
        `<rect x="${mx - w / 2}" y="${my - 8}" width="${w}" height="16" rx="3" fill="${t.surface}" stroke="${t.line}"/>`,
        `<text x="${mx}" y="${my + 4}" font-size="10.5" text-anchor="middle" fill="${t.inkMuted}">${escapeXml(e.label)}</text>`
      )
    }
  }
  for (const c of model.cards) {
    const p = cardPos.get(c.id)
    if (!p) continue
    const x = ox + p.x
    const y = oy + p.y
    const transient = c.kind === 'CTE' || c.kind === 'SUBQUERY' || c.kind === 'RESULT'
    const unknown = c.kind === 'UNKNOWN'
    parts.push(
      `<rect x="${x}" y="${y}" width="${c.width}" height="${c.height}" rx="6" fill="${transient ? surface2 : t.surface}" stroke="${unknown ? t.inkMuted : t.line}"${unknown ? ' stroke-dasharray="4 3"' : ''}/>`,
      `<text x="${x + 10}" y="${y + 17}" font-size="12.5" ${c.kind === 'RESULT' || unknown ? 'font-weight="600"' : `font-family="${MONO}"`} fill="${t.ink}">${escapeXml(truncate(c.title, 30))}</text>`,
      `<text x="${x + 10}" y="${y + 32}" font-size="11" fill="${t.inkMuted}">${escapeXml(truncate(c.subtitle, 38))}</text>`,
      `<line x1="${x}" y1="${y + CARD.HEADER}" x2="${x + c.width}" y2="${y + CARD.HEADER}" stroke="${t.line}"/>`
    )
    c.rows.forEach((r, i) => {
      const ry = y + CARD.HEADER + i * CARD.ROW
      parts.push(
        `<text x="${x + 10}" y="${ry + 15}" font-size="12" ${r.rows ? 'font-style="italic"' : `font-family="${MONO}"`} fill="${r.traced ? t.amberInk : faint}">${escapeXml(truncate(r.label, 32))}</text>`
      )
    })
    if (c.hidden > 0) {
      parts.push(`<text x="${x + 10}" y="${y + CARD.HEADER + c.rows.length * CARD.ROW + 14}" font-size="11" fill="${faint}">+${c.hidden} more columns</text>`)
    }
  }
  parts.push(...labels, '</svg>')
  return parts.join('\n')
}

/** Nested query boxes (see `layoutStructure`). */
export function structureToSvg(layout: { boxes: StructureBox[]; width: number; height: number }, t: ThemeTokens, title: string): string {
  const pad = 24
  const header = 36
  const width = Math.ceil(layout.width + pad * 2)
  const height = Math.ceil(layout.height + pad * 2 + header)
  const palette = structurePalette(t)
  const ox = pad
  const oy = pad + header
  const parts: string[] = [
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" font-family="${SANS}">`,
    `<rect width="100%" height="100%" fill="${t.canvas}"/>`,
    `<text x="${pad}" y="${pad + 12}" font-size="16" font-weight="600" fill="${t.ink}">${escapeXml(title)}</text>`
  ]
  for (const b of layout.boxes) {
    const x = ox + b.x
    const y = oy + b.y
    const accent = b.node ? palette[kindColorKey(b.node.block.kind)] : t.amber
    const fill = b.node ? mix(t.surface, accent, b.node.depth % 2 === 0 ? 0.05 : 0.09) : t.surface
    const chars = Math.max(8, Math.floor((b.width - BOX.PAD * 2) / BOX.CHAR))
    parts.push(
      `<rect x="${x}" y="${y}" width="${b.width}" height="${b.height}" rx="5" fill="${fill}" stroke="${mix(t.line, accent, 0.45)}"/>`,
      b.node?.block.kind === 'SUBQUERY'
        ? `<line x1="${x + 1.5}" y1="${y + 2}" x2="${x + 1.5}" y2="${y + b.height - 2}" stroke="${accent}" stroke-width="3" stroke-dasharray="6 3"/>`
        : `<rect x="${x}" y="${y}" width="3" height="${b.height}" rx="1.5" fill="${accent}"/>`,
      `<text x="${x + BOX.PAD}" y="${y + 16}" font-size="12" font-weight="600" fill="${t.ink}">${escapeXml(truncate(b.title, chars))}</text>`
    )
    if (b.role) {
      const tx = x + BOX.PAD + Math.min(b.title.length, chars) * 7.2 + 8
      parts.push(`<text x="${tx}" y="${y + 16}" font-size="11" fill="${t.inkMuted}">${escapeXml(b.role)}</text>`)
    }
    b.lines.forEach((l, i) => {
      const ly = y + BOX.HEADER + i * BOX.LINE + 11
      parts.push(
        `<text x="${x + BOX.PAD}" y="${ly}" font-size="11" ${l.mono ? `font-family="${MONO}" ` : ''}fill="${l.mono ? t.ink : t.inkMuted}">${escapeXml(l.text)}</text>`
      )
    })
  }
  parts.push('</svg>')
  return parts.join('\n')
}
