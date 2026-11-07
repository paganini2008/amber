import db2Fleet from './db2-fleet.sql?raw'
import h2Leaderboard from './h2-leaderboard.sql?raw'
import mariadbHelpdesk from './mariadb-helpdesk.sql?raw'
import mysqlShop from './mysql-shop.sql?raw'
import oracleLibrary from './oracle-library.sql?raw'
import postgresqlNightly from './postgresql-nightly.sql?raw'
import sqliteFitness from './sqlite-fitness.sql?raw'
import sqlserverInventory from './sqlserver-inventory.sql?raw'

export interface Sample {
  /** File name shown for the opened document. */
  name: string
  dialect: string
  /** Database name, shown first. */
  database: string
  label: string
  note: string
  text: string
}

/** One sample per supported database, in the order they are listed. */
export const SAMPLES: Sample[] = [
  {
    name: 'library-oracle.sql',
    dialect: 'oracle',
    database: 'Oracle',
    label: 'Library circulation',
    note: 'SQL*Plus, global temp table, MERGE, PL/SQL loop, CONNECT BY',
    text: oracleLibrary
  },
  {
    name: 'warehouse-inventory.sql',
    dialect: 'sqlserver',
    database: 'SQL Server',
    label: 'Inventory reconciliation',
    note: 'Procedure, IF, WHILE, TRY',
    text: sqlserverInventory
  },
  {
    name: 'bookshop-nightly.sql',
    dialect: 'postgresql',
    database: 'PostgreSQL',
    label: 'Nightly rollup',
    note: 'Temp tables, CTEs, MERGE',
    text: postgresqlNightly
  },
  {
    name: 'shop-mysql.sql',
    dialect: 'mysql',
    database: 'MySQL',
    label: 'Shop orders',
    note: 'Temp table, multi-table UPDATE, ON DUPLICATE KEY, procedure',
    text: mysqlShop
  },
  {
    name: 'helpdesk-mariadb.sql',
    dialect: 'mariadb',
    database: 'MariaDB',
    label: 'Helpdesk tickets',
    note: 'CREATE OR REPLACE, recursive CTE, correlated UPDATE',
    text: mariadbHelpdesk
  },
  {
    name: 'fleet-db2.sql',
    dialect: 'db2',
    database: 'Db2',
    label: 'Fleet maintenance',
    note: 'Global temporary table, MERGE, view, FETCH FIRST',
    text: db2Fleet
  },
  {
    name: 'leaderboard-h2.sql',
    dialect: 'h2',
    database: 'H2',
    label: 'Game leaderboard',
    note: 'Local temp table, MERGE USING, window functions',
    text: h2Leaderboard
  },
  {
    name: 'fitness-sqlite.sql',
    dialect: 'sqlite',
    database: 'SQLite',
    label: 'Fitness tracker',
    note: 'Temp table, INSERT OR REPLACE … WITH, UNION',
    text: sqliteFitness
  }
]
