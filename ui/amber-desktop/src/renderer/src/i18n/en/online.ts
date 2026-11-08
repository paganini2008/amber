/** Online mode and offline metadata (title-bar menu, status bar, Metadata panel). */
export const online = {
  status: {
    online: 'Online',
    reading: 'Online · reading metadata',
    failed: 'Online · metadata failed',
    tables: 'Online · {count, plural, one {# table} other {# tables}}',
    tablesCheck: 'Online · {count, plural, one {# table} other {# tables}} · check',
    files: '{count, plural, one {# metadata file} other {# metadata files}}'
  },
  menu: {
    mode: 'Connection mode',
    online: 'Online',
    offline: 'Offline',
    lead: 'Every feature works offline. Online mode only reads catalog metadata (columns, keys, indexes, row estimates) of the tables the script references; Amber never runs your SQL.',
    addFirst: 'add a connection first',
    lookUp: 'Look tables up in (in this order)',
    refresh: 'Refresh metadata',
    manage: 'Manage connections…',
    addDdl: 'Add DDL files…',
    addPacks: 'Add metadata packs…',
    details: 'Show metadata details'
  },
  panel: {
    off: 'Off. Turn it on from the Offline control in the title bar.',
    reading: 'Reading metadata',
    refresh: 'Refresh',
    choose: 'Choose at least one connection in the title-bar menu.',
    loaded: '{count, plural, one {# table loaded} other {# tables loaded}} and used by every view.',
    ambiguous: 'Found in several connections',
    ambiguousHint: 'Amber used the table that fits the script best: same database family first, then the one with the columns the script uses, then your list order.',
    foundIn: 'in {sources}',
    used: '{name} (used)',
    missing: 'Not found ({count})'
  },
  offline: {
    title: 'Offline metadata',
    addDdl: 'Add DDL',
    addPack: 'Add pack',
    save: 'Save as pack…',
    none: 'No DDL files or metadata packs. They describe tables without a connection and stay for this session.',
    ddl: 'DDL',
    pack: 'pack',
    remove: 'Remove {name}'
  },
  files: {
    ddl: 'SQL / DDL files',
    packs: 'Metadata packs',
    pack: 'Metadata pack',
    all: 'All files',
    addDdlTitle: 'Add DDL files',
    addPacksTitle: 'Add metadata packs'
  },
  toast: {
    readFailed: 'Could not read the metadata files: {reason}',
    nothingToSave: 'There is no metadata to save yet.',
    saved: 'Saved {count, plural, one {# table} other {# tables}} to {file}'
  }
}
