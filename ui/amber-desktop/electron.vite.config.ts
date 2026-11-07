import { resolve } from 'node:path'
import { defineConfig } from 'electron-vite'
import type { Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const root = import.meta.dirname
const shared = resolve(root, 'src/shared')

/**
 * Content Security Policy for the renderer. Production is strict: no remote origins, no inline
 * or eval scripts. Styles allow inline attributes (React Flow and CodeMirror position elements
 * with style attributes). Development adds what Vite's dev server and React refresh need.
 */
function csp(dev: boolean): string {
  const directives: Record<string, string> = {
    'default-src': "'self'",
    'script-src': dev ? "'self' 'unsafe-inline'" : "'self'",
    'style-src': "'self' 'unsafe-inline'",
    'img-src': "'self' data: blob:",
    'font-src': "'self' data:",
    'worker-src': "'self' blob:",
    'connect-src': dev ? "'self' ws://localhost:* http://localhost:*" : "'self'",
    'object-src': "'none'",
    'base-uri': "'none'",
    'form-action': "'none'",
    'frame-src': "'none'"
  }
  return Object.entries(directives)
    .map(([k, v]) => `${k} ${v}`)
    .join('; ')
}

function cspPlugin(): Plugin {
  let dev = false
  return {
    name: 'amber-csp',
    configResolved(config) {
      dev = config.command === 'serve'
    },
    transformIndexHtml(html) {
      return html.replace('%AMBER_CSP%', csp(dev))
    }
  }
}

export default defineConfig({
  main: {
    resolve: { alias: { '@shared': shared } },
    build: {
      rollupOptions: { input: { index: resolve(root, 'src/main/index.ts') } }
    }
  },
  preload: {
    resolve: { alias: { '@shared': shared } },
    build: {
      // Sandboxed preload scripts must be CommonJS.
      rollupOptions: {
        input: { index: resolve(root, 'src/preload/index.ts') },
        output: { format: 'cjs', entryFileNames: '[name].cjs' }
      }
    }
  },
  renderer: {
    root: resolve(root, 'src/renderer'),
    base: './',
    resolve: {
      alias: {
        '@shared': shared,
        '@': resolve(root, 'src/renderer/src')
      }
    },
    plugins: [cspPlugin(), react(), tailwindcss()],
    worker: { format: 'es' },
    build: {
      chunkSizeWarningLimit: 4000,
      rollupOptions: { input: { index: resolve(root, 'src/renderer/index.html') } }
    }
  }
})
