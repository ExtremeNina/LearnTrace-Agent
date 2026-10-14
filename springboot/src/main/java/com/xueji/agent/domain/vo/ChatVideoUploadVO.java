package com.xueji.agent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 对话视频上传结果（B11）：本地临时路径供转写任务使用，时长供前端展示与二次校验
 */
@Data
@Accessors(chain = true)
public class ChatVideoUploadVO {

    /** 本地临时文件路径（转写任务用，系统临时目录内） */
    private String tempPath;

    /** 权威视频标题/文件名（B 站链接为 yt-dlp 抓取的真实标题，文件上传为原始文件名）：WS videoName 透传建课标题用 */
    private String name;

    /** 视频时长（秒，ffprobe 探测） */
    private Integer durationSec;
}
