# syntax=docker/dockerfile:1

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom 预热依赖缓存（代码变更不会重复拉依赖）
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package

# ---------- 运行阶段 ----------
# Ubuntu 24.04：Python 3.12 + ffmpeg 6（避免 yt-dlp 对 Python 3.10 的弃用告警）
FROM eclipse-temurin:21-jre-noble

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Shanghai"

# ffmpeg（抽音频/关键帧）、yt-dlp 自包含二进制（yt-dlp_linux，无需 Python；ARM64 机器请换成 yt-dlp_linux_aarch64）、curl（健康检查）
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates \
    && curl -fsSL https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux -o /usr/local/bin/yt-dlp \
    && chmod +x /usr/local/bin/yt-dlp \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -m -u 10001 app

WORKDIR /app
COPY --from=build /build/target/*.jar app.jar

# 视频上传临时目录（multipart 上限 1GB，需可写空间）
RUN mkdir -p /app/tmp && chown -R app:app /app
USER app

EXPOSE 9090

# TCP 探活：不依赖 actuator，避免额外依赖
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/9090' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
