/**
 * Simplified Chinese messages. Same keys and {placeholders} as en (checked by the compiler and
 * i18n.test.ts). Full-width punctuation (，。：；（）“”、), a space between Chinese and Latin
 * words or numbers, and no trailing full stop on buttons and labels. The reader is addressed as
 * 你 (plain, as in system software), never mixed with 您.
 *
 * Glossary:
 *   finding 发现项 · lineage 血缘 · lifecycle 生命周期 · logical order 逻辑执行顺序 ·
 *   change impact 变更影响 · quick fix 快速修复 · outline 大纲 · data flow 数据流 ·
 *   statement 语句 · structure 结构 · execution playback 执行回放 · references 引用 ·
 *   details 详情 · handover document 交接文档 · online mode 在线模式 · metadata 元数据 ·
 *   dialect 方言 · engine 引擎 · routine 例程 · procedure 存储过程 · call 调用 · via 经由 ·
 *   variable 变量 · parameter 参数 · source / intermediate / target 源 / 中间 / 目标 ·
 *   certain / inferred 确定 / 推断 · severity 严重程度 · script 脚本 · column 列 · table 表 ·
 *   ER diagram ER 图 · relationship 关系 · foreign key 外键 · primary key 主键 · cardinality 基数 ·
 *   possible 可能 · optimized SQL 优化后的 SQL · rule 规则
 *
 * Not translated: SQL, object and column names, keyboard shortcuts, rule ids and enum codes. The engine
 * writes its own prose in the UI language (it receives `locale`): statement summaries, findings, diff
 * reasons, rewrite notes, check differences, ER notes, the handover document. Parser diagnostics and
 * JSON-RPC error messages stay English.
 */
import type { En } from '../en'
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

export const zhCN: En = {
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
