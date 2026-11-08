import type { En } from '../en'

export const optimize: En['optimize'] = {
  badge: 'Por reglas · sin IA',
  badgeTitle: 'Calculado por las reglas de Amber a partir del análisis y comprobado por el analizador. No interviene ninguna IA ni se ejecuta nada.',
  card: {
    title: 'SQL optimizado',
    statementLine: 'Sentencia {number} · línea {line}',
    whatChanged: 'Qué cambia',
    why: 'Por qué',
    notApplied: 'No aplicado',
    check: 'Comprobación del analizador:',
    differences: 'Diferencias estructurales',
    compare: 'Comparar',
    compareTitle: 'Mostrar la sentencia y su versión optimizada lado a lado',
    apply: 'Aplicar al editor',
    applyTitle: 'Reemplaza la sentencia en el editor en un solo paso que se puede deshacer. El archivo no se guarda.',
    stale: 'El script cambió desde que se calculó. Analice de nuevo para actualizarlo.',
    note: 'Nota'
  },
  ddl: {
    title: 'DDL sugerido (no se aplica al script)',
    hint: 'Pistas basadas en el uso del script y las definiciones de tabla. Revise los volúmenes de datos, los índices existentes y la carga de escritura antes de crear nada.',
    copy: 'Copiar DDL'
  },
  skip: {
    CONFLICT: 'se solapa con un cambio aplicado',
    EDGE_CASE: 'cambia el comportamiento en casos límite',
    DISABLED: 'regla desactivada en Configuración → Reglas',
    UNVERIFIED: 'la sentencia combinada no pasó la comprobación del analizador'
  },
  verified: {
    STRUCTURE_PRESERVED: 'Misma estructura',
    STRUCTURE_CHANGED: 'Estructura cambiada',
    NOT_CHECKED: 'Solo analizado',
    INVALID_SQL: 'SQL no válido'
  },
  panel: {
    title: 'Sentencias optimizadas',
    summary: '{count, plural, one {# sentencia tiene una versión optimizada} other {# sentencias tienen una versión optimizada}}',
    applySafe: 'Aplicar las optimizaciones seguras ({count})',
    applySafeTitle: 'Aplica solo las sentencias cuyos cambios devuelven las mismas filas con cualquier dato («Mismo resultado»), en un solo paso que se puede deshacer.',
    loading: 'Calculando sentencias optimizadas…',
    none: 'No hay sentencias optimizadas para este script.',
    unavailable: 'Esta versión del motor no puede optimizar sentencias.',
    quickFixes: 'Correcciones rápidas, de una en una',
    ddlOnly: 'Solo DDL sugerido',
    open: 'Abrir'
  },
  finding: {
    show: 'Ver SQL optimizado',
    showDdl: 'Ver DDL sugerido',
    copyOnly: 'Este SQL reemplaza más que esta sentencia. Cópielo y edite el script a mano.'
  },
  dialog: {
    title: 'SQL optimizado',
    description: 'Por reglas, sin IA. No se ejecuta ni se guarda nada: Aplicar solo cambia el editor, en un paso que se puede deshacer.',
    now: 'Ahora',
    after: 'Optimizado'
  },
  toast: {
    applied: '{count, plural, one {Se aplicó la sentencia optimizada} other {Se aplicaron # sentencias optimizadas}}',
    noMatch: 'El script ya no coincide con la sentencia optimizada. Analice de nuevo.'
  },
  details: {
    title: 'SQL optimizado',
    rewrites: '{count, plural, one {# cambio} other {# cambios}}'
  }
}
