import { t } from '@/i18n'

/** CodeMirror's built-in phrases (search panel, go to line, folding) in the UI language. */
export function editorPhrases(): Record<string, string> {
  return {
    Find: t('editor.phrases.find'),
    Replace: t('editor.phrases.replace'),
    next: t('editor.phrases.next'),
    previous: t('editor.phrases.previous'),
    all: t('editor.phrases.all'),
    'match case': t('editor.phrases.matchCase'),
    regexp: t('editor.phrases.regexp'),
    'by word': t('editor.phrases.byWord'),
    replace: t('editor.phrases.replaceOne'),
    'replace all': t('editor.phrases.replaceAll'),
    close: t('editor.phrases.close'),
    'current match': t('editor.phrases.currentMatch'),
    'on line': t('editor.phrases.onLine'),
    'replaced $ matches': t('editor.phrases.replacedMatches'),
    'replaced match on line $': t('editor.phrases.replacedMatchOnLine'),
    'Go to line': t('editor.phrases.goToLine'),
    go: t('editor.phrases.go'),
    'Folded lines': t('editor.phrases.foldedLines'),
    'Unfolded lines': t('editor.phrases.unfoldedLines'),
    to: t('editor.phrases.to'),
    'folded code': t('editor.phrases.foldedCode'),
    unfold: t('editor.phrases.unfold'),
    'Fold line': t('editor.phrases.foldLine'),
    'Unfold line': t('editor.phrases.unfoldLine'),
    'Control character': t('editor.phrases.controlCharacter'),
    'Selection deleted': t('editor.phrases.selectionDeleted')
  }
}
