package com.xueji.agent.ai;

import com.xueji.agent.domain.entity.ContentKnowledgePoint;
import com.xueji.agent.domain.entity.ContentSection;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.CourseTranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容检索纯函数单测（B26 阶段 4）：命中格式化、片段截断、时间戳格式
 */
class ContentSearchServiceTest {

    @Test
    void formatHitsShouldRenderSectionsPointsTranscript() {
        Map<Long, Course> courseById = new HashMap<>();
        courseById.put(1L, new Course().setId(1L).setTitle("全球冰封"));
        Map<Long, Long> courseByDocumentId = Map.of(10L, 1L);

        ContentSection section = new ContentSection()
                .setDocumentId(10L).setTitle("洋流中断").setSummary("洋流停止循环")
                .setStartSec(280).setEndSec(400);
        ContentKnowledgePoint point = new ContentKnowledgePoint()
                .setDocumentId(10L).setName("反照率效应").setDetail("冰面反射阳光").setTimeSec(60);
        CourseTranscriptSegment segment = new CourseTranscriptSegment()
                .setCourseId(1L).setStartSec(120).setEndSec(130).setText("……海冰蔓延……");

        String result = ContentSearchService.formatHits("洋流", courseById, courseByDocumentId,
                List.of(section), List.of(point), List.of(segment));

        assertThat(result).contains("《全球冰封》章节 [04:40]-[06:40] 洋流中断")
                .contains("知识点 [01:00] 反照率效应")
                .contains("转写 [02:00] \"……海冰蔓延……\"");
    }

    @Test
    void formatHitsShouldExplainEmptyResult() {
        String result = ContentSearchService.formatHits("不存在词", new HashMap<>(), Map.of(),
                List.of(), List.of(), List.of());
        assertThat(result).contains("未检索到");
    }

    @Test
    void snippetShouldTruncateLongText() {
        assertThat(ContentSearchService.snippet(null)).isEmpty();
        assertThat(ContentSearchService.snippet("短文本")).isEqualTo("短文本");
        String longText = "字".repeat(200);
        String snipped = ContentSearchService.snippet(longText);
        assertThat(snipped).hasSize(ContentSearchService.SNIPPET_MAX_CHARS + 1);
        assertThat(snipped).endsWith("…");
    }

    @Test
    void fmtShouldFormatTimestamps() {
        assertThat(ContentSearchService.fmt(300)).isEqualTo("[05:00]");
        assertThat(ContentSearchService.fmt(65)).isEqualTo("[01:05]");
        assertThat(ContentSearchService.fmt(null)).isEqualTo("[00:00]");
        assertThat(ContentSearchService.fmt(-1)).isEqualTo("[00:00]");
    }
}
