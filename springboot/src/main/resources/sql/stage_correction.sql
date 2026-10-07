-- B26 阶段 1：流水线细粒度阶段（stage）与转写修正管线
-- 1) course.stage：PROCESSING 期间的细分阶段（UPLOADING/EXTRACTING/TRANSCRIBING/ANALYZING/NOTE_GENERATING）， coarse status 不变以兼容列表筛选
ALTER TABLE course
    ADD COLUMN stage VARCHAR(32) NULL COMMENT '处理阶段细分（PROCESSING 期间：UPLOADING/EXTRACTING/TRANSCRIBING/ANALYZING/NOTE_GENERATING）';

-- 2) 转写修正管线：Raw 不可变，修正以新列叠加（text 保留原始结果）
--    text_corrected：自动应用（LLM 候选 + 佐证命中）后的修正文本；NULL = 无修正
--    correction_meta：修正元数据 JSON（original/suggestion/evidence/status: APPLIED|SUGGESTED/source）
ALTER TABLE course_transcript_segment
    ADD COLUMN text_corrected VARCHAR(2000) NULL COMMENT '修正后文本（转写修正管线自动应用；NULL=无修正）',
    ADD COLUMN correction_meta TEXT NULL COMMENT '修正元数据 JSON（original/suggestion/evidence/status/source）';
