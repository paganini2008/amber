import type { En } from '../en'

export const lineage: En['lineage'] = {
  title: 'Linaje de columnas',
  notAvailable: 'Actualice el motor para ver de dónde vienen los valores de cada columna.',
  find: 'Buscar una columna',
  columnsOf: 'Columnas de',
  showAllColumns: 'Mostrar todas las columnas',
  columns: 'Columnas',
  noMatch: 'Ninguna columna coincide.',
  writtenByStep: 'Columnas escritas por el paso {step}',
  counts: '{columns, plural, one {# columna} other {# columnas}} en {owners, plural, one {# objeto} other {# objetos}}',
  truncated: 'se muestran las primeras {count} columnas del recorrido',
  direction: {
    label: 'Dirección',
    upstream: {
      label: 'De dónde viene',
      hint: 'Aguas arriba: seguir el valor hasta las tablas de origen'
    },
    downstream: {
      label: 'A qué afecta',
      hint: 'Aguas abajo: ¿qué se rompe si cambio esto?'
    },
    both: {
      label: 'Ambas',
      hint: 'Aguas arriba y aguas abajo'
    }
  },
  indirect: {
    short: 'Filtros, uniones, grupos',
    label: 'Incluir filtros, uniones y grupos',
    hint: 'Las columnas usadas en WHERE, JOIN y GROUP BY deciden qué filas llegan'
  },
  fit: 'Ajustar todo el recorrido',
  empty: {
    pick: '¿Qué se rompe si cambia una columna? Elija una a la izquierda para ver de dónde vienen sus valores y a qué alimentan.',
    step: 'Este paso no mueve ninguna columna.',
    column: 'Esta columna no tiene linaje.'
  },
  layingOut: 'Distribuyendo',
  clipped: 'Parte del recorrido queda fuera de la vista. Arrastre para desplazarse o use Ajustar para ver todo el recorrido.',
  legend: {
    flows: 'el valor fluye',
    decides: 'decide las filas (filtro, unión, grupo)',
    variable: 'variable',
    parameter: 'parámetro',
    clickEdge: 'Haga clic en una arista para ver su SQL'
  },
  mapping: {
    trace: 'Rastrear {column} ({kind})',
    traceOutput: 'Rastrear {column}',
    showWhere: 'Mostrar dónde ocurre',
    moreSources: '+{count, plural, one {# origen más} other {# orígenes más}}',
    decidedBy: 'Qué filas llegan lo deciden',
    seen: 'Columnas vistas en el script',
    open: 'Abrir en el linaje'
  },
  card: {
    rows: 'qué filas llegan',
    value: 'valor',
    moreColumns: '+{count, plural, one {# columna más} other {# columnas más}}'
  },
  scope: {
    block: 'bloque (línea {line})',
    batch: 'lote (línea {line})'
  },
  owner: {
    variableIn: 'variable en {scope}',
    parameterOf: 'parámetro de {scope}',
    unresolvedTitle: 'Tabla sin resolver',
    unresolvedSubtitle: 'sin resolver: no se pudo determinar la tabla',
    resultOfStep: 'Resultado del paso {step}',
    queryResult: 'Resultado de la consulta'
  },
  ownerKind: {
    TABLE: 'tabla',
    TEMP_TABLE: 'tabla temporal',
    TABLE_VARIABLE: 'variable de tabla',
    VIEW: 'vista',
    MATERIALIZED_VIEW: 'vista materializada',
    CTE: 'CTE',
    SUBQUERY: 'subconsulta',
    RESULT: 'resultado de consulta',
    VARIABLE: 'variable',
    PARAMETER: 'parámetro',
    UNKNOWN: 'sin resolver'
  },
  transform: {
    PASS_THROUGH: 'copia',
    EXPRESSION: 'expresión',
    AGGREGATE: 'agregado',
    WINDOW: 'ventana',
    CASE: 'case',
    CAST: 'conversión',
    CONSTANT: 'constante'
  },
  role: {
    FILTER: 'filtro',
    JOIN: 'unión',
    GROUP: 'grupo',
    INDIRECT: 'indirecto'
  }
}
