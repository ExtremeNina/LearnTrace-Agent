package com.xueji.agent.ai.tool;

import com.xueji.agent.domain.entity.UserProfile;
import com.xueji.agent.service.ProfileService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 学习者画像存档工具（B26 反馈：画像改对话采集）：
 * LLM 从对话中问出用户基本情况后调用存档；存档后后续回合的个性化与内容评审都会引用画像
 */
public class SaveProfileTool {

    private final ProfileService profileService;

    public SaveProfileTool(ProfileService profileService) {
        this.profileService = profileService;
    }

    @Tool(description = "保存学习者的画像（从对话中采集的基本情况）。"
            + "当用户尚未填写画像、且你已按问询指引询问并得到用户回答时调用；"
            + "只填写用户明确说过或能确定的信息，没问到的传空字符串")
    public String saveLearningProfile(
            @ToolParam(description = "学段，如初中 / 高中 / 大学，未知传空字符串", required = false)
            String gradeLevel,
            @ToolParam(description = "自评水平或基础情况，如入门 / 进阶 / 数学基础薄弱", required = false)
            String level,
            @ToolParam(description = "学习目标，如通过期末考试 / 考研 / 兴趣了解", required = false)
            String goal,
            @ToolParam(description = "其他偏好，如希望通俗易懂地讲 / 喜欢例子多", required = false)
            String note,
            ToolContext toolContext) {
        Long userId = (Long) toolContext.getContext().get("userId");
        try {
            UserProfile profile = new UserProfile()
                    .setGradeLevel(blankToNull(gradeLevel))
                    .setLevel(blankToNull(level))
                    .setGoal(blankToNull(goal))
                    .setNote(blankToNull(note));
            profileService.save(userId, profile);
            return "PROFILE_SAVED";
        } catch (Exception e) {
            return "PROFILE_FAILED: " + e.getMessage();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
