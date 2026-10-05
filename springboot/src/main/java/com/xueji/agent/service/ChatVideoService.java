package com.xueji.agent.service;

import com.xueji.agent.domain.vo.ChatVideoUploadVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 对话视频上传（B11）：本地暂存 + 同步 ffprobe 时长探测（不做时长拒绝——
 * ≤30 分钟走轻量转写，>30 分钟由 LLM 分流到课程流水线，见 TranscribeVideoTool）
 */
public interface ChatVideoService {

    /** 对话视频大小上限：1GB（与网课上传一致，长视频普遍较大） */
    long MAX_CHAT_VIDEO_SIZE = 1024L * 1024 * 1024;

    /**
     * 上传对话视频：校验格式 / 大小，保存到系统临时目录并返回元信息（时长由转写工具与课程分支各自校验）
     */
    ChatVideoUploadVO upload(MultipartFile file);
}
