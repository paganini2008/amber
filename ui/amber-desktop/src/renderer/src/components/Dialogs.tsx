import { useEffect, useState } from 'react'
import type { AppInfo } from '@shared/api'
import logo from '@/assets/logo.png'
import { api } from '@/lib/api'
import { useStore } from '@/store'
import { exportHandover, generateHandover } from '@/actions'
import { Button, Dialog, Spinner, inputClass } from './ui'

const COMPONENTS: [string, string][] = [
  ['Electron, Chromium, Node.js', 'MIT and others'],
  ['React, Zustand, Radix UI, Lucide', 'MIT / ISC'],
  ['CodeMirror', 'MIT'],
  ['React Flow (@xyflow/react)', 'MIT'],
  ['ELK (elkjs)', 'EPL-2.0'],
  ['IBM Plex Sans and Mono', 'SIL OFL 1.1'],
  ['Amber engine, ANTLR runtime, Jackson', 'Apache-2.0 / BSD-3'],
  ['Java runtime (jlink)', 'GPLv2 with Classpath Exception'],
  ['JDBC drivers', 'see THIRD-PARTY-NOTICES.md']
]

export function AboutDialog(): React.JSX.Element {
  const open = useStore((s) => s.dialogs.about)
  const closeDialog = useStore((s) => s.closeDialog)
  const engine = useStore((s) => s.engine)
  const [info, setInfo] = useState<AppInfo | null>(null)
  useEffect(() => {
    if (open && !info) void api().app.info().then(setInfo)
  }, [open, info])

  return (
    <Dialog open={open} onOpenChange={(o) => !o && closeDialog('about')} title="About Amber" width={520}>
      <div className="px-6 py-5">
        <div className="flex items-center gap-4">
          <img src={logo} alt="Amber logo" className="h-20 w-20" />
          <div>
            <div className="text-2xl font-semibold tracking-[-0.01em]">Amber</div>
            <div className="text-sm text-ink-muted">Read and understand large SQL scripts.</div>
            <div className="mt-1 text-sm">Version {info?.version ?? '…'}</div>
          </div>
        </div>
        <dl className="mt-5 grid grid-cols-[120px_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-ink-muted">Engine</dt>
          <dd>{engine.info ? `${engine.info.version} on Java ${engine.info.java}` : engine.state === 'failed' ? 'Not running' : 'Starting…'}</dd>
          <dt className="text-ink-muted">Electron</dt>
          <dd>
            {info?.electron} (Chromium {info?.chrome}, Node {info?.node})
          </dd>
          <dt className="text-ink-muted">Platform</dt>
          <dd>
            {info?.platform} {info?.arch}
          </dd>
          <dt className="text-ink-muted">License</dt>
          <dd>Apache License 2.0</dd>
        </dl>
        <h3 className="mt-5 mb-1.5 text-sm font-semibold">Bundled components</h3>
        <ul className="divide-y divide-line rounded-[6px] border border-line text-sm">
          {COMPONENTS.map(([name, license]) => (
            <li key={name} className="flex justify-between gap-3 px-3 py-1">
              <span>{name}</span>
              <span className="text-ink-muted">{license}</span>
            </li>
          ))}
        </ul>
        <div className="mt-4 flex justify-between">
          <Button variant="ghost" onClick={() => void api().app.openExternal('https://github.com/paganini2008/amber')}>
            github.com/paganini2008/amber
          </Button>
          <Button onClick={() => closeDialog('about')}>Close</Button>
        </div>
      </div>
    </Dialog>
  )
}

export function PasteDialog(): React.JSX.Element {
  const open = useStore((s) => s.dialogs.paste)
  const closeDialog = useStore((s) => s.closeDialog)
  const loadDocument = useStore((s) => s.loadDocument)
  const [text, setText] = useState('')
  const [name, setName] = useState('pasted.sql')

  const analyze = (): void => {
    if (!text.trim()) return
    loadDocument({ path: null, name: name.trim() || 'pasted.sql', text, encoding: 'UTF-8' })
    closeDialog('paste')
    setText('')
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => !o && closeDialog('paste')}
      title="Paste SQL"
      description="Paste a script to analyze it. Nothing is executed."
      width={720}
      footer={
        <>
          <Button variant="ghost" onClick={() => closeDialog('paste')}>
            Cancel
          </Button>
          <Button variant="primary" onClick={analyze} disabled={!text.trim()}>
            Analyze
          </Button>
        </>
      }
    >
      <div className="space-y-3 px-5 py-4">
        <label className="flex items-center gap-3 text-sm">
          <span className="w-12 text-ink-muted">Name</span>
          <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <textarea
          aria-label="SQL text"
          className="code selectable h-[46vh] w-full resize-none rounded-[5px] border border-line bg-canvas p-3 text-[13px] text-ink outline-none focus:border-amber"
          spellCheck={false}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) analyze()
          }}
          placeholder="CREATE TABLE …"
          autoFocus
        />
      </div>
    </Dialog>
  )
}

/**
 * Preview of the handover document. The engine's HTML is one self-contained page without
 * scripts; it is shown in a sandboxed iframe (no scripts, no same-origin access).
 */
export function HandoverDialog(): React.JSX.Element {
  const open = useStore((s) => s.dialogs.handover)
  const close = useStore((s) => s.closeDialog)
  const analysis = useStore((s) => s.analysis)
  const [state, setState] = useState<{ status: 'loading' } | { status: 'ready'; html: string } | { status: 'error'; message: string }>({ status: 'loading' })

  useEffect(() => {
    if (!open) return
    let cancelled = false
    setState({ status: 'loading' })
    void generateHandover('html').then((r) => {
      if (!cancelled) setState(r.ok ? { status: 'ready', html: r.text } : { status: 'error', message: r.message })
    })
    return () => {
      cancelled = true
    }
  }, [open, analysis])

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => !o && close('handover')}
      title="Handover document"
      description="What the script does, its objects, data flow, risks and the parts Amber could not read: notes for whoever maintains it next."
      width={980}
      footer={
        <>
          <Button variant="ghost" onClick={() => close('handover')}>
            Close
          </Button>
          <Button onClick={() => void exportHandover('markdown')}>Save as Markdown…</Button>
          <Button variant="primary" onClick={() => void exportHandover('html')}>
            Save as HTML…
          </Button>
        </>
      }
    >
      <div className="h-[68vh] bg-canvas p-3">
        {state.status === 'loading' ? (
          <div className="flex h-full items-center justify-center gap-2 text-sm text-ink-muted">
            <Spinner /> Writing the handover document
          </div>
        ) : state.status === 'error' ? (
          <p className="p-4 text-sm text-risk-high">{state.message}</p>
        ) : (
          <iframe
            title="Handover document preview"
            sandbox=""
            srcDoc={state.html}
            className="h-full w-full rounded-[6px] border border-line bg-white"
          />
        )}
      </div>
    </Dialog>
  )
}
