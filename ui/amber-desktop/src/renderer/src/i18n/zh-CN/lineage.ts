import type { En } from '../en'

export const lineage: En['lineage'] = {
  title: '列血缘',
  notAvailable: '请更新引擎，以查看每一列的值从何而来。',
  find: '查找列',
  columnsOf: '所属对象',
  showAllColumns: '显示所有列',
  columns: '列',
  noMatch: '没有匹配的列。',
  writtenByStep: '第 {step} 步写入的列',
  counts: '{columns, plural, other {# 列}}，涉及 {owners, plural, other {# 个对象}}',
  truncated: '仅显示路径上的前 {count} 列',
  direction: {
    label: '方向',
    upstream: {
      label: '来源',
      hint: '上游：沿着值追溯到源表'
    },
    downstream: {
      label: '影响',
      hint: '下游：改动它会影响什么？'
    },
    both: {
      label: '双向',
      hint: '上游和下游'
    }
  },
  indirect: {
    short: '过滤、连接、分组',
    label: '包括过滤、连接和分组',
    hint: 'WHERE、JOIN 和 GROUP BY 中使用的列决定了哪些行会到达'
  },
  fit: '显示完整路径',
  empty: {
    pick: '改动一列会影响什么？在左侧选择一列，查看它的值从何而来、又流向哪里。',
    step: '这一步没有移动任何列。',
    column: '这一列没有血缘信息。'
  },
  layingOut: '正在布局',
  clipped: '部分路径在视图之外。拖动以平移，或使用“适应”查看完整路径。',
  legend: {
    flows: '值的流向',
    decides: '决定行（过滤、连接、分组）',
    variable: '变量',
    parameter: '参数',
    clickEdge: '点击连线查看对应的 SQL'
  },
  mapping: {
    trace: '追踪 {column}（{kind}）',
    traceOutput: '追踪 {column}',
    showWhere: '显示发生位置',
    moreSources: '+{count, plural, other {另外 # 个来源}}',
    decidedBy: '决定哪些行到达的列',
    seen: '脚本中出现的列',
    open: '在血缘中打开'
  },
  card: {
    rows: '决定到达哪些行',
    value: '值',
    moreColumns: '+{count, plural, other {另外 # 列}}'
  },
  scope: {
    block: '代码块（第 {line} 行）',
    batch: '批次（第 {line} 行）'
  },
  owner: {
    variableIn: '{scope} 中的变量',
    parameterOf: '{scope} 的参数',
    unresolvedTitle: '未解析的表',
    unresolvedSubtitle: '未解析：无法确定所属的表',
    resultOfStep: '第 {step} 步的结果',
    queryResult: '查询结果'
  },
  ownerKind: {
    TABLE: '表',
    TEMP_TABLE: '临时表',
    TABLE_VARIABLE: '表变量',
    VIEW: '视图',
    MATERIALIZED_VIEW: '物化视图',
    CTE: 'CTE',
    SUBQUERY: '子查询',
    RESULT: '查询结果',
    VARIABLE: '变量',
    PARAMETER: '参数',
    UNKNOWN: '未解析'
  },
  transform: {
    PASS_THROUGH: '复制',
    EXPRESSION: '表达式',
    AGGREGATE: '聚合',
    WINDOW: '窗口',
    CASE: 'CASE',
    CAST: '类型转换',
    CONSTANT: '常量'
  },
  role: {
    FILTER: '过滤',
    JOIN: '连接',
    GROUP: '分组',
    INDIRECT: '间接'
  }
}
