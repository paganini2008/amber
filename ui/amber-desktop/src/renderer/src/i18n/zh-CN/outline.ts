import type { En } from '../en'

export const outline: En['outline'] = {
  stats: {
    lines: '{count, plural, other {行}}',
    statements: '{count, plural, other {条语句}}',
    sources: '{count, plural, other {个源}}',
    intermediate: '{count, plural, other {个中间对象}}',
    targets: '{count, plural, other {个目标}}',
    ms: '{count, plural, other {毫秒分析用时}}'
  },
  listLabel: '按源码顺序排列的语句',
  expand: '展开',
  collapse: '折叠',
  findings: '{count, plural, other {# 个发现项}}',
  loop: '循环',
  via: {
    hint: '此语句调用 {routine} 时，它所做的操作',
    writes: '经由 {routine} 写入 {writes, plural, other {# 张表}}',
    reads: '经由 {routine} 读取 {reads, plural, other {# 张表}}',
    writesReads: '经由 {routine} 写入 {writes} 张、读取 {reads, plural, other {# 张表}}'
  }
}
