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

export function kindText(kind: string): string {
  const t = kind.replace(/_/g, ' ').toLowerCase()
  return t.charAt(0).toUpperCase() + t.slice(1)
}

export function isMuted(confidence: string): boolean {
  return confidence === 'PARTIAL' || confidence === 'UNRESOLVED'
}

export function confidenceText(confidence: string): string {
  switch (confidence) {
    case 'CERTAIN':
      return 'Certain'
    case 'INFERRED':
      return 'Inferred'
    case 'PARTIAL':
      return 'Partially parsed'
    case 'UNRESOLVED':
      return 'Unresolved'
    default:
      return confidence
  }
}
