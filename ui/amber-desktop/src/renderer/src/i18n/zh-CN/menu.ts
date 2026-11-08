import type { En } from '../en'

export const menu: En['menu'] = {
  app: {
    about: '关于 Amber',
    settings: '设置…',
    services: '服务',
    hide: '隐藏 Amber',
    hideOthers: '隐藏其他',
    showAll: '全部显示',
    quit: '退出 Amber'
  },
  file: {
    title: '文件',
    open: '打开脚本…',
    paste: '粘贴 SQL…',
    reanalyze: '重新分析',
    compareSaved: '与已保存的文件比较',
    compareFile: '与另一个版本比较…',
    export: '导出',
    exportSvg: '当前视图导出为 SVG…',
    exportPng: '当前视图导出为 PNG…',
    exportMermaid: '当前视图导出为 Mermaid…',
    exportHandover: '交接文档…',
    exportJson: '分析结果导出为 JSON…',
    settings: '设置…',
    quit: '退出'
  },
  edit: {
    title: '编辑',
    undo: '撤销',
    redo: '重做',
    cut: '剪切',
    copy: '复制',
    paste: '粘贴',
    selectAll: '全选',
    formatScript: '格式化脚本',
    formatSelection: '格式化选中内容'
  },
  view: {
    title: '视图',
    reload: '重新加载',
    devTools: '切换开发者工具',
    resetZoom: '实际大小',
    zoomIn: '放大',
    zoomOut: '缩小',
    fullScreen: '切换全屏'
  },
  window: {
    title: '窗口',
    minimize: '最小化',
    zoom: '缩放',
    close: '关闭窗口',
    front: '全部置于顶层'
  },
  help: {
    title: '帮助',
    about: '关于 Amber',
    github: 'GitHub 上的 Amber'
  }
}
