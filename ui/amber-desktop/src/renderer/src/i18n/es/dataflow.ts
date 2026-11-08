import type { En } from '../en'

export const dataflow: En['dataflow'] = {
  summary: '{objects, plural, one {# objeto} other {# objetos}} · {statements, plural, one {# sentencia mueve} other {# sentencias mueven}} datos',
  grouped: {
    label: '{count, plural, one {# grupo} other {# grupos}} (más de 300 nodos): haga doble clic en un grupo para abrirlo',
    hint: 'Agrupado en {count, plural, one {# grupo} other {# grupos}} porque el grafo tiene más de 300 nodos. Haga doble clic en un grupo para abrirlo.'
  },
  group: {
    openHint: 'Doble clic para abrir este grupo',
    steps: 'Pasos {from}–{to}',
    internal: '{count, plural, one {# tabla interna} other {# tablas internas}}'
  },
  role: {
    SOURCE: 'origen',
    INTERMEDIATE: 'intermedia',
    TARGET: 'destino',
    STRUCTURE: 'solo estructura'
  },
  edge: {
    via: 'vía {routine}'
  },
  regroup: 'Reagrupar',
  focus: 'Enfocar la selección',
  fit: 'Ajustar el grafo',
  graphLabel: 'Grafo de flujo de datos',
  empty: 'Ninguna sentencia de este script mueve datos entre tablas.',
  layingOut: 'Distribuyendo {count, plural, one {# nodo} other {# nodos}}',
  layoutFailed: 'No se pudo distribuir el grafo: {error}'
}
