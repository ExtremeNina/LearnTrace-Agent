package com.xueji.agent.service.impl;

import com.xueji.agent.domain.vo.ChatVideoUploadVO;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.service.ChatVideoService;
import com.xueji.agent.utils.MediaUtils;
import com.xueji.agent.utils.YtDlpClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 对话视频上传实现：复用网课的格式白名单；
 * 时长仅探测不拒绝（≤30min 轻量转写 / >30min 课程流水线，分流在 TranscribeVideoTool）
 */
@Slf4j
@Service
public class ChatVideoServiceImpl implements ChatVideoService {

    /** 与 AliUploadUtils 的网课视频白名单一致 */
    private static final List<String> ALLOWED_VIDEO_EXT = List.of("mp4", "mov", "mkv", "avi", "webm", "m4v");

    /** B 站链接路径的占位名（前端 agent.ts 同款文案）：无真实文件名语义，作 videoName 时回退临时文件名规则 */
    public static final String BILIBILI_PLACEHOLDER_NAME = "B 站视频链接";

    @Resource
    private YtDlpClient ytDlpClient;

    @Override
    public ChatVideoUploadVO downloadFromUrl(String url) {
        YtDlpClient.DownloadResult result = ytDlpClient.download(url, MAX_CHAT_VIDEO_SIZE);
        Path video = result.file();
        try {
            int durationSec = MediaUtils.ffprobeDurationSec(video);
            log.info("B 站链接视频拉取完成, 时长={}s, 标题={}", durationSec, result.title());
            // name 为 yt-dlp 抓取的真实标题：前端随 WS videoName 透传，建课标题不再依赖临时文件名
            return new ChatVideoUploadVO().setTempPath(video.toString()).setDurationSec(durationSec).setName(result.title());
        } catch (IOException | InterruptedException e) {
            cleanupQuietly(video);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("B 站链接视频 ffprobe 失败", e);
            throw new BusinessException("链接视频解析失败，请稍后重试或直接上传文件");
        }
    }

    @Override
    public ChatVideoUploadVO upload(MultipartFile file) {
        String original = file.getOriginalFilename();
        String ext = extOf(original);
        if (!ALLOWED_VIDEO_EXT.contains(ext)) {
            throw new BusinessException("不支持的视频格式，仅支持 mp4 / mov / mkv / avi / webm / m4v");
        }
        if (file.getSize() > MAX_CHAT_VIDEO_SIZE) {
            throw new BusinessException("视频大小不能超过 1GB");
        }

        Path temp = null;
        try {
            // 保留原始文件名（+随机后缀保唯一）：下游建课标题兜底取文件名，
            // 无语义的 xj-chat-video-<随机数> 会导致课程标题失去可读性（B27 反馈）
            temp = Files.createTempFile(baseName(original) + "-", "." + ext);
            file.transferTo(temp);
            int durationSec = MediaUtils.ffprobeDurationSec(temp);
            log.info("对话视频上传完成, 时长={}s, 大小={}B", durationSec, file.getSize());
            return new ChatVideoUploadVO().setTempPath(temp.toString()).setDurationSec(durationSec);
        } catch (BusinessException e) {
            cleanupQuietly(temp);
            throw e;
        } catch (IOException | InterruptedException e) {
            cleanupQuietly(temp);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("对话视频保存失败", e);
            throw new BusinessException("视频上传失败，请稍后重试");
        }
    }

    private String extOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 前端权威文件名 → 课程标题（去扩展名、清洗非法字符）。
     * 空 / B 站占位名返回 null，调用方回退各自的临时文件名标题规则（titleFromFileName）——
     * 与损坏点无关地根治建课标题乱码（videoName 经 WS 透传，不经 multipart/临时路径）
     */
    public static String courseTitleFromVideoName(String videoName) {
        if (videoName == null || videoName.isBlank() || BILIBILI_PLACEHOLDER_NAME.equals(videoName.trim())) {
            return null;
        }
        String cleaned = baseName(videoName.trim());
        // baseName 对无有效字符的输入回退默认基名，视作无效交给调用方兜底
        return "xj-chat-video".equals(cleaned) ? null : cleaned;
    }

    /** 原始文件名去扩展名做临时文件基名；清洗非法路径字符，无有效名回退默认（公开静态便于单测） */
    public static String baseName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "xj-chat-video";
        }
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\s]", "_").trim();
        return name.isBlank() ? "xj-chat-video" : name;
    }

    private void cleanupQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }
}
