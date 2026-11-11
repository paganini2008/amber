import type { En } from '../en'

export const refactor: En['refactor'] = {
  button: 'Refactorizar script',
  title: 'Refactorizar script',
  lead: 'Amber aplica, pasada tras pasada, cada reescritura que puede demostrar que mantiene el resultado igual para cualquier dato, y muestra el resultado en Impacto del cambio. Nada cambia en el editor hasta que lo aplique.',
  format: 'Dar formato al resultado como Formatear script',
  run: 'Refactorizar',
  running: 'Refactorizando, pasada {pass}…',
  nothing: 'Amber no encontró nada que pueda refactorizar sin cambiar el resultado.',
  unavailable: 'Este motor no puede refactorizar scripts.',
  afterName: '{name} (refactorizado)',
  applied: 'Script refactorizado',
  banner: {
    title: 'Script refactorizado',
    rewrites: '{count, plural, one {# reescritura} other {# reescrituras}} en {passes, plural, one {# pasada} other {# pasadas}}, cada una con el mismo resultado.',
    noRewrites: 'No hizo falta ninguna reescritura.',
    formatted: 'Con el formato de Formatear script.',
    logicKept: 'Comprobación de la lógica: las condiciones, los valores, las tablas de origen, las columnas de salida y el linaje son los mismos que antes.',
    logicChanged: 'Comprobación de la lógica: Amber encontró una diferencia en la lógica. No aplique esta refactorización; infórmenos del problema.',
    discard: 'Descartar',
    apply: 'Aplicar al editor'
  },
  ai: 'Refactorizar con IA',
  aiHint: 'La IA reestructura la sentencia para quien la mantenga después. Amber aplica la reescritura solo si la comprobación de la lógica no encuentra ninguna diferencia.',
  aiTitle: 'Refactorización con IA'
}
