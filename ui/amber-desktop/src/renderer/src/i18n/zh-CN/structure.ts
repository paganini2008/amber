import type { En } from '../en'

export const structure: En['structure'] = {
  title: '查询结构',
  expandAll: '全部展开',
  collapseAll: '全部折叠',
  expand: '展开',
  collapse: '折叠',
  empty: {
    select: '选择一条语句，按读取顺序把它的嵌套查询一层层拆开。',
    engine: '此版本的引擎不提供查询结构。',
    none: '这条语句没有查询结构。'
  },
  counts: {
    none: '没有查询结构',
    blocks: '{count, plural, other {# 个块}}',
    levels: '{count, plural, other {# 层}}',
    CTE: '{count, plural, other {# 个 CTE}}',
    SET: '{count, plural, other {# 个集合运算}}',
    DERIVED: '{count, plural, other {# 个派生表}}',
    SUBQUERY: '{count, plural, other {# 个子查询}}',
    QUERY: '{count, plural, other {# 个查询}}'
  },
  steps: {
    ready: '就绪',
    list: '{count, plural, other {步骤}} {items}'
  },
  box: {
    noSources: '无来源',
    noStructureLower: '没有查询结构',
    rootTitle: '{title}（第 {line} 行）',
    collapsed: '{count, plural, other {已折叠 # 个嵌套块}}',
    hiddenNested: '+{count} 个嵌套',
    fewer: '收起'
  },
  source: {
    title: '{kind} {name}。{action}。',
    titleAlias: '{kind} {name}，别名 {alias}。{action}。',
    goCte: '转到该 CTE',
    goDerived: '转到该派生表',
    select: '选择此对象'
  },
  order: {
    title: '逻辑执行顺序',
    note: '这是 SQL 标准规定的查询读取顺序。不会执行任何语句，数据库的实际执行计划可能不同。',
    show: '显示查询被读取的顺序',
    none: '这条语句没有逻辑执行顺序',
    boxSteps: '此块在逻辑执行顺序中的步骤',
    stepPhase: '第 {step} 步：{phase}',
    previous: '上一步（左方向键）',
    next: '下一步（右方向键）',
    play: '播放逻辑执行顺序',
    pause: '暂停',
    reset: '回到整个查询',
    count: '{count, plural, other {# 步}}',
    stepOf: '第 {step} / {total} 步',
    write: '写入',
    cteReady: 'CTE 就绪',
    inBlock: '位于 {block}',
    theStatement: '整条语句',
    allDone: '此块的所有步骤均已完成',
    done: '完成'
  },
  summary: {
    open: '在结构视图中打开',
    more: '… 结构视图中还有 {count} 个',
    stepThrough: '在结构视图中逐步查看',
    outline: '逻辑执行顺序概要',
    nested: '（{count} 个嵌套）',
    nestedHint: '{count, plural, other {这里会执行嵌套查询的 # 个步骤}}'
  }
}
