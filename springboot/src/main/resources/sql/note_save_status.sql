-- B28：网课 AI 笔记默认不进入笔记管理——新增保存状态列
-- 语义：save_status 0 = AI 生成未保存（笔记管理树不展示）；1 = 已保存进笔记管理（树中可见）。
-- 手动笔记 / 对话转写笔记（source_type = 0/2）创建即视为已保存，走列默认值 1，业务代码无需关心。
-- 网课流水线生成 AI 笔记（NoteGenerationService.saveNote）插入时显式置 0；
-- 用户在 AI 对话中明确要求保存时由 save_course_note 工具把状态置 1。
-- 重生成课程内容时新行继承旧行的 save_status（避免用户已保存的树节点被悄悄抹掉）。

ALTER TABLE note
    ADD COLUMN save_status tinyint NOT NULL DEFAULT 1 COMMENT '保存状态：0=AI生成未保存（笔记管理不展示） 1=已保存进笔记管理' AFTER source_type;

-- 存量迁移：已出现在笔记管理树中的网课 AI 笔记批量摘出（含历史 video 上传产生的 AI 笔记）
UPDATE note SET save_status = 0 WHERE source_type = 1 AND deleted = 0;
