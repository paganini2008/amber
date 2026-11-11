import type { En } from '../en'

export const dialogs: En['dialogs'] = {
  about: {
    title: '关于 Amber',
    slogan: '维护那些没人愿意碰的遗留 SQL。',
    version: '版本 {version}',
    privacy: 'Amber 从不运行你的 SQL。除非你连接数据库（仅读取元数据）或 AI 服务商，否则任何内容都不会离开你的电脑。',
    versions: '版本信息',
    copyInfo: '复制版本信息',
    copiedInfo: '版本信息已复制。',
    copyFailed: '无法复制到剪贴板。',
    engine: '引擎',
    engineOnJava: '{version}，运行于 Java {java}',
    notRunning: '未运行',
    starting: '正在启动…',
    system: '系统',
    checkUpdates: '检查更新',
    checkFailed: '无法检查：{reason}',
    available: 'Amber {version} 已发布。',
    latest: '已是最新版本。',
    latestVersion: '已是最新版本（{version}）。',
    downloadPage: '打开下载页面',
    downloading: '正在下载 Amber {version}… {percent}%',
    ready: 'Amber {version} 已可安装。',
    restart: '重启以更新',
    downloadFailed: '无法下载更新：{reason}',
    links: '链接',
    project: 'GitHub 项目',
    website: '网站',
    releases: '发布版本',
    issue: '报告问题',
    author: '作者',
    license: '许可证',
    notices: '第三方声明',
    noticesLead: 'Amber 所含组件的许可证。',
    noticesFailed: '无法读取声明：{reason}'
  },
  paste: {
    title: '粘贴 SQL',
    lead: '粘贴脚本进行分析。不会执行任何语句。',
    name: '名称',
    text: 'SQL 文本'
  },
  handover: {
    title: '交接文档',
    lead: '脚本做了什么、涉及的对象、数据流、风险以及 Amber 无法读懂的部分：留给下一位维护者的说明。',
    writing: '正在生成交接文档',
    preview: '交接文档预览'
  }
}
