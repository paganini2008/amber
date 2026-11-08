import type { En } from '../en'

export const statement: En['statement'] = {
  empty: '这条语句到底做了什么？请在大纲、图或编辑器中选中它。',
  stepOf: '第 {step} 步，共 {total} 步',
  previous: '上一条语句',
  next: '下一条语句',
  topLevel: '顶层',
  containing: '外层语句',
  category: '类别',
  confidence: '置信度',
  defines: '定义',
  objects: '对象',
  throughCall: '经由调用',
  via: '经由 {routine}',
  contains: '包含 {count, plural, other {# 条语句}}',
  object: {
    untracked: '脚本中未跟踪此对象。',
    find: '查找引用',
    findHint: '此脚本中使用它的所有位置（Shift+F12）',
    facts: '{origin, select, created {在此脚本中创建} other {在脚本运行前已存在}}{dropped, select, yes {，在结束前被删除} other {}}，{count, plural, other {共 # 次访问}}。',
    lifecycle: '生命周期'
  }
}
