import clsx from 'clsx'
import db2 from '@/assets/db/db2.svg'
import generic from '@/assets/db/generic.svg'
import h2 from '@/assets/db/h2.svg'
import mariadb from '@/assets/db/mariadb.svg'
import mysql from '@/assets/db/mysql.svg'
import oracle from '@/assets/db/oracle.svg'
import postgresql from '@/assets/db/postgresql.svg'
import sqlite from '@/assets/db/sqlite.svg'
import sqlserver from '@/assets/db/sqlserver.svg'
import { dialectName } from '@/lib/kinds'

/**
 * The database's mark in front of its name (dialect pickers, sample cards, connections, …). Bundled
 * SVGs under assets/db/ (no network): simple-icons marks (CC0) where it has them, original Amber
 * marks for Oracle, SQL Server and Db2, all on the same rounded tile so mixed sources look alike.
 */
export const DB_ICONS: Record<string, string> = { oracle, sqlserver, postgresql, mysql, mariadb, db2, h2, sqlite }

export function dbIconUrl(dialect: string | null | undefined): string {
  return (dialect && DB_ICONS[dialect.toLowerCase()]) || generic
}

export function DbIcon({
  dialect,
  size = 16,
  labelled = false,
  className
}: {
  dialect: string | null | undefined
  size?: 14 | 16 | 20
  /** True when the icon stands alone: it then carries the database name as its title. */
  labelled?: boolean
  className?: string
}): React.JSX.Element {
  const name = dialect ? dialectName(dialect) : ''
  return (
    <img
      src={dbIconUrl(dialect)}
      width={size}
      height={size}
      draggable={false}
      className={clsx('db-icon shrink-0', className)}
      {...(labelled ? { alt: name, title: name, role: 'img' } : { alt: '', 'aria-hidden': true })}
      data-dialect={dialect && DB_ICONS[dialect.toLowerCase()] ? dialect.toLowerCase() : 'generic'}
    />
  )
}
