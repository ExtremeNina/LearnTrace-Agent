-- 笔记分层与知识联系时间戳（PRD §3.4）
-- 1) note 表增加节点类型：0=笔记（默认）、1=分组（OneNote 式分层，parent_id 指向父分组，最多 5 层）
ALTER TABLE note
    ADD COLUMN node_type TINYINT NOT NULL DEFAULT 0 COMMENT '节点类型：0笔记 1分组' AFTER source_type;
-- 2) note_link 增加跳转时间（秒，仅网课类型的知识联系使用）
ALTER TABLE note_link
    ADD COLUMN ts_sec INT NULL COMMENT '跳转时间（秒，仅网课知识联系）' AFTER title;
