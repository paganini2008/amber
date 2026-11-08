import type { En } from '../en'

export const rules: En['rules'] = {
  tab: 'Reglas',
  lead: 'Qué comprobaciones se ejecutan y qué gravedad tienen sus hallazgos. Los cambios se aplican de inmediato al análisis, las correcciones rápidas, el SQL optimizado, el documento de traspaso, el impacto del cambio y el contexto de IA.',
  search: 'Buscar reglas',
  category: {
    risk: 'Riesgos',
    performance: 'Rendimiento',
    maintainability: 'Mantenibilidad',
    info: 'Información',
    other: 'Otras'
  },
  enable: 'Activar o desactivar {id}',
  severity: 'Gravedad de {id}',
  severityDefault: 'Predeterminada ({severity})',
  needsMetadata: 'Necesita definiciones de tabla',
  needsMetadataTitle: 'Solo informa cuando se conocen las definiciones de tabla: archivos DDL, un paquete de metadatos o el modo en línea.',
  hasQuickFix: 'Con corrección rápida',
  hasQuickFixTitle: 'Puede ofrecer una reescritura verificada de la sentencia.',
  reset: 'Restablecer valores',
  changed: '{count, plural, =0 {Todas las reglas usan sus valores predeterminados} one {# regla modificada} other {# reglas modificadas}}',
  off: 'Desactivada',
  loading: 'Cargando reglas…',
  unavailable: 'Esta versión del motor no tiene configuración de reglas.',
  error: 'No se pudieron cargar las reglas: {message}',
  noMatch: 'Ninguna regla coincide con «{query}».'
}
