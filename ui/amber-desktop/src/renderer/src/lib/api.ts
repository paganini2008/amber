import type { AmberApi, Result, WireResult } from '@shared/api'
import { RPC_ERROR, type RpcErrorObject } from '@shared/protocol'
import { getLanguage, t } from '../i18n'

/** An engine answer as it crossed IPC (JSON text, see WireResult) turned back into a Result. */
export function fromWire<T>(r: WireResult | Result<T>): Result<T> {
  if (r.ok && 'json' in r && typeof r.json === 'string') return { ok: true, value: JSON.parse(r.json) as T }
  return r as Result<T>
}

/**
 * Methods whose results carry sentences for people: they get the UI language as `locale` (docs/protocol.md,
 * "Language of the results"). `ai.buildContext` is left out on purpose: the prompt for the model stays English.
 */
export const LOCALIZED_METHODS = new Set<string>([
  'analyze',
  'doc.generate',
  'diff',
  'rewrite.suggest',
  'rewrite.apply',
  'rewrite.optimize',
  'ai.check',
  'references',
  'er.model',
  'engine.rules'
])

/** The params with `locale` added for localized methods (a params object is required for them). */
export function withLocale(method: string, params: unknown): unknown {
  if (!LOCALIZED_METHODS.has(method)) return params
  const base = params && typeof params === 'object' ? (params as Record<string, unknown>) : {}
  return { ...base, locale: getLanguage() }
}

let bridge: AmberApi | null = null
let wrapped: AmberApi | null = null

/** The preload API; engine answers arrive as JSON text and are parsed here, in the page. */
export const api = (): AmberApi => {
  const raw = window.amber
  if (raw !== bridge || !wrapped) {
    bridge = raw
    wrapped = {
      ...raw,
      engine: {
        ...raw.engine,
        analyze: async (params, requestId) =>
          fromWire(await (raw.engine.analyze(withLocale('analyze', params) as typeof params, requestId) as Promise<WireResult | Result<never>>)),
        call: async (method, params, requestId) => fromWire(await (raw.engine.call(method, withLocale(method, params), requestId) as Promise<WireResult | Result<never>>))
      }
    }
  }
  return wrapped
}

export function isNotAvailable(error: RpcErrorObject | null | undefined): boolean {
  return error?.code === RPC_ERROR.METHOD_NOT_FOUND
}

export function describeError(error: RpcErrorObject): string {
  switch (error.code) {
    case RPC_ERROR.METHOD_NOT_FOUND:
      return t('app.errors.notAvailable')
    case RPC_ERROR.REQUEST_CANCELLED:
      return t('app.errors.cancelled')
    case RPC_ERROR.ENGINE_UNAVAILABLE:
      return error.message || t('app.errors.engineNotRunning')
    default:
      return error.message || t('app.errors.engineError')
  }
}

export function unwrap<T>(result: Result<T>): T {
  if (result.ok) return result.value
  throw Object.assign(new Error(result.error.message), { code: result.error.code })
}

let counter = 0
export function newRequestId(prefix = 'r'): string {
  counter += 1
  return `${prefix}-${Date.now().toString(36)}-${counter}`
}
