import type { En } from '../en'

export const fixes: En['fixes'] = {
  safety: {
    EQUIVALENT: {
      label: '结果相同',
      detail: '对任何数据都返回相同的行。'
    },
    EQUIVALENT_WITH_METADATA: {
      label: '按表定义结果相同',
      detail: '只要列类型、可空性和键与描述一致，返回的行就相同。'
    },
    CHANGES_BEHAVIOR_IN_EDGE_CASES: {
      label: '边界情况下行为会改变',
      detail: '应用前请先阅读说明。'
    },
    notRated: '未评级'
  },
  quickFix: '快速修复：{title}',
  count: '{count, plural, other {# 个快速修复}}',
  fixItem: '修复：{title}',
  fixButton: '修复…',
  loading: '正在查找修复',
  unavailable: '当前引擎版本尚不提供修复建议。',
  stale: '脚本在上次分析后已被修改。请重新分析，查看仍然适用的修复。',
  none: '没有快速修复。Amber 为一些遗留写法提供基于规则的修复，例如 NOT IN 子查询，以及实际变成内连接的 LEFT JOIN。',
  summary: '{count, plural, other {# 个建议修复}}，用于清理遗留写法',
  summaryHint: '每个修复都经过解析器检查。应用只会修改编辑器，绝不修改磁盘上的文件。',
  applyAllTitle: '应用所有评级为“结果相同”且互不重叠的修复',
  applyAll: '应用全部“结果相同”的修复（{count}）',
  statement: '第 {number} 条语句',
  applyFirst: '请先应用 {id}，再重新检查',
  preview: '预览',
  dialog: {
    title: '快速修复',
    description: '{rule} · 清理这处遗留写法。应用只会修改编辑器；满意后请自行保存文件。',
    blocked: '请先应用 {id}，再重新检查：这两个修复涉及同一段文本。',
    apply: '应用到编辑器',
    noLongerMatches: '此修复已与脚本不匹配。请重新分析以刷新建议。',
    edgeCase: '边界情况：',
    parserCheck: '解析器检查：',
    now: '当前',
    after: '修复后'
  }
}
