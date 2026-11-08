import type { En } from '../en'

export const lifecycle: En['lifecycle'] = {
  filter: '筛选对象',
  groupBy: '分组方式',
  groupByLabel: '对象的分组或排序方式',
  sort: {
    role: '角色',
    kind: '类型',
    firstUse: '首次使用（不分组）',
    name: '名称（不分组）'
  },
  group: {
    SOURCE: '源',
    INTERMEDIATE: '中间',
    TARGET: '目标',
    STRUCTURE: '仅结构'
  },
  allSteps: '所有步骤',
  allStepsLabel: '显示所有步骤',
  empty: {
    filtered: '没有对象符合筛选条件。',
    none: '此脚本没有涉及任何表或视图。'
  },
  matrixLabel: '对象生命周期矩阵',
  createdHere: '在此创建',
  droppedHere: '在此删除',
  columnLineage: '列血缘',
  columnLineageOf: '{name} 的列血缘',
  cellTitle: '{name} · 第 {step} 步',
  shortKind: {
    TEMP_TABLE: '临时',
    TABLE_VARIABLE: '变量',
    MATERIALIZED_VIEW: '物化'
  },
  legend: '图例',
  via: '经由调用',
  viaHint: '该对象是通过调用脚本中定义的例程而访问到的',
  event: {
    direct: '{access}，第 {line} 行',
    via: '{access}，经由 {via}，第 {line} 行'
  }
}
