import type { En } from '../en'

export const main: En['main'] = {
  files: {
    openTitle: '打开 SQL 脚本',
    sqlScripts: 'SQL 脚本',
    allFiles: '所有文件',
    chooseFiles: '选择文件',
    notAFile: '这不是一个文件。',
    tooLarge: '文件大于 {mb} MB。'
  },
  engine: {
    notRunning: '引擎未运行。',
    javaMissing: '未找到 Java（{command}）。请安装 Java 17 或更高版本，或设置 JAVA_HOME。',
    couldNotStart: '无法启动引擎：{reason}',
    stopped: '引擎已停止（{reason}）。请重试。',
    jarMissing: '在 {path} 未找到引擎 jar。请构建后端，或在 ui/amber-desktop/.env 中设置 AMBER_ENGINE_JAR（参见 .env.example）。',
    exitedAtStart: '引擎在启动时退出（{reason}）。{hint}',
    noAnswer: '引擎没有响应：{reason}',
    keepsStopping: '引擎反复停止（{reason}）。请从状态菜单重启。',
    timedOut: '引擎未能及时响应“{method}”。',
    cancelled: '请求已取消。',
    unknownMethod: '未知方法 {method}。'
  },
  db: {
    timeout: '数据库在 {seconds} 秒内没有响应。请检查主机、端口和网络后重试。',
    unknownConnection: '未知的连接。',
    noConnection: '在线模式未选择任何连接。'
  },
  drivers: {
    unknown: '未知的驱动。',
    badRequest: '无效的下载请求。',
    cancelled: '下载已取消。',
    timeout: '下载未能在规定时间内完成。',
    repository: '驱动仓库必须是 https:// 地址。请在“设置 → 连接”中修改。',
    http: '仓库返回 HTTP {status}。',
    size: '下载的文件大小与预期不符，已删除。',
    hash: '下载的文件与固定的 SHA-256 校验和不符，已删除。',
    io: '无法保存驱动：{reason}',
    offline: '无法访问仓库（{reason}）。'
  },
  ai: {
    cancelled: '已取消',
    httpOnly: '请使用 http 或 https 地址。',
    providerAnswered: '服务商返回 {status}',
    addKey: '请先添加 API 密钥。',
    noModelList: '服务商没有返回模型列表。请直接输入模型名称。',
    connectedPick: '已连接。{count, plural, other {有 # 个模型可用}}，请选择一个。',
    modelMissing: '已连接，但未安装模型“{model}”。',
    modelAvailable: '已连接。模型“{model}”可用。',
    modelNotListed: '已连接。模型“{model}”不在服务商的列表中，但可能仍然可用。',
    connected: '已连接。',
    pickModel: '请先在设置中选择模型。',
    emptyResponse: '服务商返回了空响应。',
    providerError: '服务商错误'
  },
  settings: {
    noSecureStorage: '此系统不支持安全存储，无法保存密钥。'
  },
  updates: {
    githubAnswered: 'GitHub 返回 {status}。'
  }
}
