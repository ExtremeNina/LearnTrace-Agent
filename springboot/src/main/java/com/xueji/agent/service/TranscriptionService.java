package com.xueji.agent.service;

/**
 * 对话视频语音转写（B11）：后台任务型服务，与网课流水线共用 ASR 能力但互不依赖。
 * 提交后立即返回（不阻塞对话回合）；进度与结果经消息更新 + WS 推送回流会话。
 */
public interface TranscriptionService {

    /**
     * 提交转写任务：创建转写占位消息（msgType=video_transcript）并异步执行。
     * 完成后占位消息原地更新为转写全文并推送；失败同样更新消息并推送。
     *
     * @param videoTempPath 本地视频临时文件（上传接口产出，必须在系统临时目录内）
     * @param durationSec   视频时长（秒，上传接口 ffprobe 探测值）
     * @return 转写占位消息 ID
     */
    Long submit(Long userId, Long conversationId, String videoTempPath, int durationSec);
}
