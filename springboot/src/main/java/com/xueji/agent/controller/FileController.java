package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.domain.vo.ChatVideoUploadVO;
import com.xueji.agent.service.ChatVideoService;
import com.xueji.agent.utils.AliUploadUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传接口：对话附图 / 对话视频（B11）等
 */
@RequestMapping("/upload")
@RestController
public class FileController {

    @Resource
    private AliUploadUtils aliUploadUtils;

    @Resource
    private ChatVideoService chatVideoService;

    /**
     * 上传对话图片，返回可访问的图片 URL
     */
    @PostMapping("/image")
    public Result<String> uploadImage(@RequestParam("file") MultipartFile file) {
        return Result.data(aliUploadUtils.uploadChatImage(file, "chat"));
    }

    /**
     * 上传对话视频（B11，仅语音转写，≤30 分钟）：
     * 本地暂存 + 同步 ffprobe 时长校验，返回临时路径与时长（随 WS 消息回传给转写链路）
     */
    @PostMapping("/chat-video")
    public Result<ChatVideoUploadVO> uploadChatVideo(@RequestParam("file") MultipartFile file) {
        return Result.data(chatVideoService.upload(file));
    }

    /**
     * B 站链接视频拉取（2026-10-12）：yt-dlp 下载到本地临时目录，
     * 返回与 chat-video 同构的临时路径与时长——后续意图卡/建课/流水线零改动复用
     */
    @PostMapping("/chat-video-url")
    public Result<ChatVideoUploadVO> uploadChatVideoUrl(@RequestParam("url") String url) {
        return Result.data(chatVideoService.downloadFromUrl(url));
    }
}
