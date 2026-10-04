package com.xueji.agent.service;

import com.xueji.agent.domain.vo.ChatVideoUploadVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 对话视频上传（B11）：本地暂存 + 同步 ffprobe 时长校验（超限秒级拒绝，不进转写任务）
 */
public interface ChatVideoService {

    /** 对话视频时长上限（秒）：30 分钟，超限引导去视频管理上传完整网课 */
    int MAX_CHAT_VIDEO_SEC = 30 * 60;

    /** 对话视频大小上限：与网课上传一致（500MB） */
    long MAX_CHAT_VIDEO_SIZE = 500L * 1024 * 1024;

    /**
     * 上传对话视频：校验格式 / 大小 / 时长，保存到系统临时目录并返回元信息。
     * 超过 30 分钟抛 BusinessException（提示去视频管理上传）
     */
    ChatVideoUploadVO upload(MultipartFile file);
}
