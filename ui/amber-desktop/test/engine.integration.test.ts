/**
 * Spawns the real engine jar and checks the protocol end to end.
 * Uses AMBER_ENGINE_JAR, or the backend build output (`mvn -pl amber-core package`).
 */
import { existsSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { EngineSupervisor, resolveEngineCommand } from '../src/main/engine/supervisor'
import { RpcError } from '../src/main/engine/rpc-client'
import { RPC_ERROR, type AnalysisResult, type EngineInfo, type LineageNode, type Statement } from '../src/shared/protocol'

const command = resolveEngineCommand({
  packaged: false,
  resourcesPath: '',
  appRoot: resolve(import.meta.dirname, '..'),
  platform: process.platform,
  env: process.env
})
const hasEngine = existsSync(command.jar)

const SCRIPT = `-- Sample ETL written for the Amber test suite
CREATE TEMP TABLE tmp_visits AS
SELECT v.visitor_id, v.page, v.seen_at
FROM web.page_views v
WHERE v.seen_at >= current_date;

INSERT INTO stats.page_hits (page, hits)
WITH ranked AS (
  SELECT page, COUNT(*) AS hits FROM tmp_visits GROUP BY page
)
SELECT page, hits FROM ranked;

UPDATE stats.page_hits SET hits = hits + 1 WHERE page = 'home';
DROP TABLE tmp_visits;
`

function flatten(statements: Statement[]): Statement[] {
  return statements.flatMap((s) => [s, ...flatten(s.children)])
}

describe.skipIf(!hasEngine)('engine integration', () => {
  let engine: EngineSupervisor

  beforeAll(async () => {
    engine = new EngineSupervisor({ command })
    await engine.start()
  })

  afterAll(async () => {
    await engine?.stop()
  })

  it('answers engine.info with the eight dialects', async () => {
    const info = await engine.request<EngineInfo>('engine.info')
    expect(info.name).toBe('Amber')
    expect(typeof info.version).toBe('string')
    expect(info.dialects.map((d) => d.id).sort()).toEqual(
      ['db2', 'h2', 'mariadb', 'mysql', 'oracle', 'postgresql', 'sqlite', 'sqlserver']
    )
    expect(engine.getStatus().state).toBe('ready')
  })

  it('analyzes text and returns statements, refs and lifecycles', async () => {
    const result = await engine.request<AnalysisResult>('analyze', {
      dialect: 'postgresql',
      name: 'visits.sql',
      text: SCRIPT
    })
    expect(result.status).toBe('SUCCESS')
    expect(result.name).toBe('visits.sql')
    expect(result.diagnostics).toEqual([])
    expect(result.stepCount).toBe(4)
    const all = flatten(result.statements)
    expect(all.map((s) => s.kind)).toEqual(['CREATE_TABLE_AS', 'INSERT', 'UPDATE', 'DROP'])
    for (const s of all) {
      expect(typeof s.summary).toBe('string')
      expect(s.range.start.line).toBeGreaterThan(0)
      expect(SCRIPT.slice(s.range.start.offset, s.range.end.offset).length).toBeGreaterThan(0)
    }
    const insert = all[1]!
    expect(insert.ctes).toContain('ranked')
    expect(insert.refs.some((r) => r.access === 'INSERT' && r.name === 'stats.page_hits')).toBe(true)
    const ref = insert.refs.find((r) => r.name === 'stats.page_hits')!
    expect(SCRIPT.slice(ref.range.start.offset, ref.range.end.offset)).toBe('stats.page_hits')

    const temp = result.lifecycles.find((l) => l.name === 'tmp_visits')
    expect(temp).toMatchObject({ kind: 'TEMP_TABLE', role: 'INTERMEDIATE', createdInScript: true, droppedInScript: true })
    expect(temp!.events.map((e) => e.access)).toEqual(['CREATE', 'READ', 'DROP'])
    expect(result.lifecycles.find((l) => l.name === 'web.page_views')?.role).toBe('SOURCE')
  })

  it('returns column lineage through a temp table and a CTE', async () => {
    const script = `-- Written for the Amber test suite
CREATE TEMP TABLE tmp_paid AS
SELECT o.order_id, o.customer_id, o.amount
FROM shop.orders o WHERE o.status = 'paid';

INSERT INTO report.customer_totals (customer_id, region, spend)
WITH per_customer AS (
  SELECT p.customer_id, SUM(p.amount) AS spend FROM tmp_paid p GROUP BY p.customer_id
)
SELECT pc.customer_id, c.region, pc.spend
FROM per_customer pc JOIN shop.customers c ON c.id = pc.customer_id;
`
    const result = await engine.request<AnalysisResult>('analyze', { dialect: 'postgresql', text: script })
    expect(result.status).toBe('SUCCESS')
    const lineage = result.lineage!
    expect(Array.isArray(lineage.nodes)).toBe(true)
    expect(Array.isArray(lineage.edges)).toBe(true)
    for (const n of lineage.nodes) {
      expect(typeof n.id).toBe('number')
      expect(typeof n.ownerKey).toBe('string')
      expect(typeof n.column).toBe('string')
    }
    const byId = new Map<number, LineageNode>(lineage.nodes.map((n) => [n.id, n]))
    const find = (ownerKey: string, column: string): LineageNode | undefined =>
      lineage.nodes.find((n) => n.ownerKey === ownerKey && n.column === column)
    for (const e of lineage.edges) {
      expect(byId.has(e.from) && byId.has(e.to)).toBe(true)
      expect(['DIRECT', 'INDIRECT']).toContain(e.kind)
      expect(typeof e.statement).toBe('number')
      expect(e.range.start.offset).toBeLessThanOrEqual(e.range.end.offset)
    }
    expect(lineage.nodes.some((n) => n.ownerKind === 'CTE')).toBe(true)
    expect(lineage.nodes.some((n) => n.ownerKind === 'TEMP_TABLE')).toBe(true)

    // Walk upstream from the target column, following direct edges only.
    const target = find('REPORT.CUSTOMER_TOTALS', 'customer_id')!
    const seen = new Set([target.id])
    const queue = [target.id]
    while (queue.length) {
      const id = queue.shift()!
      for (const e of lineage.edges) {
        if (e.to === id && e.kind === 'DIRECT' && !seen.has(e.from)) {
          seen.add(e.from)
          queue.push(e.from)
        }
      }
    }
    const path = [...seen].map((id) => byId.get(id)!)
    expect(path.some((n) => n.ownerKey === 'SHOP.ORDERS' && n.column === 'customer_id')).toBe(true)
    expect(path.some((n) => n.ownerKey === 'TMP_PAID')).toBe(true)
    expect(path.some((n) => n.ownerKind === 'CTE')).toBe(true)

    // Filters, joins and groups arrive as INDIRECT edges on the *rows* pseudo column.
    const indirect = lineage.edges.filter((e) => e.kind === 'INDIRECT')
    expect(indirect.every((e) => byId.get(e.to)!.column === '*rows*')).toBe(true)
    expect(new Set(indirect.map((e) => e.role))).toEqual(new Set(['FILTER', 'GROUP', 'JOIN']))
    // SUM(...) inside the CTE is an AGGREGATE edge.
    expect(lineage.edges.some((e) => e.transform === 'AGGREGATE' && byId.get(e.to)!.ownerKind === 'CTE')).toBe(true)
  })

  it('returns findings, and honours amber:ignore', async () => {
    const script = `-- Written for the Amber test suite
SELECT o.id, c.name FROM shop.orders o, crm.customers c;
SELECT p.sku FROM shop.products p WHERE p.name LIKE '%lamp';
-- amber:ignore AMB-S001
SELECT o.id, c.name FROM shop.orders o, crm.customers c;
`
    const result = await engine.request<AnalysisResult>('analyze', { dialect: 'postgresql', text: script })
    expect(result.status).toBe('SUCCESS')
    const findings = result.findings!
    const cartesian = findings.filter((f) => f.rule === 'AMB-S001')
    expect(cartesian).toHaveLength(1)
    expect(cartesian[0]).toMatchObject({ severity: 'HIGH', statement: 0 })
    expect(typeof cartesian[0]!.title).toBe('string')
    expect(typeof cartesian[0]!.message).toBe('string')
    expect(cartesian[0]!.range.start.line).toBe(2)
    expect(findings.some((f) => f.rule === 'AMB-S003' && f.statement === 1)).toBe(true)
  })

  it('reports warnings without failing the analysis', async () => {
    const result = await engine.request<AnalysisResult>('analyze', { dialect: 'mysql', text: 'SELECT a\u200B FROM t1;' })
    expect(result.status).not.toBe('FAILED')
    for (const d of result.diagnostics) expect(['ERROR', 'WARNING', 'INFO']).toContain(d.severity)
  })

  it('reads a GB18030 file by path', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'amber-it-'))
    const file = join(dir, 'gb.sql')
    // "-- café" in GB18030 (é is 0xA8 0xA6, not valid UTF-8), then a statement
    writeFileSync(file, Buffer.concat([Buffer.from([0x2d, 0x2d, 0x20, 0x63, 0x61, 0x66, 0xa8, 0xa6, 0x0a]), Buffer.from('SELECT a FROM t1;\n')]))
    const result = await engine.request<AnalysisResult>('analyze', { dialect: 'mysql', path: file })
    expect(result.status).toBe('SUCCESS')
    expect(result.name).toBe('gb.sql')
    expect(result.statements[0]!.range.start.offset).toBe(8)
  })

  it('fails in strict mode with diagnostics, and accepts the fallback switch', async () => {
    const bad = 'SELECT a FROM t1;\nSELEC b FROM t2;\nSELECT c FROM t3;\n'
    const strict = await engine.request<AnalysisResult>('analyze', { dialect: 'h2', text: bad })
    expect(strict.status).toBe('FAILED')
    expect(strict.statements).toEqual([])
    expect(strict.diagnostics[0]).toMatchObject({ severity: 'ERROR', code: 'syntax' })
    expect(strict.diagnostics[0]!.range.start.line).toBe(2)

    const fallback = await engine.request<AnalysisResult>('analyze', { dialect: 'h2', text: bad, allowFallback: true })
    expect(['PARTIAL', 'SUCCESS']).toContain(fallback.status)
    expect(fallback.statements.length).toBeGreaterThan(0)
  })

  it('reports unknown methods with -32601', async () => {
    const error = await engine.request('lineage', {}).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(RpcError)
    expect((error as RpcError).code).toBe(RPC_ERROR.METHOD_NOT_FOUND)
    expect(engine.isKnownUnsupported('lineage')).toBe(true)
  })

  it('handles concurrent requests', async () => {
    const results = await Promise.all(
      ['oracle', 'sqlserver', 'postgresql', 'mysql', 'mariadb', 'db2', 'h2', 'sqlite'].map((dialect) =>
        engine.request<AnalysisResult>('analyze', { dialect, text: 'SELECT a FROM t1;' })
      )
    )
    expect(results.map((r) => r.dialect)).toEqual(['oracle', 'sqlserver', 'postgresql', 'mysql', 'mariadb', 'db2', 'h2', 'sqlite'])
  })

  it('rejects an unknown dialect with -32602', async () => {
    await expect(engine.request('analyze', { dialect: 'nope', text: 'SELECT 1' })).rejects.toMatchObject({
      code: RPC_ERROR.INVALID_PARAMS
    })
  })
})
