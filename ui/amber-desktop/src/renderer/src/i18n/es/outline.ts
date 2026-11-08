import type { En } from '../en'

export const outline: En['outline'] = {
  stats: {
    lines: '{count, plural, one {línea} other {líneas}}',
    statements: '{count, plural, one {sentencia} other {sentencias}}',
    sources: '{count, plural, one {origen} other {orígenes}}',
    intermediate: '{count, plural, one {intermedia} other {intermedias}}',
    targets: '{count, plural, one {destino} other {destinos}}',
    ms: '{count, plural, other {ms de análisis}}'
  },
  listLabel: 'Sentencias en el orden del código',
  expand: 'Expandir',
  collapse: 'Contraer',
  findings: '{count, plural, one {# hallazgo} other {# hallazgos}}',
  loop: 'bucle',
  via: {
    hint: 'Lo que hace {routine} cuando esta sentencia la llama',
    writes: 'escribe {writes, plural, one {# tabla} other {# tablas}} a través de {routine}',
    reads: 'lee {reads, plural, one {# tabla} other {# tablas}} a través de {routine}',
    writesReads: 'escribe {writes} y lee {reads, plural, one {# tabla} other {# tablas}} a través de {routine}'
  }
}
