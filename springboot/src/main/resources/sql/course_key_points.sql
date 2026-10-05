-- 本课重点缓存（B25 首页继续学习卡）：LLM 从转写提炼的 JSON 数组字符串，无 AI 笔记时兜底
ALTER TABLE course
    ADD COLUMN key_points text NULL COMMENT '本课重点（LLM 从转写提炼，JSON 数组字符串）' AFTER last_studied_at;
