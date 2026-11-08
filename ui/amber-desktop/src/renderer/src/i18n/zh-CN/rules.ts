import type { En } from '../en'

export const rules: En['rules'] = {
  tab: '规则',
  lead: '选择运行哪些检查，以及其发现项的严重程度。更改会立即作用于分析、快速修复、优化后的 SQL、交接文档、变更影响和 AI 上下文。',
  search: '搜索规则',
  category: {
    risk: '风险',
    performance: '性能',
    maintainability: '可维护性',
    info: '信息',
    other: '其他'
  },
  enable: '开启或关闭 {id}',
  severity: '{id} 的严重程度',
  severityDefault: '默认（{severity}）',
  needsMetadata: '需要表定义',
  needsMetadataTitle: '只有在已知表定义时才会报告：DDL 文件、元数据包或在线模式。',
  hasQuickFix: '有快速修复',
  hasQuickFixTitle: '可以提供经过校验的语句改写。',
  reset: '恢复默认',
  changed: '{count, plural, =0 {所有规则均为默认设置} other {已修改 # 条规则}}',
  off: '已关闭',
  loading: '正在加载规则…',
  unavailable: '此版本的引擎没有规则设置。',
  error: '无法加载规则：{message}',
  noMatch: '没有与“{query}”匹配的规则。'
}
