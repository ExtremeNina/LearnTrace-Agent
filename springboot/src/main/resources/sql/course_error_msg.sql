-- B28：网课流水线失败信息修复——error_msg 扩容
-- 背景：失败信息拼接为「前缀 + truncate(500)」，截断后再拼前缀必超 varchar(500)；
-- 且判题 Agent 循环边界失效时的超长 issue 文案（"重写后仍无合格题目"等）同样溢出，
-- 数据库截断异常反过来掩盖真实根因（用户只能看到 Data too long 而非首个错误）。
-- 配套代码：CoursePipelineService 截断收口至 updateStatus（上限 2000）+ markFailed 写库失败降级重试。

ALTER TABLE course MODIFY COLUMN error_msg varchar(2000) NULL COMMENT '失败原因（截断上限 2000 与代码 ERROR_MSG_MAX 对齐）';
