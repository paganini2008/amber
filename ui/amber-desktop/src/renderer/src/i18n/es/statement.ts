import type { En } from '../en'

export const statement: En['statement'] = {
  empty: '¿Qué hace realmente esta sentencia? Selecciónela en el esquema, en un grafo o en el editor.',
  stepOf: 'Paso {step} de {total}',
  previous: 'Sentencia anterior',
  next: 'Sentencia siguiente',
  topLevel: 'Nivel superior',
  containing: 'Sentencias contenedoras',
  category: 'Categoría',
  confidence: 'Confianza',
  defines: 'Define',
  objects: 'Objetos',
  throughCall: 'A través de la llamada a',
  via: 'a través de {routine}',
  contains: 'Contiene {count, plural, one {# sentencia} other {# sentencias}}',
  object: {
    untracked: 'Este objeto no se sigue en el script.',
    find: 'Buscar referencias',
    findHint: 'Todos los lugares donde este script lo usa (Shift+F12)',
    facts: '{origin, select, created {Creado en este script} other {Existe antes del script}}{dropped, select, yes {, eliminado antes del final} other {}}, {count, plural, one {# acceso} other {# accesos}}.',
    lifecycle: 'Ciclo de vida'
  }
}
