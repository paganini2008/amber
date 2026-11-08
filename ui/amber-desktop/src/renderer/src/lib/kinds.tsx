import {
  ArrowDownToLine,
  Braces,
  CircleQuestionMark,
  CornerDownRight,
  Dot,
  Eraser,
  GitBranch,
  GitCommitHorizontal,
  KeyRound,
  LifeBuoy,
  ListTree,
  Merge,
  PenLine,
  Repeat,
  ScanSearch,
  Scissors,
  Settings2,
  Shuffle,
  SquareDashed,
  SquarePlus,
  Terminal,
  Trash,
  Undo2,
  Variable,
  Wrench,
  type LucideIcon
} from 'lucide-react'
import { tOr } from '../i18n'

const BY_KIND: Record<string, LucideIcon> = {
  SELECT: ScanSearch,
  INSERT: ArrowDownToLine,
  SELECT_INTO: ArrowDownToLine,
  UPDATE: PenLine,
  DELETE: Eraser,
  MERGE: Merge,
  CREATE_TABLE: SquarePlus,
  CREATE_TABLE_AS: SquarePlus,
  CREATE_VIEW: SquarePlus,
  CREATE_MATERIALIZED_VIEW: SquarePlus,
  CREATE_INDEX: ListTree,
  ALTER: Wrench,
  DROP: Trash,
  TRUNCATE: Scissors,
  CREATE_PROCEDURE: Braces,
  CREATE_FUNCTION: Braces,
  CREATE_PACKAGE: Braces,
  CREATE_PACKAGE_BODY: Braces,
  CREATE_TRIGGER: Braces,
  CREATE_TYPE_BODY: Braces,
  ROUTINE: Braces,
  BLOCK: SquareDashed,
  IF: GitBranch,
  CASE: GitBranch,
  LOOP: Repeat,
  TRY: LifeBuoy,
  EXCEPTION_HANDLER: LifeBuoy,
  CALL: CornerDownRight,
  DYNAMIC_SQL: Shuffle,
  DECLARE: Variable,
  ASSIGNMENT: Variable,
  CURSOR: Variable,
  RETURN: Undo2,
  GRANT: KeyRound,
  REVOKE: KeyRound,
  TRANSACTION: GitCommitHorizontal,
  SESSION: Settings2,
  CLIENT_COMMAND: Terminal,
  UNPARSED: CircleQuestionMark
}

export function kindIcon(kind: string): LucideIcon {
  if (BY_KIND[kind]) return BY_KIND[kind]
  if (kind.startsWith('CREATE_')) return SquarePlus
  return Dot
}

export function KindIcon({ kind, size = 14, className }: { kind: string; size?: number; className?: string }): React.JSX.Element {
  const Icon = kindIcon(kind)
  return <Icon size={size} strokeWidth={1.75} className={className} aria-hidden />
}

const humanize = (value: string): string => {
  const lower = value.replace(/_/g, ' ').toLowerCase()
  return lower.charAt(0).toUpperCase() + lower.slice(1)
}

/** Statement kind in the UI language, e.g. "Create procedure"; unknown kinds are humanized. */
export function kindText(kind: string): string {
  return tOr(`kinds.statement.${kind}`, humanize(kind))
}

/** Statement category (QUERY, DML, …). */
export function categoryText(category: string): string {
  return tOr(`kinds.category.${category}`, humanize(category))
}

/** Object kind, capitalized ("Table") or for running text ("table"). */
export function objectKindText(kind: string, lower = false): string {
  return lower ? tOr(`kinds.objectLower.${kind}`, kind.replace(/_/g, ' ').toLowerCase()) : tOr(`kinds.object.${kind}`, humanize(kind))
}

/** Access name ("Read", "Insert"), or as a heading verb ("Inserts into"). */
export function accessText(access: string, verb = false): string {
  return verb ? tOr(`kinds.accessVerb.${access}`, humanize(access)) : tOr(`kinds.access.${access}`, humanize(access))
}

/** Object role in the script, lower case ("source", "target"). */
export function roleText(role: string): string {
  return tOr(`kinds.role.${role}`, role.toLowerCase())
}

export function severityText(severity: string): string {
  return tOr(`kinds.severity.${severity}`, humanize(severity))
}

export function dialectName(dialect: string): string {
  return tOr(`kinds.dialect.${dialect}`, dialect)
}

/**
 * A finding's title and suggestion, and a rule's description. The engine writes them in the UI language
 * (`locale` goes with every call), so its text is used as is; the catalog in i18n/findings is the same
 * wording, kept for rule titles shown without an engine answer (e.g. the "why" of an optimized statement).
 */
export function findingTitle(f: { rule: string; title: string }): string {
  return f.title || tOr(`findings.rules.${f.rule}.title`, f.rule)
}

export function findingSuggestion(f: { rule: string; suggestion: string | null }): string | null {
  return f.suggestion
}

export function ruleDescription(rule: { id: string; description: string }): string {
  return rule.description || tOr(`findings.rules.${rule.id}.description`, '')
}

export function isMuted(confidence: string): boolean {
  return confidence === 'PARTIAL' || confidence === 'UNRESOLVED'
}

export function confidenceText(confidence: string): string {
  return tOr(`kinds.confidence.${confidence}`, confidence)
}
