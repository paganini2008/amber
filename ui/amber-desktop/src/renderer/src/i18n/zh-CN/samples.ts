import type { En } from '../en'

export const samples: En['samples'] = {
  oracle: { label: '图书借阅', note: 'SQL*Plus、全局临时表、MERGE、PL/SQL 循环、CONNECT BY' },
  sqlserver: { label: '库存对账', note: '存储过程、IF、WHILE、TRY' },
  postgresql: { label: '夜间汇总', note: '临时表、CTE、MERGE' },
  mysql: { label: '商店订单', note: '临时表、多表 UPDATE、ON DUPLICATE KEY、存储过程' },
  mariadb: { label: '客服工单', note: 'CREATE OR REPLACE、递归 CTE、相关 UPDATE' },
  db2: { label: '车队维护', note: '全局临时表、MERGE、视图、FETCH FIRST' },
  h2: { label: '游戏排行榜', note: '本地临时表、MERGE USING、窗口函数' },
  sqlite: { label: '健身记录', note: '临时表、INSERT OR REPLACE … WITH、UNION' }
}
