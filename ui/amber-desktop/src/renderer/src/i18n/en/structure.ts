/** Structure view: a statement's nested query blocks, and the logical order they are read in. */
export const structure = {
  title: 'Query structure',
  expandAll: 'Expand all',
  collapseAll: 'Collapse all',
  expand: 'Expand',
  collapse: 'Collapse',
  empty: {
    select: 'Select a statement to take its nested queries apart, box by box, in the order they are read.',
    engine: 'This engine version does not report query structure.',
    none: 'This statement has no query structure.'
  },
  counts: {
    none: 'No query structure',
    blocks: '{count, plural, one {# block} other {# blocks}}',
    levels: '{count, plural, one {# level} other {# levels}}',
    CTE: '{count, plural, one {# CTE} other {# CTEs}}',
    SET: '{count, plural, one {# set operation} other {# set operations}}',
    DERIVED: '{count, plural, one {# derived table} other {# derived tables}}',
    SUBQUERY: '{count, plural, one {# subquery} other {# subqueries}}',
    QUERY: '{count, plural, one {# query} other {# queries}}'
  },
  steps: {
    ready: 'ready',
    list: '{count, plural, one {step} other {steps}} {items}'
  },
  box: {
    noSources: 'no sources',
    noStructureLower: 'no query structure',
    rootTitle: '{title} (line {line})',
    collapsed: '{count, plural, one {# nested block collapsed} other {# nested blocks collapsed}}',
    hiddenNested: '+{count} nested',
    fewer: 'fewer'
  },
  source: {
    title: '{kind} {name}. {action}.',
    titleAlias: '{kind} {name} as {alias}. {action}.',
    goCte: 'Go to the CTE',
    goDerived: 'Go to the derived table',
    select: 'Select this object'
  },
  order: {
    title: 'Logical order',
    note: 'The order the SQL standard defines for reading the query. Nothing is run, and the database’s actual plan may differ.',
    show: 'Show the order in which the query is read',
    none: 'No logical order for this statement',
    boxSteps: 'Steps of this box in the logical order',
    stepPhase: 'Step {step}: {phase}',
    previous: 'Previous step (Left arrow)',
    next: 'Next step (Right arrow)',
    play: 'Play the logical order',
    pause: 'Pause',
    reset: 'Back to the whole query',
    count: '{count, plural, one {# step} other {# steps}}',
    stepOf: 'Step {step} of {total}',
    write: 'Write',
    cteReady: 'CTE ready',
    inBlock: 'in {block}',
    theStatement: 'the statement',
    allDone: 'All steps of this box are done',
    done: 'done'
  },
  summary: {
    open: 'Open in Structure view',
    more: '… {count} more in the Structure view',
    stepThrough: 'Step through in Structure view',
    outline: 'Logical order, outline',
    nested: '({count} nested)',
    nestedHint: '{count, plural, one {# step of nested queries runs here} other {# steps of nested queries run here}}'
  }
}
