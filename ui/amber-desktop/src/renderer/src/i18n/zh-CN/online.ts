import type { En } from '../en'

export const online: En['online'] = {
  status: {
    online: '在线',
    reading: '在线 · 正在读取元数据',
    failed: '在线 · 元数据读取失败',
    tables: '在线 · {count, plural, other {# 张表}}',
    tablesCheck: '在线 · {count, plural, other {# 张表}} · 请检查',
    files: '{count, plural, other {# 个元数据文件}}'
  },
  menu: {
    mode: '连接模式',
    online: '在线',
    offline: '离线',
    lead: '所有功能都可以离线使用。在线模式只读取脚本所引用表的目录元数据（列、键、索引、行数估计）；Amber 从不运行你的 SQL。',
    addFirst: '请先添加连接',
    lookUp: '在以下连接中查找表（按此顺序）',
    refresh: '刷新元数据',
    manage: '管理连接…',
    addDdl: '添加 DDL 文件…',
    addPacks: '添加元数据包…',
    details: '查看元数据详情'
  },
  panel: {
    off: '已关闭。可在标题栏的“离线”控件中开启。',
    reading: '正在读取元数据',
    refresh: '刷新',
    choose: '请在标题栏菜单中至少选择一个连接。',
    loaded: '已加载 {count, plural, other {# 张表}}，所有视图都会使用。',
    ambiguous: '在多个连接中找到',
    ambiguousHint: 'Amber 选用了最符合脚本的表：先看数据库类型是否相同，再看是否包含脚本用到的列，最后按你的列表顺序。',
    foundIn: '位于 {sources}',
    used: '{name}（已使用）',
    missing: '未找到（{count}）'
  },
  offline: {
    title: '离线元数据',
    addDdl: '添加 DDL',
    addPack: '添加元数据包',
    save: '另存为元数据包…',
    none: '没有 DDL 文件或元数据包。它们无需连接即可描述表，在本次会话中保留。',
    ddl: 'DDL',
    pack: '元数据包',
    remove: '移除 {name}'
  },
  files: {
    ddl: 'SQL / DDL 文件',
    packs: '元数据包',
    pack: '元数据包',
    all: '所有文件',
    addDdlTitle: '添加 DDL 文件',
    addPacksTitle: '添加元数据包'
  },
  toast: {
    readFailed: '无法读取元数据文件：{reason}',
    nothingToSave: '还没有可保存的元数据。',
    saved: '已将 {count, plural, other {# 张表}}保存到 {file}'
  }
}
