package com.xueji.agent.service;

import cn.hutool.json.JSONObject;
import com.xueji.agent.ai.CorrectionCandidate;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseFrame;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import com.xueji.agent.mapper.CourseTranscriptSegmentMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 转写修正应用服务（B26 阶段 1 修正管线的确认位）：
 * 对 LLM 候选逐条做佐证确认——帧 OCR 交叉验证（候选修正词出现在对应时间范围附近的画面识别文本中）。
 * 命中 → APPLIED：写 text_corrected（下游消费修正版）；未命中 → SUGGESTED：仅记元数据进批量确认清单。
 * Raw 不可变原则：text 列永不修改，修正以新列留痕叠加。
 */
@Slf4j
@Service
public class TranscriptCorrectionService {

    /** 候选词佐证的帧时间容差（秒）：分段起止前后各放宽 60s（PPT 预翻页场景） */
    private static final int FRAME_EVIDENCE_WINDOW_SEC = 60;

    /** 佐证状态 */
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_SUGGESTED = "SUGGESTED";

    @Resource
    private CourseTranscriptSegmentMapper segmentMapper;

    /**
     * 应用候选修正到转写分段（内存行更新 + 落库）。
     *
     * @return 自动应用（APPLIED）条数
     */
    public int apply(Course course, List<CourseTranscriptSegment> segments,
                     List<CorrectionCandidate> candidates, List<CourseFrame> frames) {
        int applied = 0;
        int suggested = 0;
        for (CorrectionCandidate candidate : candidates) {
            if (candidate.getSort() < 0 || candidate.getSort() >= segments.size()) {
                continue;
            }
            CourseTranscriptSegment segment = segments.get(candidate.getSort());
            if (segment.getCorrectionMeta() != null || segment.getText() == null
                    || !segment.getText().contains(candidate.getOriginal())
                    || candidate.getOriginal().equals(candidate.getSuggestion())) {
                // 已有修正 / 原文不含候选词 / 候选词与原文相同（无意义）：跳过
                continue;
            }

            String evidence = findFrameEvidence(frames, segment, candidate.getSuggestion());
            String status = evidence != null ? STATUS_APPLIED : STATUS_SUGGESTED;
            JSONObject meta = new JSONObject()
                    .set("original", candidate.getOriginal())
                    .set("suggestion", candidate.getSuggestion())
                    .set("reason", candidate.getReason())
                    .set("evidence", evidence)
                    .set("status", status)
                    .set("source", "llm_review");
            if (evidence != null) {
                segment.setTextCorrected(candidate.getSuggestion());
                applied++;
            } else {
                suggested++;
            }
            segment.setCorrectionMeta(meta.toString());
            segmentMapper.updateById(segment);
            log.info("转写修正候选已处理, courseId={}, sort={}, {}={}, 状态={}",
                    course.getId(), candidate.getSort(), candidate.getOriginal(), candidate.getSuggestion(), status);
        }
        log.info("转写修正应用完成, courseId={}, applied={}, suggested={}", course.getId(), applied, suggested);
        return applied;
    }

    /** 帧 OCR 交叉验证：分段时间范围（±60s）内的画面文本包含建议词 → 佐证命中，返回证据描述 */
    private String findFrameEvidence(List<CourseFrame> frames, CourseTranscriptSegment segment, String suggestion) {
        if (frames == null || suggestion == null || suggestion.isBlank()
                || segment.getStartSec() == null || segment.getEndSec() == null) {
            return null;
        }
        for (CourseFrame frame : frames) {
            if (frame.getOcrText() == null || frame.getTimeSec() == null) {
                continue;
            }
            boolean inWindow = frame.getTimeSec() >= segment.getStartSec() - FRAME_EVIDENCE_WINDOW_SEC
                    && frame.getTimeSec() <= segment.getEndSec() + FRAME_EVIDENCE_WINDOW_SEC;
            if (inWindow && frame.getOcrText().contains(suggestion)) {
                return "帧OCR@" + frame.getTimeSec() + "s";
            }
        }
        return null;
    }
}
