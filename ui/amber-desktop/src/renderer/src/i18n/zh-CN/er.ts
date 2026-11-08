import type { En } from '../en'

export const er: En['er'] = {
  title: 'ER 图',
  graphLabel: '实体关系图',
  loading: '正在读取表和连接…',
  layingOut: '正在排列图形…',
  unavailable: '此版本的引擎无法生成 ER 图。',
  error: '无法生成 ER 图：{message}',
  analyzeFirst: '请先分析脚本；ER 图基于分析结果生成。',
  empty: {
    noTables: '此脚本没有读写任何表。',
    noRelationships: '没有找到关系。外键、连接和同名列都会产生关系。',
    filtered: '没有符合筛选条件的关系。'
  },
  search: '查找表',
  searchMatches: '{count, plural, =0 {无匹配} other {# 个匹配}}',
  fit: '适应视图',
  copyMermaid: '复制 Mermaid',
  mermaidCopied: '已复制 Mermaid erDiagram。',
  dataFlow: '数据流',
  dataFlowHint: '同时绘制数据在表之间的流动（INSERT … SELECT），而不只是键和连接。',
  minConfidence: '显示',
  confidenceAtLeast: {
    POSSIBLE: '全部关系',
    INFERRED: '推断和确定的关系',
    CERTAIN: '仅确定的关系'
  },
  evidenceLabel: '依据',
  evidence: {
    DECLARED_FK: '声明的外键',
    METADATA_FK: '数据库外键',
    JOIN: '连接',
    NAME_MATCH: '同名列',
    DATA_FLOW: '数据流'
  },
  evidenceLong: {
    DECLARED_FK: '脚本中声明的外键',
    METADATA_FK: '来自数据库（元数据）的外键',
    JOIN: '脚本中进行了连接',
    NAME_MATCH: '仅由列名推测',
    DATA_FLOW: '数据从一张表流入另一张表'
  },
  confidence: {
    CERTAIN: '确定',
    INFERRED: '推断',
    POSSIBLE: '可能'
  },
  cardinality: {
    MANY_TO_ONE: '多对一',
    ONE_TO_ONE: '一对一',
    ONE_TO_MANY: '一对多',
    MANY_TO_MANY: '多对多',
    UNKNOWN: '基数未知'
  },
  around: {
    label: '只看此表周围',
    hops: '{count, plural, other {# 步}}',
    showing: '{name} 周围',
    clear: '显示所有表'
  },
  card: {
    pk: '主键',
    fk: '外键',
    joined: '用于连接',
    inScript: '在此脚本中创建',
    more: '{count, plural, other {还有 # 列}}',
    less: '只显示键列',
    noColumns: '不知道有哪些列',
    source: {
      DDL: '来自脚本中的 DDL',
      METADATA: '来自数据库元数据',
      USAGE: '来自脚本中的用法'
    }
  },
  isolated: {
    title: '{count, plural, other {# 张没有关系的表}}',
    show: '显示',
    hide: '隐藏'
  },
  legend: {
    certain: '确定（外键）',
    inferred: '由连接推断',
    possible: '可能（仅凭列名）',
    many: '多',
    one: '一',
    flow: '数据流',
    hint: '点击连线查看其背后的连接'
  },
  panel: {
    relationship: '关系',
    entity: '表',
    close: '关闭',
    evidence: '依据',
    confidence: '置信度',
    cardinality: '基数',
    constraint: '约束',
    note: '引擎说明',
    occurrences: '{count, plural, =0 {脚本中没有连接} other {出现在 # 处}}',
    occurrencesShown: '显示前 {shown} 处，共 {count} 处。',
    occurrenceLine: '第 {line} 行',
    relationships: '{count, plural, =0 {没有关系} other {# 个关系}}',
    columns: '{count, plural, other {# 列}}',
    kind: '类型',
    focus: '只看此表周围',
    jump: '在编辑器中显示'
  },
  stats: '{entities, plural, other {# 张表}} · {relationships, plural, other {# 个关系}} · {joins, plural, other {读取了 # 个连接条件}} · {unresolved} 个未解析',
  upgraded: '{count, plural, other {数据库元数据确认了 # 个关系。}}',
  upgradedDismiss: '关闭'
}
