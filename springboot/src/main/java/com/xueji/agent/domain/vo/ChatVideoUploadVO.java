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

    /** 视频时长（秒，ffprobe 探测） */
    private Integer durationSec;
}
