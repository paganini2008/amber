import type { En } from '../en'

export const lifecycle: En['lifecycle'] = {
  filter: 'Filtrar objetos',
  groupBy: 'Agrupar por',
  groupByLabel: 'Agrupar u ordenar objetos',
  sort: {
    role: 'Rol',
    kind: 'Tipo',
    firstUse: 'Primer uso (sin grupos)',
    name: 'Nombre (sin grupos)'
  },
  group: {
    SOURCE: 'Orígenes',
    INTERMEDIATE: 'Intermedias',
    TARGET: 'Destinos',
    STRUCTURE: 'Solo estructura'
  },
  allSteps: 'Todos los pasos',
  allStepsLabel: 'Mostrar todos los pasos',
  empty: {
    filtered: 'Ningún objeto coincide con el filtro.',
    none: 'Este script no usa ninguna tabla ni vista.'
  },
  matrixLabel: 'Matriz del ciclo de vida de los objetos',
  createdHere: 'creado aquí',
  droppedHere: 'eliminado aquí',
  columnLineage: 'Linaje de columnas',
  columnLineageOf: 'Linaje de columnas de {name}',
  cellTitle: '{name} · paso {step}',
  shortKind: {
    TEMP_TABLE: 'temp',
    TABLE_VARIABLE: 'var',
    MATERIALIZED_VIEW: 'vmat'
  },
  legend: 'Leyenda',
  via: 'a través de una llamada',
  viaHint: 'Se accede al objeto a través de una llamada a una rutina definida en el script',
  event: {
    direct: '{access}, línea {line}',
    via: '{access} a través de {via}, línea {line}'
  }
}
