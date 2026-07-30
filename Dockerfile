# syntax=docker/dockerfile:1.6
#
# 后端镜像：Spring Boot 3.3 + Java 17
# 多阶段构建：Stage 1 用 maven+jdk 编译，Stage 2 只带 jre，最终镜像 ~200MB
# 构建：docker build -t rag-app:latest .
# 由 docker-compose.prod.yml 里的 `build: .` 触发

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先只拷 pom.xml，触发依赖下载并利用 Docker layer cache
# 后续 src/ 变更不会重下依赖
COPY pom.xml .
RUN mvn -B dependency:go-offline

# 拷贝源码并打包（跳过测试：CI/本地已跑过；生产镜像构建只关心可运行制品）
COPY src ./src
RUN mvn -B -DskipTests clean package

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 非 root 运行，生产标配
RUN groupadd -r rag && useradd -r -g rag rag \
    && mkdir -p /app/logs /app/data \
    && chown -R rag:rag /app

# spring-boot-maven-plugin repackage 后的 fat jar
COPY --from=builder /build/target/rag-learning-*.jar /app/app.jar

USER rag

EXPOSE 19090

# JVM 参数：容器感知内存、UTC 时区（日志按 ISO 展示，前端渲染时再本地化）
# -XX:MaxRAMPercentage=75 让 JVM 用容器 memory limit 的 75%
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"

# 生产 profile 固定 prod；密钥/端点通过 docker compose env 注入
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=prod"]
