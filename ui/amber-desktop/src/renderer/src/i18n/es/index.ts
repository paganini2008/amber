/**
 * Spanish messages, neutral (no regional words; "usted" in instructions, infinitives on buttons).
 * Same keys and {placeholders} as en (checked by the compiler and i18n.test.ts).
 *
 * Glossary:
 *   finding hallazgo · lineage linaje · lifecycle ciclo de vida · logical order orden lógico ·
 *   change impact impacto del cambio · quick fix corrección rápida · outline esquema ·
 *   data flow flujo de datos · statement sentencia · structure estructura ·
 *   execution playback reproducción de la ejecución · references referencias · details detalles ·
 *   handover document documento de traspaso · online mode modo en línea · metadata metadatos ·
 *   dialect dialecto · engine motor · routine rutina · procedure procedimiento · call llamada ·
 *   via a través de · variable variable · parameter parámetro ·
 *   source / intermediate / target origen / intermedia / destino · certain / inferred seguro / inferido ·
 *   severity gravedad · script script · column columna · table tabla ·
 *   ER diagram diagrama ER · relationship relación · foreign key clave foránea · primary key clave primaria ·
 *   cardinality cardinalidad · possible posible · optimized SQL SQL optimizado · rule regla
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

export const es: En = {
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
