import type { En } from '../en'

export const playback: En['playback'] = {
  title: '执行回放',
  stepBack: '后退一步',
  stepForward: '前进一步',
  play: '播放',
  pause: '暂停',
  stop: '停止回放',
  position: '回放位置',
  stepOf: '第 {step} / {total} 步',
  speed: '回放速度',
  stepInside: '逐步深入',
  stepInsideHint: '在结构视图中逐步查看此语句的逻辑执行顺序',
  pending: '第 {line} 行的{kind}。Amber 没有运行时的值，请选择要回放的内容。',
  insideLoop: '位于循环内：仅显示一次有代表性的迭代。',
  oneIteration: '一次有代表性的迭代',
  decision: {
    all: '按顺序回放子语句',
    only: '只回放 {branch}',
    skip: '跳过'
  },
  decided: {
    skipped: '已跳过',
    all: '全部',
    ask: '询问'
  },
  intro: '按逻辑顺序逐条重放脚本，让你看到各张表如何被填充和清空。不会执行任何语句。大纲、编辑器、生命周期矩阵和数据流图会同步跟随。',
  askLabel: '在每个 IF 和 CASE 处询问',
  askLong: '在每个 IF 和 CASE 处暂停并询问',
  askShort: '在 IF 和 CASE 处询问',
  playFromStart: '从头播放',
  manual: '手动逐步执行',
  branch: '引擎无法知道会执行哪个分支。请选择回放如何处理其中的 {count, plural, other {# 条语句}}。',
  reads: '读取',
  changes: '修改',
  nothing: '无',
  runOrder: '执行顺序',
  loop: '循环'
}
