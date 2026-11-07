/**
 * Decodes script bytes the same way the engine does: BOM first, then strict UTF-8, then GB18030.
 * The editor text must match the engine's text exactly, because ranges are UTF-16 offsets into it.
 */
export interface Decoded {
  text: string
  encoding: string
}

export function decodeScript(bytes: Uint8Array, encoding?: string | null): Decoded {
  if (encoding && encoding.toLowerCase() !== 'auto') {
    const label = encoding.toLowerCase()
    const text = new TextDecoder(label).decode(bytes)
    return { text: stripBom(text), encoding: encoding.toUpperCase() }
  }
  if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
    return { text: new TextDecoder('utf-8').decode(bytes.subarray(3)), encoding: 'UTF-8' }
  }
  if (bytes.length >= 2 && bytes[0] === 0xff && bytes[1] === 0xfe) {
    return { text: new TextDecoder('utf-16le').decode(bytes.subarray(2)), encoding: 'UTF-16LE' }
  }
  if (bytes.length >= 2 && bytes[0] === 0xfe && bytes[1] === 0xff) {
    return { text: new TextDecoder('utf-16be').decode(bytes.subarray(2)), encoding: 'UTF-16BE' }
  }
  try {
    return { text: new TextDecoder('utf-8', { fatal: true }).decode(bytes), encoding: 'UTF-8' }
  } catch {
    return { text: new TextDecoder('gb18030').decode(bytes), encoding: 'GB18030' }
  }
}

function stripBom(text: string): string {
  return text.charCodeAt(0) === 0xfeff ? text.slice(1) : text
}
