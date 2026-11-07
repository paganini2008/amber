/**
 * Types for the engine JSON-RPC protocol (docs/protocol.md).
 * Unknown fields may appear in newer engines; treat everything not listed here as optional.
 */

export const RPC_ERROR = {
  PARSE_ERROR: -32700,
  INVALID_REQUEST: -32600,
  METHOD_NOT_FOUND: -32601,
  INVALID_PARAMS: -32602,
  INTERNAL_ERROR: -32603,
  REQUEST_CANCELLED: -32800,
  /** Client-side: the engine process exited or is not running. */
  ENGINE_UNAVAILABLE: -33001,
  /** Client-side: the request timed out. */
  TIMEOUT: -33002
} as const

export interface Position {
  offset: number
  line: number
  column: number
}

export interface Range {
  start: Position
  end: Position
}

export type DialectId = 'oracle' | 'sqlserver' | 'postgresql' | 'mysql' | 'mariadb' | 'db2' | 'h2' | 'sqlite'

export interface Dialect {
  id: DialectId | string
  name: string
}

export interface EngineInfo {
  name: string
  version: string
  java: string
  dialects: Dialect[]
}

export type AnalysisStatus = 'SUCCESS' | 'FAILED' | 'PARTIAL'
export type Confidence = 'CERTAIN' | 'INFERRED' | 'PARTIAL' | 'UNRESOLVED'
export type Category =
  | 'QUERY'
  | 'DML'
  | 'DDL'
  | 'PROGRAM'
  | 'CONTROL'
  | 'PROCEDURAL'
  | 'DCL'
  | 'TCL'
  | 'CLIENT'
  | 'OTHER'

export type Access =
  | 'CREATE'
  | 'READ'
  | 'INSERT'
  | 'UPDATE'
  | 'DELETE'
  | 'MERGE'
  | 'TRUNCATE'
  | 'ALTER'
  | 'DROP'
  | 'EXECUTE'

export type ObjectKind =
  | 'TABLE'
  | 'TEMP_TABLE'
  | 'TABLE_VARIABLE'
  | 'VIEW'
  | 'MATERIALIZED_VIEW'
  | 'CTE'
  | 'INDEX'
  | 'SEQUENCE'
  | 'PROCEDURE'
  | 'FUNCTION'
  | 'PACKAGE'
  | 'TRIGGER'
  | 'TYPE'
  | 'SYNONYM'
  | 'SCHEMA'
  | 'ROUTINE'
  | 'OTHER'

export type Role = 'SOURCE' | 'INTERMEDIATE' | 'TARGET' | 'STRUCTURE'

export interface Ref {
  name: string
  key: string
  kind: ObjectKind | string
  access: Access | string
  confidence: Confidence | string
  range: Range
}

export interface Statement {
  id: number
  kind: string
  category: Category | string
  container: boolean
  confidence: Confidence | string
  name: string | null
  summary: string
  range: Range
  ctes: string[]
  refs: Ref[]
  children: Statement[]
  /**
   * Nested query structure (CTEs, set operations, derived tables, subqueries). Empty for
   * statements without a query; missing from engines older than structure support.
   */
  structure?: QueryBlock[]
  /**
   * Logical processing order of the query (the order the SQL standard defines for reading it;
   * not the database's execution plan). Empty without a query; missing from older engines.
   */
  executionOrder?: LogicalStep[]
  /**
   * Not yet sent by the engine. Future: for children of IF/CASE containers, which branch they
   * belong to (e.g. "THEN", "ELSE", "WHEN 1"). The UI groups by it when present.
   */
  branch?: string
}

export type QueryBlockKind = 'QUERY' | 'SET' | 'CTE' | 'DERIVED' | 'SUBQUERY'

export interface QueryBlockSource {
  name: string
  /** As Lineage ownerKind: TABLE, CTE, SUBQUERY, … */
  kind: LineageOwnerKind | string
  alias: string | null
}

/** One clause of a block, in processing order; `rank` is its place in the standard order. */
export interface QueryPhase {
  name: string
  rank: number
  range: Range
}

/** One step of a statement's logical processing order. */
export interface LogicalStep {
  /** 1-based position in the order. */
  step: number
  /** `QueryBlock.id` the step belongs to; -1 for the final WRITE of DML. */
  block: number
  /** Nesting level, for indentation. */
  depth: number
  /** FROM, WHERE, …, a set operator ("UNION ALL"), CTE, or WRITE. */
  phase: string
  detail: string | null
  range: Range
}

/** One box of the nested structure view. Children nest in source order. */
export interface QueryBlock {
  /** Pre-order number within the statement; `LogicalStep.block` refers to it. Missing from older engines. */
  id?: number
  kind: QueryBlockKind | string
  /** CTE name, derived-table alias, or set operators ("UNION ALL"). */
  name: string | null
  /** DERIVED: "FROM"; SUBQUERY: clause + predicate, e.g. "WHERE IN", "SELECT scalar". */
  role: string | null
  range: Range
  sources: QueryBlockSource[]
  /** Output column names (expression text when unnamed). */
  outputs: string[]
  /** DISTINCT, JOIN, WHERE, GROUP BY, HAVING, ORDER BY, … */
  clauses: string[]
  /** The block's clauses in processing order. Missing from older engines. */
  phases?: QueryPhase[]
  children: QueryBlock[]
}

export interface LifecycleEvent {
  step: number
  access: Access | string
  range: Range
}

export interface Lifecycle {
  name: string
  key: string
  kind: ObjectKind | string
  role: Role | string
  createdInScript: boolean
  droppedInScript: boolean
  events: LifecycleEvent[]
}

export interface Diagnostic {
  severity: 'ERROR' | 'WARNING' | 'INFO' | string
  code: string
  message: string
  range: Range
  offending?: string
  expected?: string[]
}

export type LineageOwnerKind =
  | 'TABLE'
  | 'TEMP_TABLE'
  | 'TABLE_VARIABLE'
  | 'VIEW'
  | 'MATERIALIZED_VIEW'
  | 'CTE'
  | 'SUBQUERY'
  | 'RESULT'
  | 'UNKNOWN'

export interface LineageNode {
  id: number
  ownerKind: LineageOwnerKind | string
  owner: string
  ownerKey: string
  /** `*rows*` is a pseudo column: INDIRECT edges point at it. */
  column: string
}

export interface LineageEdge {
  from: number
  to: number
  kind: 'DIRECT' | 'INDIRECT' | string
  /** Direct edges: PASS_THROUGH EXPRESSION AGGREGATE WINDOW CASE CAST CONSTANT. */
  transform: string | null
  /** Indirect edges: FILTER JOIN GROUP. */
  role: string | null
  confidence: Confidence | string
  statement: number
  range: Range
}

export interface Lineage {
  nodes: LineageNode[]
  edges: LineageEdge[]
}

export const ROWS_COLUMN = '*rows*'

export type FindingSeverity = 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO'

export interface Finding {
  /** Rule id, e.g. AMB-S002. `-- amber:ignore <rule>` in the statement suppresses it. */
  rule: string
  severity: FindingSeverity | string
  title: string
  message: string
  suggestion: string | null
  statement: number
  range: Range
  /** INFERRED means "possible": there was no metadata to prove it. */
  confidence: Confidence | string
}

export interface AnalysisResult {
  status: AnalysisStatus
  dialect: string
  name: string
  lineCount: number
  elapsedMs: number
  diagnostics: Diagnostic[]
  statements: Statement[]
  lifecycles: Lifecycle[]
  stepCount: number
  /** Column lineage of the whole script. Missing from engines older than lineage support. */
  lineage?: Lineage
  /** Risk and improvement hints. Missing from engines older than findings support. */
  findings?: Finding[]
}

export interface AnalyzeParams {
  dialect: string
  text?: string
  path?: string
  encoding?: string
  name?: string
  allowFallback?: boolean
  /** Paths of metadata packs (JSON). */
  metadataPacks?: string[]
  /** DDL files whose CREATE TABLE / CREATE INDEX statements describe tables. */
  ddlFiles?: string[]
  /** Table metadata passed inline, e.g. `pack.tables` from `db.loadMetadata`. */
  metadata?: TableMeta[]
}

export interface ColumnMeta {
  name: string
  type?: string | null
  nullable?: boolean | null
}

export interface TableMeta {
  catalog?: string | null
  schema?: string | null
  name: string
  kind?: string | null
  columns?: ColumnMeta[]
  primaryKey?: string[]
  uniqueKeys?: string[][]
  indexes?: { name: string; columns: string[]; unique: boolean }[]
  rowEstimate?: number | null
  /** Data source id (online) or where the table was described. */
  source?: string | null
}

/** Metadata pack file, `format` 1. */
export interface MetadataPack {
  format: number
  dialect: string
  source: string
  created: string
  tables: TableMeta[]
}

/** A database to read metadata from. The password is added in the main process only. */
export interface DataSource {
  id: string
  dialect: string
  url: string | null
  host: string
  port: number
  database: string
  user: string
  password: string
  properties: Record<string, string>
}

export interface ServerInfo {
  product: string
  version: string
  majorVersion: number
  minorVersion: number
  user: string | null
  readOnly: boolean
}

export interface LoadMetadataResult {
  /** Number of tables found. */
  tables: number
  missing: string[]
  /**
   * Names found in several data sources. `chosen` is the source whose table was used: one of the
   * script's database family first, then the table with most of the columns the script uses, then order.
   */
  ambiguous: { name: string; dataSources: string[]; chosen?: string | null }[]
  /** Data source id → error message. */
  errors: Record<string, string>
  /** Data source id → server info. */
  servers: Record<string, ServerInfo>
  saved: string | null
  pack: MetadataPack
}

export interface MetadataImportResult {
  dialect: string
  tables: number
  names: string[]
  saved: string | null
  pack: MetadataPack
}

/** `ai.buildContext`: the analyze params plus scope, budget and masking. */
export interface AiBuildContextParams extends AnalyzeParams {
  /** Statement ids in scope; empty means the whole script. */
  statements: number[]
  tokenBudget?: number
  mask?: boolean
  /** The developer's own notes for the model; masked with the same aliases, never trimmed. */
  notes?: string
}

export interface AiContext {
  system: string
  user: string
  tokenEstimate: number
  statements: number[]
  /** What did not fit the budget, as text, e.g. "statement 7 (line 120)" or "table metadata". */
  trimmed: string[]
  /** alias → original. Stays on this machine; never sent to a provider. */
  mask: Record<string, string>
}

export interface AiCheckParams {
  dialect: string
  original: string
  rewritten: string
  mask?: Record<string, string>
  metadataPacks?: string[]
  ddlFiles?: string[]
  metadata?: TableMeta[]
}

export type AiCheckLabel = 'STRUCTURE_PRESERVED' | 'STRUCTURE_CHANGED' | 'INVALID_SQL'

export interface AiCheckResult {
  label: AiCheckLabel | string
  /** The rewritten SQL with masked names restored. */
  sql: string
  differences: string[]
  diagnostics: Diagnostic[]
}

/** `references`: an object by name, or a column of a table. */
export type ReferenceQuery = { object: string } | { table: string; column: string }

export interface Occurrence {
  statement: number
  /** The Ref access, or READ / WRITE / CREATE for columns. */
  access: Access | string
  range: Range
  /** "12: <source line>" */
  line: string
}

export interface ReferencesResult {
  name: string
  /** Object kind, COLUMN, or null when nothing matched. */
  kind: string | null
  /** Where the script defines it, or null when it comes from outside the script. */
  definition: Occurrence | null
  occurrences: Occurrence[]
}

export interface DocGenerateResult {
  format: 'markdown' | 'html' | string
  text: string
  saved: string | null
}

// ---------------------------------------------------------------------------------------------
// diff: change impact between two versions of a script

export type DiffRisk = 'HIGH' | 'MEDIUM' | 'LOW' | string
export type PairStatus = 'UNCHANGED' | 'FORMAT_ONLY' | 'MODIFIED' | 'ADDED' | 'REMOVED' | 'MOVED' | string

export interface DiffSideText {
  text?: string
  path?: string
  encoding?: string
  name?: string
}

export interface DiffParams {
  dialect: string
  before: DiffSideText
  after: DiffSideText
  metadataPacks?: string[]
  ddlFiles?: string[]
  metadata?: TableMeta[]
}

export interface DiffSide {
  name: string
  status: AnalysisStatus | string
  /** Strict parsing failed, so this side was compared with fallback parsing. */
  fallback: boolean
  lineCount: number
  stepCount: number
  unparsed: number
  diagnostics: Diagnostic[]
}

/** Common to every change: where it is on each side (either side may be missing). */
export interface DiffLocated {
  beforeStatement: number | null
  afterStatement: number | null
  beforeRange: Range | null
  afterRange: Range | null
}

export interface DiffReason extends DiffLocated {
  level: DiffRisk
  code: string
  message: string
}

export interface DiffSummary {
  risk: DiffRisk
  headline: string
  statements: { unchanged: number; formatOnly: number; modified: number; added: number; removed: number; moved: number }
  objectChanges?: number
  columnChanges?: number
  rowInfluenceChanges?: number
  shapeChanges?: number
  newFindings?: number
  resolvedFindings?: number
  controlFlowChanges?: number
  reasons: DiffReason[]
  notes: string[]
}

export interface DiffHunk {
  before: Range
  after: Range
  beforeText: string
  afterText: string
}

export interface StatementPair {
  status: PairStatus
  kind: string
  target: string | null
  beforeId: number | null
  afterId: number | null
  beforeRange: Range | null
  afterRange: Range | null
  beforeSummary: string | null
  afterSummary: string | null
  similarity: number
  reordered: boolean
  hunks: DiffHunk[]
  children: StatementPair[]
}

export interface ObjectChange extends DiffLocated {
  scope: 'SCRIPT' | 'STATEMENT' | string
  kind: string
  object: string
  key: string
  objectKind: string | null
  access: string | null
  beforeRole: string | null
  afterRole: string | null
  message: string
}

export interface ColumnChange extends DiffLocated {
  kind: 'ADDED' | 'REMOVED' | 'SOURCES_CHANGED' | 'TRANSFORM_CHANGED' | string
  target: string
  targetKey: string
  column: string
  ownerKind: string
  beforeSources: string[]
  afterSources: string[]
  addedSources: string[]
  removedSources: string[]
  beforeTransforms: string[]
  afterTransforms: string[]
  message: string
}

export interface IndirectChange extends DiffLocated {
  target: string
  targetKey: string
  role: 'FILTER' | 'JOIN' | 'GROUP' | string
  change: 'ADDED' | 'REMOVED' | string
  source: string
  message: string
}

export interface ShapeChange extends DiffLocated {
  kind: string
  block: string | null
  detail: string | null
  message: string
}

export interface FindingChange extends DiffLocated {
  change: 'NEW' | 'RESOLVED' | string
  rule: string
  severity: string
  title: string
  message: string
}

export interface ControlFlowChange extends DiffLocated {
  kind: string
  target: string | null
  beforePath: string[]
  afterPath: string[]
  message: string
}

export interface DiffResult {
  dialect: string
  before: DiffSide
  after: DiffSide
  elapsedMs: number
  summary: DiffSummary
  statements: StatementPair[]
  objects: ObjectChange[]
  lineage: { columns: ColumnChange[]; indirect: IndirectChange[] }
  shapes: ShapeChange[]
  findings: FindingChange[]
  controlFlow: ControlFlowChange[]
}

export interface FormatResult {
  text: string
  changed: boolean
  /** Why a statement was kept as written, e.g. "Line 2: statement kept as written (…)". */
  warnings: string[]
  skipped: Range[]
}

// ---------------------------------------------------------------------------------------------
// rewrite.suggest: deterministic quick-fixes for findings

export type RewriteSafety = 'EQUIVALENT' | 'EQUIVALENT_WITH_METADATA' | 'CHANGES_BEHAVIOR_IN_EDGE_CASES' | string

export interface RewriteEdit {
  range: Range
  replacement: string
}

export interface Rewrite {
  id: string
  rule: string
  title: string
  explanation: string
  statement: number
  edits: RewriteEdit[]
  /** The whole statement after the edits. */
  rewrittenStatement: string
  safety: RewriteSafety
  safetyNote: string | null
  /** As `ai.check`: STRUCTURE_PRESERVED, STRUCTURE_CHANGED or INVALID_SQL. */
  check: AiCheckLabel | string
  checkDifferences: string[]
  /** An earlier rewrite whose edits overlap this one's. */
  conflictsWith: string | null
}

/** Methods the UI knows about. Some are not implemented by every engine version. */
export type EngineMethod =
  | 'engine.info'
  | 'analyze'
  | 'lineage'
  | 'impact'
  | 'findings'
  | 'diff'
  | 'rewrite.suggest'
  | 'format'
  | 'export'
  | 'metadata.import'
  | 'metadata.export'
  | 'db.test'
  | 'db.loadMetadata'
  | 'ai.buildContext'
  | 'ai.check'
  | 'ai.maskText'
  | 'references'
  | 'doc.generate'
  | 'rewrite.apply'

export interface RpcErrorObject {
  code: number
  message: string
  data?: unknown
}
