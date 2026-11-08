import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { DbIcon, DB_ICONS, dbIconUrl } from './DbIcon'

const DIALECTS = ['oracle', 'sqlserver', 'postgresql', 'mysql', 'mariadb', 'db2', 'h2', 'sqlite']

describe('DbIcon', () => {
  it('has a distinct bundled mark for each of the 8 databases', () => {
    expect(Object.keys(DB_ICONS).sort()).toEqual([...DIALECTS].sort())
    const urls = DIALECTS.map((d) => dbIconUrl(d))
    expect(new Set(urls).size).toBe(8)
    // Small SVGs are inlined as data URIs by the bundler; either way they are local, never fetched.
    for (const u of urls) expect(u).toMatch(/svg/)
  })

  it('renders every dialect, decorative next to a name and titled when alone', () => {
    for (const d of DIALECTS) {
      const html = renderToStaticMarkup(<DbIcon dialect={d} size={16} />)
      expect(html).toContain(`data-dialect="${d}"`)
      expect(html).toContain('aria-hidden="true"')
      expect(html).toContain('width="16"')
    }
    const alone = renderToStaticMarkup(<DbIcon dialect="sqlserver" size={20} labelled />)
    expect(alone).toContain('title="SQL Server"')
    expect(alone).toContain('alt="SQL Server"')
  })

  it('falls back to a generic database glyph', () => {
    const html = renderToStaticMarkup(<DbIcon dialect="informix" />)
    expect(html).toContain('data-dialect="generic"')
    expect(dbIconUrl(null)).toBe(dbIconUrl('nope'))
    expect(dbIconUrl('nope')).not.toBe(dbIconUrl('oracle'))
  })
})
