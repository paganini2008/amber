import type { En } from '../en'

export const dataflow: En['dataflow'] = {
  summary: '{objects, plural, other {# 个对象}} · {statements, plural, other {# 条语句}}移动数据',
  grouped: {
    label: '{count, plural, other {# 个分组}}（超过 300 个节点）：双击分组即可展开',
    hint: '图中节点超过 300 个，因此合并为 {count, plural, other {# 个分组}}。双击分组即可展开。'
  },
  group: {
    openHint: '双击展开此分组',
    steps: '第 {from}–{to} 步',
    internal: '{count, plural, other {# 张内部表}}'
  },
  role: {
    SOURCE: '源',
    INTERMEDIATE: '中间',
    TARGET: '目标',
    STRUCTURE: '仅结构'
  },
  edge: {
    via: '经由 {routine}'
  },
  regroup: '重新分组',
  focus: '聚焦所选内容',
  fit: '适应图形',
  graphLabel: '数据流图',
  empty: '此脚本中没有在表之间移动数据的语句。',
  layingOut: '正在布局 {count, plural, other {# 个节点}}',
  layoutFailed: '无法布局此图：{error}'
}
