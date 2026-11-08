import type { En } from '../en'

export const app: En['app'] = {
  views: {
    label: 'Vistas',
    outline: 'Esquema',
    lifecycle: 'Ciclo de vida',
    dataflow: 'Flujo de datos',
    lineage: 'Linaje',
    er: 'Diagrama ER',
    statement: 'Sentencia',
    structure: 'Estructura',
    playback: 'Reproducción',
    diff: 'Impacto del cambio'
  },
  themes: {
    system: 'Sistema',
    amberLight: 'Amber claro',
    amberDark: 'Amber oscuro',
    resin: 'Resina',
    highContrast: 'Alto contraste'
  },
  resize: {
    outline: 'Cambiar el ancho del esquema',
    editor: 'Cambiar el alto del editor',
    details: 'Cambiar el ancho de los detalles'
  },
  analysis: {
    analyze: 'Analizar',
    again: 'Analizar de nuevo',
    analyzing: 'Analizando',
    analyzingFile: 'Analizando {name}',
    didNotFinish: 'El análisis no terminó',
    edited: 'Editado desde el último análisis'
  },
  start: {
    title: 'Mantenga el SQL heredado que nadie quiere tocar',
    lead: 'Abra un script de miles de líneas, con tablas, procedimientos y tablas temporales escritos por muchas manos, y vea lo que realmente hace. No se ejecuta nada.',
    questionWhat: '¿Qué hace realmente este script?',
    questionBreaks: '¿Qué se rompe si cambio esta columna?',
    questionSafe: '¿Es seguro hacer este cambio?',
    questionHandover: '¿Lo va a traspasar? Exporte un documento de traspaso.',
    open: 'Abrir script…',
    paste: 'Pegar SQL…',
    drop: 'O suelte un archivo .sql en cualquier parte de esta ventana.',
    samples: 'Pruebe un ejemplo',
    samplesLead: 'Un pequeño script de estilo heredado para cada base de datos: tablas temporales, procedimientos, MERGE y consultas anidadas.',
    recent: 'Recientes'
  },
  drop: {
    open: 'Abrir el script',
    openHint: 'Reemplaza el script abierto',
    compare: 'Comparar con el script abierto',
    compareHint: 'El archivo soltado es la versión nueva. ¿Es seguro este cambio?'
  },
  title: {
    fileMenu: 'Archivo',
    noScript: 'Ningún script abierto',
    readable: 'Hacerlo legible de nuevo',
    formatScript: 'Formatear script',
    formatSelection: 'Formatear selección',
    isSafe: '¿Es seguro este cambio?',
    compareSaved: 'Comparar con el archivo guardado',
    compareOlder: 'Comparar con una versión anterior…',
    compareNewer: 'Comparar con una versión más reciente…',
    openSample: 'Abrir ejemplo',
    clearRecent: 'Borrar archivos recientes',
    about: 'Acerca de Amber',
    dialect: 'Dialecto SQL',
    theme: 'Tema',
    detailsPanel: 'Panel de detalles',
    settings: 'Configuración'
  },
  export: {
    label: 'Exportar',
    currentStructure: 'Estructura de la consulta actual',
    currentView: 'Grafo, matriz o linaje actual',
    png: 'PNG a {scale}×',
    mermaidStructure: 'Mermaid (subgrafos anidados)',
    mermaidLineage: 'Mermaid (linaje)',
    mermaidObjects: 'Mermaid (nivel de objetos)',
    json: 'Análisis como JSON',
    handover: 'Documento de traspaso para el siguiente responsable',
    preview: 'Vista previa…',
    markdown: 'Guardar como Markdown…',
    html: 'Guardar como HTML…'
  },
  files: {
    svg: 'Imagen SVG',
    png: 'Imagen PNG',
    html: 'Página HTML'
  },
  outline: {
    title: 'Esquema',
    steps: '{count, plural, one {# paso} other {# pasos}}',
    expandAll: 'Expandir todo',
    collapseAll: 'Contraer todo',
    expand: 'Expandir',
    collapse: 'Contraer',
    noStatements: 'No hay sentencias.',
    openScript: 'Abra un script para ver sus sentencias.',
    statements: 'Sentencias',
    findings: '{count, plural, one {# hallazgo} other {# hallazgos}}'
  },
  status: {
    error: 'Error',
    warning: 'Advertencia',
    findings: 'Hallazgos',
    metadata: 'Metadatos',
    references: 'Referencias',
    suggestedFixes: 'Sugerencias: SQL optimizado y correcciones rápidas',
    fixes: 'Sugerencias',
    syntax: 'Sintaxis',
    syntaxProblems: 'Problemas de sintaxis',
    formatReport: 'Informe de formato',
    keptAsWritten: 'Sin cambios',
    findingsUnavailable: 'Los hallazgos no están disponibles en esta versión del motor.',
    notAvailable: 'no disponible en esta versión del motor',
    noFindings: 'No hay hallazgos. Las reglas de Amber no encontraron nada riesgoso en este script.',
    noSyntaxProblems: 'No hay problemas de sintaxis.',
    possible: 'Posible: ',
    metadataTitle: 'Metadatos usados por el análisis',
    parse: 'Análisis: {state, select, failed {fallido} partial {tolerante, parcial} complete {tolerante, completo} other {estricto ✓}}',
    lines: '{count, plural, one {# línea} other {# líneas}}',
    engine: 'Motor {version}',
    engineStopped: 'Motor detenido',
    engineRestarting: 'Reiniciando el motor',
    engineStarting: 'Iniciando el motor',
    restart: 'Reiniciar'
  },
  error: {
    title: '{name} no se puede analizar en modo estricto',
    lead: '{count, plural, one {Un error de sintaxis detiene} other {# errores de sintaxis detienen}} el análisis. Compruebe que el dialecto sea el correcto, corrija el script o analice en modo tolerante: las sentencias que no se puedan analizar se conservan como bloques sin analizar y se marcan como parciales.',
    withFallback: 'Analizar en modo tolerante',
    unexpected: '«{token}» inesperado',
    lineColumn: 'línea {line}, columna {column}',
    expected: 'Se esperaba {tokens}'
  },
  partial: {
    banner: 'Analizado en modo tolerante. {partial, plural, one {# sentencia solo se entendió en parte y se muestra} other {# sentencias solo se entendieron en parte y se muestran}} atenuadas; {errors, plural, one {# error de sintaxis} other {# errores de sintaxis}} en Problemas.'
  },
  format: {
    nothing: 'El último formateo no dejó nada que informar.',
    explain: 'El formateador deja una sentencia tal como está cuando no puede demostrar que el nuevo diseño conserva los mismos tokens, por ejemplo junto a una cadena sin cerrar.',
    alreadyDone: 'Ya tiene formato: no hay nada que cambiar.',
    selectionDone: 'Las sentencias seleccionadas ya tienen formato.',
    script: 'Script formateado',
    statements: '{count, plural, one {# sentencia formateada} other {# sentencias formateadas}}',
    kept: '{count} sin cambios'
  },
  compare: {
    saved: '{name} (guardado)',
    edited: '{name} (editado)'
  },
  errors: {
    notAvailable: 'No disponible en esta versión del motor.',
    cancelled: 'Cancelado.',
    engineNotRunning: 'El motor no está en ejecución.',
    engineError: 'El motor informó un error.'
  },
  toast: {
    dismiss: 'Descartar',
    show: 'Ver',
    checkChange: 'Revisar el cambio',
    openBeforeCompare: 'Primero abra un script y luego compárelo con otra versión.',
    analyzeFirst: 'Primero abra y analice un script.',
    updateAvailable: 'Amber {version} está disponible. Vea Configuración → Actualizaciones.',
    openFailed: 'No se pudo abrir el archivo: {reason}',
    openPathFailed: 'No se pudo abrir {path}: {reason}',
    saved: 'Se guardó {file}',
    pickColumn: 'Primero elija una columna en la vista Linaje.',
    pickStructure: 'Primero seleccione una sentencia con estructura de consulta.',
    pngFailed: 'No se pudo crear el PNG: {reason}',
    noHandover: 'Esta versión del motor no puede generar documentos de traspaso.',
    handoverFailed: 'No se pudo crear el documento de traspaso: {reason}',
    noFileOnDisk: 'Este script no tiene archivo en disco. Use Comparar con… para elegir la otra versión.',
    savedReadFailed: 'No se pudo leer el archivo guardado: {reason}',
    noChanges: 'No hay cambios desde que se abrió o guardó el archivo.',
    readFailed: 'No se pudo leer {name}: {reason}',
    summaryCopied: 'Resumen de cambios copiado como Markdown.',
    referencesFailed: 'No se pudieron buscar las referencias.',
    definedOutside: '{name} está definido fuera de este script.',
    notFound: 'No se encontró nada llamado {name} en este script.',
    noFormat: 'Esta versión del motor todavía no puede formatear scripts.',
    formatFailed: 'No se pudo formatear: {reason}',
    changedWhileFormatting: 'El script cambió mientras se formateaba. Formatéelo de nuevo.',
    fixesStale: 'El script cambió desde que se sugirieron estas correcciones. Analícelo de nuevo para actualizarlas.',
    fixesOverlap: 'Estas correcciones se solapan. Aplíquelas de una en una.',
    appliedOne: 'Se aplicó «{title}»',
    appliedMany: '{count, plural, one {Se aplicó # corrección} other {Se aplicaron # correcciones}}',
    applied: 'Aplicado',
    appliedUnsaved: '{label}. El archivo en disco no cambia hasta que lo guarde.',
    noCompare: 'Esta versión del motor todavía no puede comparar scripts.',
    compareFailed: 'La comparación no terminó.',
    metadataFailed: 'No se pudieron leer los metadatos.'
  }
}
