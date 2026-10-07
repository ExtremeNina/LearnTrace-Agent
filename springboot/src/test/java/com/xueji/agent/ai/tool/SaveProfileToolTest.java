package com.xueji.agent.ai.tool;

import com.xueji.agent.domain.entity.UserProfile;
import com.xueji.agent.service.ProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画像存档工具单测（B26 反馈）：成功落库 / 异常返回码 / 空串归 null
 */
class SaveProfileToolTest {

    private ProfileService profileService;
    private SaveProfileTool tool;
    private final ToolContext context = new ToolContext(Map.of("userId", 5L));

    @BeforeEach
    void setUp() {
        profileService = mock(ProfileService.class);
        tool = new SaveProfileTool(profileService);
    }

    @Test
    void shouldSaveTrimmedProfile() {
        String result = tool.saveLearningProfile(" 大学 ", "数学基础薄弱", "通过期末考试", " ", context);
        assertThat(result).isEqualTo("PROFILE_SAVED");
        ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
        verify(profileService).save(org.mockito.ArgumentMatchers.eq(5L), captor.capture());
        assertThat(captor.getValue().getGradeLevel()).isEqualTo("大学");
        assertThat(captor.getValue().getNote()).isNull();
    }

    @Test
    void shouldReturnFailedCodeWhenServiceThrows() {
        when(profileService.save(any(), any())).thenThrow(new RuntimeException("db down"));
        String result = tool.saveLearningProfile("大学", "", "", "", context);
        assertThat(result).startsWith("PROFILE_FAILED");
    }
}
