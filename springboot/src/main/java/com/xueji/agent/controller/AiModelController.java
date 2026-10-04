package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.domain.dto.AiModelSaveDto;
import com.xueji.agent.domain.dto.AiModelTestDto;
import com.xueji.agent.domain.vo.AiModelVO;
import com.xueji.agent.service.AiModelService;
import com.xueji.agent.utils.UserUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模型管理接口：用户自建 OpenAI 兼容模型配置的增删改查、连接测试、模块偏好
 */
@RequestMapping("/models")
@RestController
public class AiModelController {

    @Resource
    private AiModelService aiModelService;

    /** 我的模型配置列表（密钥脱敏） */
    @GetMapping
    public Result<List<AiModelVO>> list() {
        return Result.data(aiModelService.listModels(UserUtils.getCurrentLoginId()));
    }

    /** 添加模型配置 */
    @PostMapping
    public Result<AiModelVO> add(@RequestBody AiModelSaveDto dto) {
        return Result.data(aiModelService.addModel(UserUtils.getCurrentLoginId(),
                dto.getName(), dto.getBaseUrl(), dto.getApiKey(), dto.getModel()));
    }

    /** 编辑模型配置（apiKey 留空 = 保持原值） */
    @PutMapping("/{id}")
    public Result<AiModelVO> update(@PathVariable Long id, @RequestBody AiModelSaveDto dto) {
        return Result.data(aiModelService.updateModel(UserUtils.getCurrentLoginId(), id,
                dto.getName(), dto.getBaseUrl(), dto.getApiKey(), dto.getModel()));
    }

    /** 删除模型配置（引用它的模块自动回退系统默认） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        aiModelService.deleteModel(UserUtils.getCurrentLoginId(), id);
        return Result.ok("已删除");
    }

    /** 测试连接（已落库配置） */
    @PostMapping("/{id}/test")
    public Result<Void> test(@PathVariable Long id) {
        aiModelService.testConnection(UserUtils.getCurrentLoginId(), id);
        return Result.ok("连接成功");
    }

    /** 测试连接（弹窗草稿，未落库） */
    @PostMapping("/test")
    public Result<Void> testDraft(@RequestBody AiModelTestDto dto) {
        aiModelService.testConnectionDraft(UserUtils.getCurrentLoginId(),
                dto.getBaseUrl(), dto.getApiKey(), dto.getModel());
        return Result.ok("连接成功");
    }

    /** 模块偏好：chat / course_note / briefing → 模型配置 ID（null = 系统默认） */
    @GetMapping("/module-preferences")
    public Result<Map<String, Long>> modulePreferences() {
        return Result.data(aiModelService.getModulePreferences(UserUtils.getCurrentLoginId()));
    }

    /** 设置模块偏好 */
    @PutMapping("/module-preferences")
    public Result<Void> setModulePreference(@RequestParam String module, @RequestParam(required = false) Long configId) {
        aiModelService.setModulePreference(UserUtils.getCurrentLoginId(), module, configId);
        return Result.ok("已保存");
    }
}
