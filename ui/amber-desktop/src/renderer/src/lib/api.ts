import type { AmberApi, Result } from '@shared/api'
import { RPC_ERROR, type RpcErrorObject } from '@shared/protocol'

export const api = (): AmberApi => window.amber

export function isNotAvailable(error: RpcErrorObject | null | undefined): boolean {
  return error?.code === RPC_ERROR.METHOD_NOT_FOUND
}

export function describeError(error: RpcErrorObject): string {
  switch (error.code) {
    case RPC_ERROR.METHOD_NOT_FOUND:
      return 'Not available in this engine version.'
    case RPC_ERROR.REQUEST_CANCELLED:
      return 'Cancelled.'
    case RPC_ERROR.ENGINE_UNAVAILABLE:
      return error.message || 'The engine is not running.'
    default:
      return error.message || 'The engine reported an error.'
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
