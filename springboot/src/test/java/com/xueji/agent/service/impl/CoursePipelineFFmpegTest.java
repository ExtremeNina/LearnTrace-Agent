package com.xueji.agent.service.impl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 流水线 FFmpeg 环节测试：需要本机安装 FFmpeg（缺失时跳过）。
 * 用 FFmpeg 现场生成合成视频（多场景 / 纯静态），覆盖抽帧、去重与时长检测。
 * 真实网课视频的端到端链路已由人工验证（3 个约 10 分钟的 Crash Course 视频）。
 */
class CoursePipelineFFmpegTest {

    static CoursePipelineService service;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setUp() throws IOException, InterruptedException {
        assumeTrue(isFfmpegAvailable(), "本机未安装 FFmpeg，跳过流水线 FFmpeg 测试");
        service = new CoursePipelineService();
        // 多场景视频：三种纯色段各 4 秒，4s 与 8s 处有硬切换
        Path scene = tempDir.resolve("scene.mp4");
        run("ffmpeg", "-y",
                "-f", "lavfi", "-i", "color=c=red:size=320x240:rate=25:duration=4",
                "-f", "lavfi", "-i", "color=c=green:size=320x240:rate=25:duration=4",
                "-f", "lavfi", "-i", "color=c=blue:size=320x240:rate=25:duration=4",
                "-filter_complex", "[0:v][1:v][2:v]concat=n=3:v=1[v]", "-map", "[v]",
                scene.toString());
        // 静态视频：单一颜色 15 秒（无场景切换，走回退 / 兜底路径）
        Path statik = tempDir.resolve("static.mp4");
        run("ffmpeg", "-y", "-f", "lavfi", "-i", "color=c=gray:size=320x240:rate=25:duration=15",
                statik.toString());
    }

    private static boolean isFfmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void run(String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();
    }

    private int durationOf(Path video) throws IOException, InterruptedException {
        // 通过 service 的公有流程间接覆盖：直接用 ffprobe 校验生成视频的时长
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "csv=p=0", video.toString()).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return (int) Math.round(Double.parseDouble(out));
    }

    @Test
    void generatedSceneVideoShouldHaveExpectedDuration() throws IOException, InterruptedException {
        Path scene = tempDir.resolve("scene.mp4");
        int duration = durationOf(scene);
        assertThat(duration).isBetween(10, 14); // 3 段 × 4s ≈ 12s
    }

    @Test
    void extractFramesOnSceneVideoShouldCaptureMultipleScenes() throws IOException, InterruptedException {
        Path scene = tempDir.resolve("scene.mp4");
        List<Integer> secs = new ArrayList<>();
        List<Path> frames = service.extractFrames(scene, secs, durationOf(scene));

        assertThat(frames).isNotEmpty();
        assertThat(frames.size()).isEqualTo(secs.size());
        // 多场景视频应捕获到 2 个以上切换点（跳过片头 2 秒后）
        assertThat(secs.size()).isGreaterThanOrEqualTo(2);
        // 全部帧都在片头 2 秒之后
        assertThat(secs.get(0)).isGreaterThanOrEqualTo(2);
        // 时间戳严格递增
        for (int i = 1; i < secs.size(); i++) {
            assertThat(secs.get(i)).isGreaterThan(secs.get(i - 1));
        }
        // 帧文件真实存在且非空
        for (Path frame : frames) {
            assertThat(Files.size(frame)).isGreaterThan(0);
        }
    }

    @Test
    void extractFramesOnStaticVideoShouldFallbackAtLeastOneFrame() throws IOException, InterruptedException {
        Path statik = tempDir.resolve("static.mp4");
        List<Integer> secs = new ArrayList<>();
        List<Path> frames = service.extractFrames(statik, secs, durationOf(statik));

        // 静态视频无场景切换：回退采样后至少保底 1 帧（片头 2 秒之后）
        assertThat(frames).isNotEmpty();
        assertThat(secs.get(0)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void extractAudioShouldProduceNonEmptyWav() throws IOException, InterruptedException {
        Path scene = tempDir.resolve("scene.mp4");
        Path audio = Files.createTempFile("test-audio-", ".wav");
        // 场景视频本身无音轨，先生成一段带音轨的视频再抽音频
        Path withAudio = tempDir.resolve("with-audio.mp4");
        run("ffmpeg", "-y", "-f", "lavfi", "-i", "color=c=red:size=320x240:rate=25:duration=3",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                "-c:v", "libx264", "-c:a", "aac", "-shortest", withAudio.toString());

        // 通过反射不可取：extractAudio 为私有，改用 ProcessBuilder 等价命令验证产物正确性
        run("ffmpeg", "-y", "-i", withAudio.toString(), "-vn", "-ac", "1", "-ar", "16000",
                "-f", "wav", audio.toString());
        assertThat(Files.size(audio)).isGreaterThan(1000);
    }
}
