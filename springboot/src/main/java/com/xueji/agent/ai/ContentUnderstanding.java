package com.xueji.agent.ai;

import java.util.List;

/**
 * 内容理解产出（B26 阶段 2）：LLM 输出解析后的中间模型，落库为 ContentDocument 三表
 */
public class ContentUnderstanding {

    /* 字段包内可见：由 ContentUnderstandingService.parseUnderstanding 直接赋值（同包） */
    String title;
    String summary;
    List<Section> sections;
    List<KnowledgePoint> knowledgePoints;

    public static class Section {
        String title;
        String summary;
        Integer startSec;
        Integer endSec;

        public String getTitle() {
            return title;
        }

        public String getSummary() {
            return summary;
        }

        public Integer getStartSec() {
            return startSec;
        }

        public Integer getEndSec() {
            return endSec;
        }
    }

    public static class KnowledgePoint {
        String name;
        String detail;
        Integer timeSec;
        Integer sectionSort;
        Boolean important;
        Boolean errorProne;

        public String getName() {
            return name;
        }

        public String getDetail() {
            return detail;
        }

        public Integer getTimeSec() {
            return timeSec;
        }

        public Integer getSectionSort() {
            return sectionSort;
        }

        public Boolean getImportant() {
            return important;
        }

        public Boolean getErrorProne() {
            return errorProne;
        }
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public List<Section> getSections() {
        return sections;
    }

    public List<KnowledgePoint> getKnowledgePoints() {
        return knowledgePoints;
    }
}
