import type { En } from '../en'

export const optimize: En['optimize'] = {
  badge: '基于规则 · 无 AI',
  badgeTitle: '由 Amber 的规则根据分析结果计算，并经过解析器校验。不涉及 AI，也不执行任何语句。',
  card: {
    title: '优化后的 SQL',
    statementLine: '第 {number} 条语句 · 第 {line} 行',
    whatChanged: '改动内容',
    why: '原因',
    notApplied: '未应用',
    check: '解析器校验：',
    differences: '结构差异',
    compare: '对比',
    compareTitle: '并排显示语句和优化后的版本',
    apply: '应用到编辑器',
    applyTitle: '在编辑器中替换该语句，作为一个可撤销的步骤。不会保存文件。',
    stale: '脚本在计算之后已被修改。请重新分析以刷新。',
    note: '注意'
  },
  ddl: {
    title: '建议的 DDL（不会应用到脚本）',
    hint: '基于脚本用法和表定义的提示。创建任何对象之前，请核实数据量、已有索引和写入负载。',
    copy: '复制 DDL'
  },
  skip: {
    CONFLICT: '与已应用的改动重叠',
    EDGE_CASE: '在边界情况下会改变行为',
    DISABLED: '该规则已在“设置 → 规则”中关闭',
    UNVERIFIED: '合并后的语句未通过解析器校验'
  },
  verified: {
    STRUCTURE_PRESERVED: '结构相同',
    STRUCTURE_CHANGED: '结构已改变',
    NOT_CHECKED: '仅解析',
    INVALID_SQL: '无效的 SQL'
  },
  panel: {
    title: '优化后的语句',
    summary: '{count, plural, other {# 条语句有优化版本}}',
    applySafe: '应用所有安全的优化（{count}）',
    applySafeTitle: '只应用那些对任何数据都返回相同行（“结果相同”）的语句改动，作为一个可撤销的步骤。',
    loading: '正在计算优化后的语句…',
    none: '此脚本没有可优化的语句。',
    unavailable: '此版本的引擎不支持语句优化。',
    quickFixes: '快速修复（逐条应用）',
    ddlOnly: '仅有建议的 DDL',
    open: '打开'
  },
  finding: {
    show: '查看优化后的 SQL',
    showDdl: '查看建议的 DDL',
    copyOnly: '这段 SQL 替换的不止这一条语句。请复制后手动修改脚本。'
  },
  dialog: {
    title: '优化后的 SQL',
    description: '基于规则，无 AI。不执行也不保存任何内容：“应用”只修改编辑器，作为一个可撤销的步骤。',
    now: '当前',
    after: '优化后'
  },
  toast: {
    applied: '{count, plural, other {已应用 # 条优化后的语句}}',
    noMatch: '脚本已与优化后的语句不一致。请重新分析。'
  },
  details: {
    title: '优化后的 SQL',
    rewrites: '{count, plural, other {# 处改动}}'
  }
}
