import type { AmberApi } from '../shared/api'

declare global {
  interface Window {
    amber: AmberApi
  }
}

export {}
