import type { En } from '../en'

export const refactor: En['refactor'] = {
  button: '重构脚本',
  title: '重构脚本',
  lead: 'Amber 会多轮应用所有能证明对任何数据结果都不变的改写，并在“变更影响”中展示结果。在你应用之前，编辑器中的内容不会改变。',
  format: '按“格式化脚本”排版结果',
  run: '重构',
  running: '正在重构，第 {pass} 轮…',
  nothing: '没有可以在不改变结果的前提下重构的内容。',
  unavailable: '当前引擎不支持重构脚本。',
  afterName: '{name}（重构后）',
  applied: '已重构脚本',
  banner: {
    title: '重构后的脚本',
    rewrites: '经过 {passes} 轮，共 {count} 处改写，每处都保持结果不变。',
    noRewrites: '不需要改写。',
    formatted: '已按“格式化脚本”排版。',
    logicKept: '逻辑检查：条件、常量值、来源表、输出列和血缘都与原脚本一致。',
    logicChanged: '逻辑检查：Amber 发现逻辑有差异。请不要应用这次重构，并反馈这个问题。',
    discard: '放弃',
    apply: '应用到编辑器'
  },
  ai: '让 AI 重构',
  aiHint: 'AI 会为后续维护者重新组织这条语句的结构。只有逻辑检查完全没有发现差异时，Amber 才允许应用。',
  aiTitle: 'AI 重构'
}
