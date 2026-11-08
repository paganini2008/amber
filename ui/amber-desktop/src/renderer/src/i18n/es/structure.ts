import type { En } from '../en'

export const structure: En['structure'] = {
  title: 'Estructura de la consulta',
  expandAll: 'Expandir todo',
  collapseAll: 'Contraer todo',
  expand: 'Expandir',
  collapse: 'Contraer',
  empty: {
    select: 'Seleccione una sentencia para desarmar sus consultas anidadas, bloque a bloque, en el orden en que se leen.',
    engine: 'Esta versión del motor no informa la estructura de las consultas.',
    none: 'Esta sentencia no tiene estructura de consulta.'
  },
  counts: {
    none: 'Sin estructura de consulta',
    blocks: '{count, plural, one {# bloque} other {# bloques}}',
    levels: '{count, plural, one {# nivel} other {# niveles}}',
    CTE: '{count, plural, one {# CTE} other {# CTE}}',
    SET: '{count, plural, one {# operación de conjuntos} other {# operaciones de conjuntos}}',
    DERIVED: '{count, plural, one {# tabla derivada} other {# tablas derivadas}}',
    SUBQUERY: '{count, plural, one {# subconsulta} other {# subconsultas}}',
    QUERY: '{count, plural, one {# consulta} other {# consultas}}'
  },
  steps: {
    ready: 'lista',
    list: '{count, plural, one {paso} other {pasos}} {items}'
  },
  box: {
    noSources: 'sin orígenes',
    noStructureLower: 'sin estructura de consulta',
    rootTitle: '{title} (línea {line})',
    collapsed: '{count, plural, one {# bloque anidado contraído} other {# bloques anidados contraídos}}',
    hiddenNested: '+{count} anidados',
    fewer: 'menos'
  },
  source: {
    title: '{kind} {name}. {action}.',
    titleAlias: '{kind} {name} como {alias}. {action}.',
    goCte: 'Ir a la CTE',
    goDerived: 'Ir a la tabla derivada',
    select: 'Seleccionar este objeto'
  },
  order: {
    title: 'Orden lógico',
    note: 'El orden que define el estándar SQL para leer la consulta. No se ejecuta nada, y el plan real de la base de datos puede ser distinto.',
    show: 'Mostrar el orden en que se lee la consulta',
    none: 'Esta sentencia no tiene orden lógico',
    boxSteps: 'Pasos de este bloque en el orden lógico',
    stepPhase: 'Paso {step}: {phase}',
    previous: 'Paso anterior (flecha izquierda)',
    next: 'Paso siguiente (flecha derecha)',
    play: 'Reproducir el orden lógico',
    pause: 'Pausar',
    reset: 'Volver a la consulta completa',
    count: '{count, plural, one {# paso} other {# pasos}}',
    stepOf: 'Paso {step} de {total}',
    write: 'Escritura',
    cteReady: 'CTE lista',
    inBlock: 'en {block}',
    theStatement: 'la sentencia',
    allDone: 'Todos los pasos de este bloque están hechos',
    done: 'hecho'
  },
  summary: {
    open: 'Abrir en la vista Estructura',
    more: '… {count} más en la vista Estructura',
    stepThrough: 'Recorrer en la vista Estructura',
    outline: 'Orden lógico, resumen',
    nested: '({count} anidados)',
    nestedHint: '{count, plural, one {Aquí se ejecuta # paso de consultas anidadas} other {Aquí se ejecutan # pasos de consultas anidadas}}'
  }
}
