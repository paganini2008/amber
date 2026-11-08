import type { En } from '../en'

export const details: En['details'] = {
  title: '详情',
  emptyNoScript: '这里显示所选语句或对象的详情。',
  emptyHint: '修改之前，先在这里选中它：选中语句或表，就能看到它的数据从哪里来、又有什么依赖它。',
  source: '源代码',
  moreLines: '{count, plural, other {… 还有 # 行}}',
  finding: {
    aria: '发现项',
    possible: '可能',
    possibleHint: '没有元数据可以证实这一点',
    position: '第 {line} 行，第 {column} 列',
    suggestion: '建议：',
    ignoreBefore: '如需忽略，请添加',
    ignoreAfter: '放在语句中，或语句的上一行。',
    copyIgnore: '复制忽略注释',
    copiedIgnore: '已复制忽略注释',
    findings: '发现项'
  }
}
