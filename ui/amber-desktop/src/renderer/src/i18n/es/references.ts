import type { En } from '../en'

export const references: En['references'] = {
  goToDefinition: 'Ir a la definición de {name}',
  findReferences: 'Buscar referencias a {name}',
  findButton: 'Buscar referencias a {name} ({shortcut})',
  rowTitle: '{access} · línea {line}',
  rowTitleVia: '{access} a través de {via} · línea {line}',
  definition: 'definición',
  via: 'a través de',
  viaTitle: 'Esta llamada llega a él a través de {via}',
  column: 'Columna',
  header: 'Referencias a',
  count: '{places, plural, one {# lugar} other {# lugares}}{statements, plural, =0 {} one { en # sentencia} other { en # sentencias}}',
  shortcuts: 'F12 ir a la definición · ⇧F12 buscar referencias',
  close: 'Cerrar referencias',
  looking: 'Buscando {name}',
  idle: 'Antes de cambiar una tabla o columna, vea todos los lugares donde se usa: ponga el cursor sobre un nombre y pulse F12 o Mayús+F12, o haga clic derecho.',
  nothing: 'No se encontró nada llamado {name} en este script.',
  listAria: 'Referencias a {name}',
  external: 'Definido fuera de este script: aquí no hay CREATE ni WITH para él.',
  statement: 'Sentencia {number}',
  routineTitle: 'Ir a la definición de {name} (F12)'
}
