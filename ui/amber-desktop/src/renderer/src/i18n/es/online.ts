import type { En } from '../en'

export const online: En['online'] = {
  status: {
    online: 'En línea',
    reading: 'En línea · leyendo metadatos',
    failed: 'En línea · error de metadatos',
    tables: 'En línea · {count, plural, one {# tabla} other {# tablas}}',
    tablesCheck: 'En línea · {count, plural, one {# tabla} other {# tablas}} · revisar',
    files: '{count, plural, one {# archivo de metadatos} other {# archivos de metadatos}}'
  },
  menu: {
    mode: 'Modo de conexión',
    online: 'En línea',
    offline: 'Sin conexión',
    lead: 'Todas las funciones trabajan sin conexión. El modo en línea solo lee metadatos del catálogo (columnas, claves, índices, estimaciones de filas) de las tablas a las que hace referencia el script; Amber nunca ejecuta su SQL.',
    addFirst: 'primero agregue una conexión',
    lookUp: 'Buscar tablas en (en este orden)',
    refresh: 'Actualizar metadatos',
    manage: 'Administrar conexiones…',
    addDdl: 'Agregar archivos DDL…',
    addPacks: 'Agregar paquetes de metadatos…',
    details: 'Ver detalles de metadatos'
  },
  panel: {
    off: 'Desactivado. Actívelo desde el control Sin conexión de la barra de título.',
    reading: 'Leyendo metadatos',
    refresh: 'Actualizar',
    choose: 'Elija al menos una conexión en el menú de la barra de título.',
    loaded: '{count, plural, one {# tabla cargada} other {# tablas cargadas}} y usadas por todas las vistas.',
    ambiguous: 'Encontradas en varias conexiones',
    ambiguousHint: 'Amber usó la tabla que mejor encaja con el script: primero la misma familia de base de datos, luego la que tiene las columnas que usa el script y después el orden de su lista.',
    foundIn: 'en {sources}',
    used: '{name} (usada)',
    missing: 'No encontradas ({count})'
  },
  offline: {
    title: 'Metadatos sin conexión',
    addDdl: 'Agregar DDL',
    addPack: 'Agregar paquete',
    save: 'Guardar como paquete…',
    none: 'No hay archivos DDL ni paquetes de metadatos. Describen tablas sin conexión y se mantienen durante esta sesión.',
    ddl: 'DDL',
    pack: 'paquete',
    remove: 'Quitar {name}'
  },
  files: {
    ddl: 'Archivos SQL / DDL',
    packs: 'Paquetes de metadatos',
    pack: 'Paquete de metadatos',
    all: 'Todos los archivos',
    addDdlTitle: 'Agregar archivos DDL',
    addPacksTitle: 'Agregar paquetes de metadatos'
  },
  toast: {
    readFailed: 'No se pudieron leer los archivos de metadatos: {reason}',
    nothingToSave: 'Todavía no hay metadatos para guardar.',
    saved: 'Se guardaron {count, plural, one {# tabla} other {# tablas}} en {file}'
  }
}
