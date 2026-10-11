-- 课程级笔记风格与学习者画像（意图三问卡采集，注入笔记渲染提示词）
-- 配套代码：Course 实体 noteStyle/learnerGoal/learnerLevel、IntentAgentService 四问链路、
--           NoteGenerationService.renderNotePaged 风格变体（FOLLOW=跟学型 / EXAM_SPRINT=考点速查型）
ALTER TABLE course
    ADD COLUMN note_style VARCHAR(20) NULL COMMENT '笔记风格：FOLLOW=跟学型 / EXAM_SPRINT=考点速查型（NULL=默认跟学型）' AFTER expectations,
    ADD COLUMN learner_goal VARCHAR(255) NULL COMMENT '学习目的（意图三问卡采集）' AFTER note_style,
    ADD COLUMN learner_level VARCHAR(50) NULL COMMENT '知识水平（意图三问卡采集）' AFTER learner_goal;
