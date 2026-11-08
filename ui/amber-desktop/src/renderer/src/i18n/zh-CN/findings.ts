import type { En } from '../en'

export const findings: En['findings'] = {
  rules: {
    'AMB-S001': {
      title: '笛卡尔积',
      suggestion: '添加连接条件；如果确实需要笛卡尔积，请写成 CROSS JOIN。',
      description: 'FROM 中列出了多张表，却没有任何连接条件或 WHERE 条件：每一行都会与每一行组合。'
    },
    'AMB-S002': {
      title: 'LEFT JOIN 实际变成了 INNER JOIN',
      suggestion: '把条件移到 ON 子句中；如果本意就是内连接，请使用 INNER JOIN。',
      description: '对 LEFT JOIN 右表的 WHERE 条件会丢弃外连接保留下来的行。'
    },
    'AMB-S003': {
      title: '前导通配符',
      suggestion: '让匹配模式从开头固定，或改用全文检索。',
      description: 'LIKE \'%x\' 无法使用该列上的索引。'
    },
    'AMB-S004': {
      title: 'NOT IN 搭配子查询',
      suggestion: '改用 NOT EXISTS，或在子查询中过滤掉 NULL。',
      description: '子查询只要返回一个 NULL，NOT IN 就不匹配任何行（当元数据表明该列可为空时可以确定）。'
    },
    'AMB-S005': {
      title: '同一张表被多次读取',
      suggestion: '在 CTE 中读取一次（或使用条件聚合），然后复用结果。',
      description: '同一张表在一条语句中被多次读取。'
    },
    'AMB-S006': {
      title: '循环中逐行执行 DML',
      suggestion: '用一条基于集合的语句替换循环（INSERT … SELECT、UPDATE … FROM、MERGE），或改为批量处理（FORALL / 批量绑定）。',
      description: 'INSERT、UPDATE、DELETE 或 MERGE 在每次循环迭代中都执行一次。'
    },
    'AMB-S007': {
      title: '循环内 COMMIT',
      suggestion: '在循环结束后提交一次，或按更大的批次提交。',
      description: '每次迭代都提交既慢，又会在循环失败时留下部分结果。'
    },
    'AMB-S008': {
      title: '对过滤列使用函数',
      suggestion: '用原始列与转换后的值比较（例如用日期范围代替 TRUNC(col)）。',
      description: 'WHERE 中对列使用函数或计算，会使该列上的索引无法使用（元数据显示存在索引时为高）。'
    },
    'AMB-S009': {
      title: 'UNION 会去重',
      suggestion: '如果各分支不会重叠或允许重复，请使用 UNION ALL。',
      description: 'UNION 会对结果排序或哈希去重；UNION ALL 可能就足够了。'
    },
    'AMB-S010': {
      title: '将 SELECT * 写入表或视图',
      suggestion: '显式列出各列。',
      description: '源表一变化，列清单就随之变化。'
    },
    'AMB-S011': {
      title: 'DISTINCT 与 GROUP BY 同时使用',
      suggestion: '除非选择列表包含分组之外的列，否则请去掉 DISTINCT。',
      description: 'GROUP BY 已经为每个分组返回一行。'
    },
    'AMB-S012': {
      title: '子查询中的 ORDER BY',
      suggestion: '去掉 ORDER BY，或把它移到最外层查询。',
      description: '没有行数限制的子查询或 CTE 的排序不会被外层查询保留。'
    },
    'AMB-M001': {
      title: '多对多连接',
      suggestion: '在唯一键上连接，或先把一侧按连接键聚合为一行。请检查合计值是否被放大。',
      description: '连接列在两侧都不唯一，因此两侧的行都会重复。'
    },
    'AMB-D001': {
      title: '重复的语句',
      suggestion: '删除重复部分，或把它提取为存储过程。',
      description: '同一条语句再次出现（忽略字面量）。'
    },
    'AMB-D002': {
      title: '重复的子查询',
      suggestion: '在 CTE 或临时表中计算一次，然后复用。',
      description: '同一个子查询在脚本中出现了多次。'
    },
    'AMB-I001': {
      title: '动态 SQL',
      suggestion: '尽可能使用静态 SQL，以便检查语句。',
      description: '运行时拼接的 SQL 不会被分析。'
    },
    'AMB-I002': {
      title: '无法归属到表的列',
      suggestion: '用表别名限定这些列，或添加元数据（导入 DDL、元数据包或在线模式）。',
      description: '无法归属到某张表的列。'
    },
    'AMB-I003': {
      title: '检查提前停止',
      suggestion: '把脚本拆成更小的文件，或调高 AMBER_FINDINGS_SECONDS。',
      description: '检查用完了时间预算（AMBER_FINDINGS_SECONDS，默认 60 秒）；之后的语句没有被检查。'
    },
    'AMB-P001': {
      title: '可能缺少索引',
      suggestion: '可以考虑以该列开头的索引。此提示基于脚本中的用法和表定义；添加之前请在数据库中核实真实数据量和已有索引。',
      description: '在已知的表定义中，用于连接或过滤的列没有以它开头的索引（在 3 条及以上语句中使用时提升为低）。这只是提示：请核实真实数据量和已有索引。'
    },
    'AMB-P002': {
      title: '隐式类型转换',
      suggestion: '与列类型一致的值比较（数字不加引号，字符串加引号，日期使用带明确格式的字面量），或者转换值，而不要转换列。',
      description: '已知类型的列与另一种类型的值比较，数据库会转换其中一侧：转换列（无法使用索引），或转换值（格式依赖会话设置，或在运行时出错）。'
    },
    'AMB-P003': {
      title: '选择列表中的相关子查询',
      suggestion: '一次性为所有行计算：连接一个分组派生表，或使用 APPLY / LATERAL。对于简单的聚合形式，Amber 提供经过校验的改写。',
      description: '引用外层行的标量子查询会对每一行执行一次；与分组派生表连接则只执行一次。'
    },
    'AMB-P004': {
      title: '可以合并的语句',
      suggestion: '把它们合并为一条语句（一条 UPDATE，或 MERGE 之类的 upsert），只读写这些行一次。请检查按语句计数的触发器。',
      description: '连续更新相同行的多条 UPDATE，或同一张表上的 UPDATE 加 INSERT … WHERE NOT EXISTS（即 upsert），可以合并为一条语句。'
    },
    'AMB-P005': {
      title: '可以改为一条语句的游标循环',
      suggestion: '用一条基于集合的语句替换循环（UPDATE … FROM / MERGE、INSERT … SELECT、DELETE … WHERE EXISTS）。请先确认游标不会两次返回同一目标行，并且不需要逐行的错误处理或提交。',
      description: '循环体只有一条仅使用游标行的 DML 的游标循环，可以写成一条基于集合的语句。'
    },
    'AMB-P006': {
      title: '跨索引列的 OR',
      suggestion: '把查询拆成多个 UNION ALL 分支，每个带索引的条件一个（排除前面分支已有的行），或确认执行计划会合并这些索引。',
      description: '对同一张表不同索引列的条件使用 OR，可能导致任何单个索引都无法使用。'
    },
    'AMB-P007': {
      title: '对比较列进行计算',
      suggestion: '把计算移到比较的另一侧，让列保持原样（例如 col + 1 = 5 改为 col = 5 - 1）。',
      description: '在 WHERE / ON 中对列做算术或拼接，或对连接列使用 NVL / COALESCE / ISNULL，会使该列上的索引无法使用。'
    },
    'AMB-P008': {
      title: '用 COUNT 判断是否存在',
      suggestion: '使用 EXISTS / NOT EXISTS (SELECT 1 FROM … WHERE …)，找到第一条匹配行就会停止。',
      description: 'COUNT(*) > 0 会统计每一条匹配行；EXISTS 找到第一条就停止。'
    },
    'AMB-P009': {
      title: 'DISTINCT 掩盖了产生重复的连接',
      suggestion: '用 EXISTS (SELECT 1 FROM … WHERE …) 判断其他表，而不是连接它们，并去掉 DISTINCT：这样不会产生重复行，也不需要排序或哈希。',
      description: '所选列已经包含某张表的唯一键；DISTINCT 只是在去除连接产生的重复行。'
    }
  }
}
