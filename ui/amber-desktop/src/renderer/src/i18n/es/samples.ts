import type { En } from '../en'

export const samples: En['samples'] = {
  oracle: { label: 'Préstamos de biblioteca', note: 'SQL*Plus, tabla temporal global, MERGE, bucle PL/SQL, CONNECT BY' },
  sqlserver: { label: 'Conciliación de inventario', note: 'Procedimiento, IF, WHILE, TRY' },
  postgresql: { label: 'Resumen nocturno', note: 'Tablas temporales, CTE, MERGE' },
  mysql: { label: 'Pedidos de tienda', note: 'Tabla temporal, UPDATE multitabla, ON DUPLICATE KEY, procedimiento' },
  mariadb: { label: 'Tickets de soporte', note: 'CREATE OR REPLACE, CTE recursiva, UPDATE correlacionado' },
  db2: { label: 'Mantenimiento de flota', note: 'Tabla temporal global, MERGE, vista, FETCH FIRST' },
  h2: { label: 'Clasificación de juego', note: 'Tabla temporal local, MERGE USING, funciones de ventana' },
  sqlite: { label: 'Registro de ejercicio', note: 'Tabla temporal, INSERT OR REPLACE … WITH, UNION' }
}
