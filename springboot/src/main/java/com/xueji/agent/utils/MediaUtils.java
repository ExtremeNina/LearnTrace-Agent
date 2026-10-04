package com.xueji.agent.utils;

import java.io.IOException;
import java.nio.file.Path;

/**
 * FFmpeg / ffprobe 命令行工具（网课流水线与对话视频转写共用）：
 * 注意本机 FFmpeg 为新版，已移除 -vsync（抽帧用 -fps_mode vfr）；ProcessBuilder 无 shell，参数原样传递
 */
public final class MediaUtils {

    private MediaUtils() {
    }

    /**
     * ffprobe 探测视频时长（秒，四舍五入）
     */
    public static int ffprobeDurationSec(Path video) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "csv=p=0", video.toString()).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return (int) Math.round(Double.parseDouble(out));
    }

    /**
     * FFmpeg 抽音频：单声道 16kHz wav（ASR 输入要求）
     */
    public static void extractAudio(Path video, Path audio) throws IOException, InterruptedException {
        run("ffmpeg", "-y", "-i", video.toString(), "-vn", "-ac", "1", "-ar", "16000", "-f", "wav", audio.toString());
    }

    /**
     * 执行外部命令，非零退出码抛 IllegalStateException（输出截断附在消息中）
     */
    public static void run(String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("命令执行失败(" + code + "): " + truncate(output));
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : s.substring(0, Math.min(500, s.length()));
    }
}
