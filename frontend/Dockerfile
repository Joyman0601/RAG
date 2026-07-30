# syntax=docker/dockerfile:1.6
#
# 前端 + Nginx 一体化镜像
# Stage 1: node 打包出 dist/
# Stage 2: nginx alpine 承载静态文件 + 反代 /api → app:19090 + HTTPS
# 构建：docker build -t rag-web:latest ./frontend

# ---------- Stage 1: build ----------
FROM node:20-alpine AS builder
WORKDIR /build

# 依赖层：package.json 变更才重装
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund

# 源码层
COPY . .
# vue-tsc + vite build，产物在 dist/
RUN npm run build

# ---------- Stage 2: nginx ----------
FROM nginx:1.27-alpine

# 干掉默认 site，避免和自定义 conf 冲突
RUN rm -f /etc/nginx/conf.d/default.conf

# 静态产物
COPY --from=builder /build/dist /usr/share/nginx/html

# Nginx 配置：HTTPS + 反代 + gzip 等
# 证书通过 docker-compose 挂载 /etc/letsencrypt 目录进来
COPY nginx.conf /etc/nginx/conf.d/rag.conf

# 80 + 443：80 用于 certbot ACME challenge + 301 跳转，443 主服务
EXPOSE 80 443

# 默认 CMD 保持 nginx 官方镜像行为
