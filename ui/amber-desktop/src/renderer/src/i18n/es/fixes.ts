import type { En } from '../en'

export const fixes: En['fixes'] = {
  safety: {
    EQUIVALENT: {
      label: 'Mismo resultado',
      detail: 'Devuelve las mismas filas con cualquier dato.'
    },
    EQUIVALENT_WITH_METADATA: {
      label: 'Mismo resultado según las definiciones de tablas',
      detail: 'Las mismas filas, siempre que los tipos de columna, la nulabilidad y las claves sean los descritos.'
    },
    CHANGES_BEHAVIOR_IN_EDGE_CASES: {
      label: 'Cambia el comportamiento en casos límite',
      detail: 'Lea la nota antes de aplicarla.'
    },
    notRated: 'Sin calificar'
  },
  quickFix: 'Corrección rápida: {title}',
  count: '{count, plural, one {# corrección rápida} other {# correcciones rápidas}}',
  fixItem: 'Corregir: {title}',
  fixButton: 'Corregir…',
  loading: 'Buscando correcciones',
  unavailable: 'Esta versión del motor todavía no sugiere correcciones.',
  stale: 'El script se editó después del último análisis. Vuelva a analizarlo para ver las correcciones que siguen siendo válidas.',
  none: 'No hay correcciones rápidas. Amber tiene correcciones basadas en reglas para algunos patrones heredados, como subconsultas con NOT IN y LEFT JOIN que actúan como uniones internas.',
  summary: '{count, plural, one {# corrección sugerida} other {# correcciones sugeridas}} para limpiar patrones heredados',
  summaryHint: 'El analizador comprueba cada una. Aplicarlas solo cambia el editor, nunca el archivo en disco.',
  applyAllTitle: 'Aplica todas las correcciones calificadas como «Mismo resultado» que no se solapan con otra',
  applyAll: 'Aplicar todas con «Mismo resultado» ({count})',
  statement: 'Sentencia {number}',
  applyFirst: 'Aplique primero {id} y vuelva a comprobar',
  preview: 'Vista previa',
  dialog: {
    title: 'Corrección rápida',
    description: '{rule} · limpiar este patrón heredado. Aplicarla solo cambia el editor; guarde el archivo usted mismo cuando esté conforme.',
    blocked: 'Aplique primero {id} y vuelva a comprobar: las dos correcciones tocan el mismo texto.',
    apply: 'Aplicar al editor',
    noLongerMatches: 'Esta corrección ya no coincide con el script. Vuelva a analizarlo para actualizar las sugerencias.',
    edgeCase: 'Caso límite: ',
    confirmEdge: 'Comprobé el caso límite: no puede ocurrir en este script',
    parserCheck: 'Comprobación del analizador:',
    now: 'Ahora',
    after: 'Tras la corrección'
  }
}
