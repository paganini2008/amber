import type { En } from '../en'

export const references: En['references'] = {
  goToDefinition: '转到 {name} 的定义',
  findReferences: '查找 {name} 的引用',
  findButton: '查找 {name} 的引用（{shortcut}）',
  rowTitle: '{access} · 第 {line} 行',
  rowTitleVia: '{access}（经由 {via}）· 第 {line} 行',
  definition: '定义',
  via: '经由',
  viaTitle: '这次调用经由 {via} 访问到它',
  column: '列',
  header: '引用：',
  count: '{places, plural, other {# 处}}{statements, plural, =0 {} other {，分布在 # 条语句中}}',
  shortcuts: 'F12 转到定义 · ⇧F12 查找引用',
  close: '关闭引用',
  looking: '正在查找 {name}',
  idle: '修改表或列之前，先看看它在哪些地方被使用：把光标放在名称上，按 F12 或 Shift+F12，或右键单击。',
  nothing: '本脚本中没有找到名为 {name} 的对象。',
  listAria: '{name} 的引用',
  external: '定义在本脚本之外：这里没有它的 CREATE 或 WITH。',
  statement: '第 {number} 条语句',
  routineTitle: '转到 {name} 的定义（F12）'
}
