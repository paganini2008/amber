import { Check, Copy } from 'lucide-react'
import { useEffect, useState } from 'react'
import { t } from '@/i18n'
import { useStore } from '@/store'

/** SQL in a code frame with a copy button (AI suggestions, optimized SQL, suggested DDL). */
export function CodeBlock({ code, copyLabel }: { code: string; copyLabel?: string }): React.JSX.Element {
  const [copied, setCopied] = useState(false)
  useEffect(() => {
    if (!copied) return
    const timer = setTimeout(() => setCopied(false), 1500)
    return () => clearTimeout(timer)
  }, [copied])
  return (
    <div className="relative">
      <pre className="code selectable max-h-60 overflow-auto rounded-[5px] border border-line bg-canvas p-2 pr-8 text-[12.5px] whitespace-pre-wrap text-ink">{code}</pre>
      <button
        type="button"
        aria-label={copied ? t('common.copied') : (copyLabel ?? t('ai.suggestion.copySql'))}
        title={copied ? t('common.copied') : (copyLabel ?? t('ai.suggestion.copySql'))}
        className="absolute top-1.5 right-1.5 flex h-6 w-6 items-center justify-center rounded-[4px] text-ink-muted hover:bg-surface-hover hover:text-ink"
        onClick={() => {
          void navigator.clipboard.writeText(code).then(
            () => setCopied(true),
            () => useStore.getState().toast(t('ai.suggestion.copyFailed'), 'error')
          )
        }}
      >
        {copied ? <Check size={13} /> : <Copy size={13} />}
      </button>
    </div>
  )
}
