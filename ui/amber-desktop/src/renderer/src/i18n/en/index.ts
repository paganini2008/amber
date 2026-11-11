/**
 * English messages: the source of truth. Every other language has exactly these keys (checked by
 * the compiler and by i18n.test.ts) and the same {placeholders}.
 *
 * Glossary (terms used consistently across the app; other languages translate from this list):
 *   finding          a risk or improvement hint the engine reports for a statement (rule AMB-…)
 *   lineage          where a column's value comes from and what it affects
 *   lifecycle        what happens to one object over the script (created, filled, read, dropped)
 *   logical order    the order in which a query's clauses are evaluated (FROM before SELECT …)
 *   change impact    what a change to the script affects downstream
 *   quick fix        a rewrite of a statement the engine proposes, with a stated safety level
 *   outline          the tree of statements in the script
 *   data flow        the graph of objects and the statements that move data between them
 *   execution playback  stepping through the statements in execution order
 *   references       every place an object or column is used
 *   handover document   the generated description of a script for the next maintainer
 *   online mode      reading table definitions (metadata only) from a live database
 *   routine          a procedure or function; "via" names the routine a call goes through
 *   variable / parameter  values held by a routine; they can carry lineage
 *   certain / inferred    confidence: proven from the text or metadata / likely, not proven
 *   ER diagram       tables and the relationships (foreign keys, joins) between them; possible = names only
 *   optimized SQL    a statement with all verified rule-based rewrites applied (no AI)
 *
 * Not translated: SQL, object and column names, keyboard shortcuts, rule ids and enum codes. The engine
 * writes its own prose in the UI language (it receives `locale`): statement summaries, findings, diff
 * reasons, rewrite notes, check differences, ER notes, the handover document. Parser diagnostics and
 * JSON-RPC error messages stay English.
 */
import { common } from './common'
import { meta } from './meta'
import { menu } from './menu'
import { main } from './main'
import { kinds } from './kinds'
import { findings } from './findings'
import { settings } from './settings'
import { app } from './app'
import { dialogs } from './dialogs'
import { online } from './online'
import { samples } from './samples'
import { details } from './details'
import { fixes } from './fixes'
import { suggestions } from './suggestions'
import { refactor } from './refactor'
import { references } from './references'
import { editor } from './editor'
import { diff } from './diff'
import { ai } from './ai'
import { outline } from './outline'
import { lifecycle } from './lifecycle'
import { dataflow } from './dataflow'
import { lineage } from './lineage'
import { statement } from './statement'
import { structure } from './structure'
import { playback } from './playback'
import { exports } from './exports'
import { optimize } from './optimize'
import { rules } from './rules'
import { er } from './er'

export const en = {
  common,
  meta,
  menu,
  main,
  kinds,
  findings,
  settings,
  app,
  dialogs,
  online,
  samples,
  details,
  fixes,
  suggestions,
  refactor,
  references,
  editor,
  diff,
  ai,
  outline,
  lifecycle,
  dataflow,
  lineage,
  statement,
  structure,
  playback,
  exports,
  optimize,
  rules,
  er
}

export type En = typeof en
