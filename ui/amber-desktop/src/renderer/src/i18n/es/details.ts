import type { En } from '../en'

export const details: En['details'] = {
  title: 'Detalles',
  emptyNoScript: 'Aquí aparecen los detalles de la sentencia o el objeto seleccionado.',
  emptyHint: 'Antes de cambiar algo, selecciónelo aquí: una sentencia o una tabla muestra de dónde vienen sus datos y qué depende de ella.',
  source: 'Código',
  moreLines: '{count, plural, one {… # línea más} other {… # líneas más}}',
  finding: {
    aria: 'Hallazgo',
    possible: 'posible',
    possibleHint: 'No había metadatos para demostrarlo',
    position: 'línea {line}, columna {column}',
    suggestion: 'Sugerencia: ',
    ignoreBefore: 'Para ignorarlo, agregue',
    ignoreAfter: 'en la sentencia o en la línea anterior.',
    copyIgnore: 'Copiar el comentario para ignorar',
    copiedIgnore: 'Comentario para ignorar copiado',
    findings: 'Hallazgos'
  }
}
