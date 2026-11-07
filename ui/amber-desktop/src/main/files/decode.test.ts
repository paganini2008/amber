import { describe, expect, it } from 'vitest'
import { decodeScript } from './decode'

describe('decodeScript', () => {
  it('strips a UTF-8 BOM', () => {
    const bytes = new Uint8Array([0xef, 0xbb, 0xbf, ...new TextEncoder().encode('SELECT 1;')])
    expect(decodeScript(bytes)).toEqual({ text: 'SELECT 1;', encoding: 'UTF-8' })
  })

  it('reads plain UTF-8', () => {
    const bytes = new TextEncoder().encode('-- café menu\nSELECT 1;')
    expect(decodeScript(bytes)).toEqual({ text: '-- café menu\nSELECT 1;', encoding: 'UTF-8' })
  })

  it('falls back to GB18030 for invalid UTF-8', () => {
    // "-- café" in GB18030 (é is 0xA8 0xA6, which is not valid UTF-8)
    const bytes = new Uint8Array([0x2d, 0x2d, 0x20, 0x63, 0x61, 0x66, 0xa8, 0xa6])
    expect(decodeScript(bytes)).toEqual({ text: '-- café', encoding: 'GB18030' })
  })

  it('honours an explicit encoding', () => {
    const bytes = new Uint8Array([0xa8, 0xa6])
    expect(decodeScript(bytes, 'gbk').text).toBe('é')
  })
})
