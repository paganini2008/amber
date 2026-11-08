import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('source text', () => {
  it('has CJK characters only in the zh-CN locale files', () => {
    const root = join(import.meta.dirname, '..')
    const allowed = join('src', 'renderer', 'src', 'i18n', 'zh-CN') + sep
    // CJK symbols and punctuation, CJK ideographs, full-width forms.
    const cjk = /[\u3000-\u303f\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff00-\uffef]/
    const offenders: string[] = []
    const walk = (dir: string): void => {
      for (const name of readdirSync(dir)) {
        const path = join(dir, name)
        if (statSync(path).isDirectory()) {
          walk(path)
          continue
        }
        if (!/\.(ts|tsx|mjs|js|css|html|json|sql)$/.test(name)) continue
        const rel = relative(root, path)
        if (rel.startsWith(allowed)) continue
        if (cjk.test(readFileSync(path, 'utf8'))) offenders.push(rel)
      }
    }
    walk(join(root, 'src'))
    walk(join(root, 'scripts'))
    expect(offenders).toEqual([])
  })
})
