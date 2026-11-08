import type { En } from '../en'

export const er: En['er'] = {
  title: 'Diagrama ER',
  graphLabel: 'Diagrama entidad-relación',
  loading: 'Leyendo tablas y uniones…',
  layingOut: 'Organizando el diagrama…',
  unavailable: 'Esta versión del motor no puede crear un diagrama ER.',
  error: 'No se pudo crear el diagrama ER: {message}',
  analyzeFirst: 'Analice primero el script; el diagrama se crea a partir del análisis.',
  empty: {
    noTables: 'Este script no lee ni escribe ninguna tabla.',
    noRelationships: 'No se encontraron relaciones. Las crean las claves foráneas, las uniones y los nombres de columna coincidentes.',
    filtered: 'Ninguna relación coincide con los filtros.'
  },
  search: 'Buscar una tabla',
  searchMatches: '{count, plural, =0 {Sin coincidencias} one {# coincidencia} other {# coincidencias}}',
  fit: 'Ajustar a la vista',
  copyMermaid: 'Copiar Mermaid',
  mermaidCopied: 'Se copió el erDiagram de Mermaid.',
  dataFlow: 'Flujo de datos',
  dataFlowHint: 'Dibujar también cómo se mueven los datos entre tablas (INSERT … SELECT), no solo claves y uniones.',
  minConfidence: 'Mostrar',
  confidenceAtLeast: {
    POSSIBLE: 'Todas las relaciones',
    INFERRED: 'Inferidas y seguras',
    CERTAIN: 'Solo seguras'
  },
  evidenceLabel: 'Evidencia',
  evidence: {
    DECLARED_FK: 'FK declarada',
    METADATA_FK: 'FK de la base',
    JOIN: 'Uniones',
    NAME_MATCH: 'Nombres',
    DATA_FLOW: 'Flujo de datos'
  },
  evidenceLong: {
    DECLARED_FK: 'Una clave foránea declarada en el script',
    METADATA_FK: 'Una clave foránea de la base de datos (metadatos)',
    JOIN: 'Unidas en el script',
    NAME_MATCH: 'Solo lo sugieren los nombres de columna',
    DATA_FLOW: 'Los datos pasan de una tabla a la otra'
  },
  confidence: {
    CERTAIN: 'Segura',
    INFERRED: 'Inferida',
    POSSIBLE: 'Posible'
  },
  cardinality: {
    MANY_TO_ONE: 'muchos a uno',
    ONE_TO_ONE: 'uno a uno',
    ONE_TO_MANY: 'uno a muchos',
    MANY_TO_MANY: 'muchos a muchos',
    UNKNOWN: 'cardinalidad desconocida'
  },
  around: {
    label: 'Alrededor de esta tabla',
    hops: '{count, plural, one {# paso} other {# pasos}}',
    showing: 'Alrededor de {name}',
    clear: 'Mostrar todas las tablas'
  },
  card: {
    pk: 'Clave primaria',
    fk: 'Clave foránea',
    joined: 'Usada en una unión',
    inScript: 'Creada en este script',
    more: '{count, plural, one {+# columna más} other {+# columnas más}}',
    less: 'Mostrar solo las columnas clave',
    noColumns: 'Sin columnas conocidas',
    source: {
      DDL: 'del DDL del script',
      METADATA: 'de los metadatos de la base',
      USAGE: 'del uso en el script'
    }
  },
  isolated: {
    title: '{count, plural, one {# tabla sin relaciones} other {# tablas sin relaciones}}',
    show: 'Mostrar',
    hide: 'Ocultar'
  },
  legend: {
    certain: 'segura (clave foránea)',
    inferred: 'inferida de uniones',
    possible: 'posible (solo nombres)',
    many: 'muchos',
    one: 'uno',
    flow: 'flujo de datos',
    hint: 'Haga clic en una línea para ver sus uniones'
  },
  panel: {
    relationship: 'Relación',
    entity: 'Tabla',
    close: 'Cerrar',
    evidence: 'Evidencia',
    confidence: 'Confianza',
    cardinality: 'Cardinalidad',
    constraint: 'Restricción',
    note: 'Nota del motor',
    occurrences: '{count, plural, =0 {No se une en el script} one {Aparece en # lugar} other {Aparece en # lugares}}',
    occurrencesShown: 'Se muestran los primeros {shown} de {count}.',
    occurrenceLine: 'Línea {line}',
    relationships: '{count, plural, =0 {Sin relaciones} one {# relación} other {# relaciones}}',
    columns: '{count, plural, one {# columna} other {# columnas}}',
    kind: 'Tipo',
    focus: 'Ver alrededor de esta tabla',
    jump: 'Mostrar en el editor'
  },
  stats: '{entities, plural, one {# tabla} other {# tablas}} · {relationships, plural, one {# relación} other {# relaciones}} · {joins, plural, one {# condición de unión leída} other {# condiciones de unión leídas}} · {unresolved} sin resolver',
  upgraded: '{count, plural, one {Los metadatos de la base confirmaron # relación.} other {Los metadatos de la base confirmaron # relaciones.}}',
  upgradedDismiss: 'Descartar'
}
