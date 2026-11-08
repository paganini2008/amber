/** Sample cards on the start page (card label and feature note; SQL keywords stay as written). */
export const samples = {
  oracle: { label: 'Library circulation', note: 'SQL*Plus, global temp table, MERGE, PL/SQL loop, CONNECT BY' },
  sqlserver: { label: 'Inventory reconciliation', note: 'Procedure, IF, WHILE, TRY' },
  postgresql: { label: 'Nightly rollup', note: 'Temp tables, CTEs, MERGE' },
  mysql: { label: 'Shop orders', note: 'Temp table, multi-table UPDATE, ON DUPLICATE KEY, procedure' },
  mariadb: { label: 'Helpdesk tickets', note: 'CREATE OR REPLACE, recursive CTE, correlated UPDATE' },
  db2: { label: 'Fleet maintenance', note: 'Global temporary table, MERGE, view, FETCH FIRST' },
  h2: { label: 'Game leaderboard', note: 'Local temp table, MERGE USING, window functions' },
  sqlite: { label: 'Fitness tracker', note: 'Temp table, INSERT OR REPLACE … WITH, UNION' }
}
