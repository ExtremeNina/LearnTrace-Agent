package com.xueji.agent.utils;

import com.xueji.agent.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * yt-dlp 客户端（B 站链接转写，2026-10-12）：解析并下载 B 站视频到本地临时目录，
 * 产物与文件上传同构（临时 mp4 + ffprobe 时长），下游意图卡/建课/流水线零改动。
 * 仅支持 B 站视频页（BV 号白名单，无 SSRF 面）；错误按 stderr 分类映射为可读文案。
 * yt-dlp 为外部二进制（配置 yt-dlp.path，默认走 PATH），缺失仅在下载时报错不阻断启动。
 */
@Slf4j
@Component
public class YtDlpClient {

    /** B 站视频页白名单：bilibili.com/video/BVxxxxxxxx（BV + 10 位字母数字） */
    private static final Pattern BILIBILI_VIDEO = Pattern.compile(
            "^https?://(www\\.)?bilibili\\.com/video/(BV[0-9A-Za-z]{8,12})(\\?.*)?$");

    /** 分 P 参数（?p=N）：N≥2 为非首 P */
    private static final Pattern PART_PARAM = Pattern.compile("[?&]p=(\\d+)");

    /** 单次下载超时（分钟）：B 站 70MB 级通常 10~30s，上限留足长视频余量 */
    private static final long DOWNLOAD_TIMEOUT_MINUTES = 10;

    /** yt-dlp stderr → 可读文案的分类映射（contains 匹配，小写） */
    private static final Map<String, String> ERROR_CLASSIFICATION = buildErrorClassification();

    @Value("${ytdlp.path:yt-dlp}")
    private String ytdlpPath;

    /**
     * 浏览器 cookies 登录态（逗号分隔多浏览器，如 edge,chrome）：课程类视频的音频流 URL 对游客签名无效
     * （CDN 挂起连接导致下载卡死），必须携带登录 cookies；空 = 游客模式（与历史行为一致，零回归）
     */
    @Value("${ytdlp.cookies-from-browser:}")
    private String cookiesFromBrowser;

    @PostConstruct
    public void warnIfMissing() {
        // 仅对显式配置的绝对路径做存在性告警；PATH 方式（默认）无法静态判断，留给下载时报错
        if (ytdlpPath != null && (ytdlpPath.contains("\\") || ytdlpPath.contains("/"))
                && !Files.exists(Path.of(ytdlpPath))) {
            log.warn("yt-dlp 路径配置不存在: {} —— B 站链接转写不可用（下载时报错），请安装 yt-dlp 或修正 ytdlp.path", ytdlpPath);
        }
    }

    /** 校验 URL 是否 B 站视频页（公开静态便于单测）；通过返回原文，不通过抛可读文案 */
    public static String validateBilibiliUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new BusinessException("请提供视频链接");
        }
        String trimmed = url.trim();
        if (!BILIBILI_VIDEO.matcher(trimmed).matches()) {
            throw new BusinessException("仅支持 B 站视频链接（bilibili.com/video/BV…）");
        }
        Matcher part = PART_PARAM.matcher(trimmed);
        if (part.find() && Integer.parseInt(part.group(1)) >= 2) {
            throw new BusinessException("多 P 视频暂不支持，请去掉 ?p= 参数仅转写第一个分 P");
        }
        return trimmed;
    }

    /**
     * 下载 B 站视频到本地临时文件（以视频标题命名，供建课标题兜底）。
     *
     * @return 下载产物路径（mp4）
     * @throws BusinessException 分类后的可读文案
     */
    public Path download(String url, long maxBytes) {
        String validated = validateBilibiliUrl(url);
        Path dir = null;
        try {
            dir = Files.createTempDirectory("xj-bili-");
            // 先取标题（同时快速校验视频存在性与可访问性），再以标题命名产物文件
            String title = runYtDlp(buildCommandArgs(ytdlpPath, cookiesFromBrowser,
                    "--skip-download", "--print", "%(title).80s", validated), DOWNLOAD_TIMEOUT_MINUTES);
            if (title == null || title.isBlank()) {
                title = "bilibili-" + bvOf(validated);
            }
            String outputTemplate = dir.resolve("%(title).80s.%(ext)s").toString();
            runYtDlp(buildCommandArgs(ytdlpPath, cookiesFromBrowser,
                    "-f", "bv*[height<=1080]+ba/b[height<=1080]/b",
                    "--merge-output-format", "mp4", "-o", outputTemplate, validated), DOWNLOAD_TIMEOUT_MINUTES);

            Path product = locateProduct(dir);
            long size = Files.size(product);
            if (size > maxBytes) {
                cleanupDir(dir);
                throw new BusinessException("视频过大（" + size / 1024 / 1024 + "MB），超出 1GB 处理限制");
            }
            // 以净化后的视频标题命名临时文件：建课标题兜底取文件名（titleFromFileName），
            // 课程列表/详情由此可区分不同视频（2026-10-12 反馈：xj-bili 无法区分）
            Path moved = Files.createTempFile(sanitizeTitle(title) + "-", ".mp4");
            Files.move(product, moved, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            cleanupDir(dir);
            log.info("B 站视频下载完成: url={}, title={}, size={}B", validated, title, size);
            return moved;
        } catch (BusinessException e) {
            cleanupDir(dir);
            throw e;
        } catch (IOException e) {
            // 进程启动失败：yt-dlp 缺失（PATH 找不到）属配置问题，日志留痕
            cleanupDir(dir);
            log.error("yt-dlp 启动失败（可能未安装）, path={}", ytdlpPath, e);
            throw new BusinessException("链接视频拉取失败，请稍后重试或直接上传文件");
        } catch (InterruptedException e) {
            cleanupDir(dir);
            Thread.currentThread().interrupt();
            throw new BusinessException("链接视频拉取被中断，请稍后重试");
        }
    }

    /** 执行 yt-dlp 命令：非零退出码按 stderr 分类抛可读文案，超时强杀并清理 */
    private String runYtDlp(String[] command, long timeoutMinutes) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        boolean finished = p.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            p.destroyForcibly();
            throw new BusinessException("链接视频拉取超时，请稍后重试或直接上传文件");
        }
        output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (p.exitValue() != 0) {
            log.warn("yt-dlp 执行失败, exit={}, output={}", p.exitValue(), truncate(output));
            throw new BusinessException(classifyError(output));
        }
        // --print 输出取最后一行非空（进度条与告警在前）
        String[] lines = output.trim().split("\\r?\\n");
        return lines.length > 0 ? lines[lines.length - 1].trim() : null;
    }

    /** 产物定位：临时目录内唯一的视频文件（yt-dlp 按标题命名，扩展名以实际产出为准） */
    private Path locateProduct(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.{mp4,mkv,webm}")) {
            Path found = null;
            for (Path candidate : stream) {
                if (found != null) {
                    throw new BusinessException("链接视频拉取结果异常（多个产物文件），请稍后重试");
                }
                found = candidate;
            }
            if (found == null) {
                throw new BusinessException("链接视频拉取失败，未获得视频文件，请稍后重试或直接上传文件");
            }
            return found;
        }
    }

    /**
     * yt-dlp 命令组装：防卡死 socket 超时 + 浏览器 cookies 登录态 + 额外参数。
     * --socket-timeout 20：B 站对无效签名的流地址会"接受连接但不吐数据"，无超时会干等到总超时；
     * 加上后 20 秒收不到数据即报错，由 yt-dlp 内部自动重试换节点（静态纯函数便于单测）
     */
    static String[] buildCommandArgs(String ytdlpPath, String cookiesFromBrowser, String... extras) {
        List<String> args = new java.util.ArrayList<>();
        args.add(ytdlpPath);
        args.add("--no-playlist");
        args.add("--socket-timeout");
        args.add("20");
        args.addAll(cookiesArgs(cookiesFromBrowser));
        for (String extra : extras) {
            args.add(extra);
        }
        return args.toArray(new String[0]);
    }

    /** cookies 配置解析：逗号分隔多浏览器，逐个展开为 --cookies-from-browser；空白配置返回空列表（游客模式，静态纯函数便于单测） */
    static List<String> cookiesArgs(String cookiesFromBrowser) {
        List<String> args = new java.util.ArrayList<>();
        if (cookiesFromBrowser == null) {
            return args;
        }
        for (String browser : cookiesFromBrowser.split(",")) {
            String name = browser.trim();
            if (!name.isEmpty()) {
                args.add("--cookies-from-browser");
                args.add(name);
            }
        }
        return args;
    }

    /** yt-dlp stderr 分类：命中关键词返回映射文案，未命中回退通用文案 */
    static String classifyError(String output) {
        if (output == null) {
            return "链接视频拉取失败，请稍后重试或直接上传文件";
        }
        String lower = output.toLowerCase();
        for (Map.Entry<String, String> entry : ERROR_CLASSIFICATION.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "链接视频拉取失败，请稍后重试或直接上传文件";
    }

    private static Map<String, String> buildErrorClassification() {
        Map<String, String> map = new LinkedHashMap<>();
        // cookie 库读取失败须置于通用 "cookies" 关键词之前（LinkedHashMap 保序，避免被登录文案掩盖真实原因）
        map.put("could not copy", "读取浏览器 Cookie 失败（对应浏览器可能未完全关闭），请关闭后重试或检查 ytdlp.cookies-from-browser 配置");
        map.put("unsupported url", "仅支持 B 站视频链接（bilibili.com/video/BV…）");
        map.put("video unavailable", "视频不存在或已被删除，请确认链接");
        map.put("does not exist", "视频不存在或已被删除，请确认链接");
        map.put("has been removed", "视频不存在或已被删除，请确认链接");
        map.put("private video", "该视频需要登录或付费权限，暂不支持链接转写，请直接上传文件");
        map.put("login", "该视频需要登录或付费权限，暂不支持链接转写，请直接上传文件");
        map.put("cookies", "该视频需要登录或付费权限，暂不支持链接转写，请直接上传文件");
        map.put("premium", "该视频需要登录或付费权限，暂不支持链接转写，请直接上传文件");
        map.put("会员", "该视频需要登录或付费权限，暂不支持链接转写，请直接上传文件");
        map.put("requested format is not available", "该视频无可下载的免费格式，请直接上传文件");
        map.put("timed out", "链接视频拉取超时，请稍后重试或直接上传文件");
        return map;
    }

    private static String bvOf(String url) {
        Matcher matcher = BILIBILI_VIDEO.matcher(url.trim());
        return matcher.matches() ? matcher.group(2) : "video";
    }

    /** 标题清洗为安全文件名前缀：非法路径字符与空白替换为下划线（与 ChatVideoServiceImpl.baseName 同规则），空回退默认 */
    static String sanitizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "xj-bili";
        }
        String name = title.replaceAll("[\\\\/:*?\"<>|\\s]", "_").trim();
        return name.isBlank() ? "xj-bili" : name;
    }

    private static void cleanupDir(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : s.substring(0, Math.min(1500, s.length()));
    }
}
