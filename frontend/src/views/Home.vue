<script setup lang="ts">
const highlights = [
  { key: '召回', value: '69.2% → 88.5%', desc: '混合检索 Top-3 命中率（BM25 + 向量 + RRF）' },
  { key: '精度', value: '0.653 → 0.889', desc: 'context precision（评估驱动技术选型）' },
  { key: '忠实度', value: '0.85 – 0.95', desc: 'faithfulness 稳定区间（RAGAS）' },
]

const techStack = [
  'Java 17', 'Spring Boot 3', 'PostgreSQL + pgvector (HNSW)',
  'text-embedding-v4 (1024d)', 'BM25 + Vector Hybrid (RRF)',
  'bge-reranker-v2-m3', 'RAGAS 评估', 'Agent / Tool Calling', 'Langfuse',
]
</script>

<template>
  <div class="home">
    <el-card class="hero" shadow="never">
      <h1>企业知识库 RAG + Agent 问答系统</h1>
      <p class="sub">面向企业内部文档的检索增强问答，兼顾 RAG 检索质量与 Agent 工具调用安全</p>
      <div class="stack">
        <el-tag v-for="t in techStack" :key="t" class="stack-tag" type="info" effect="plain">{{ t }}</el-tag>
      </div>
    </el-card>

    <el-row :gutter="16" class="highlights">
      <el-col v-for="h in highlights" :key="h.key" :span="8">
        <el-card class="metric" shadow="hover">
          <div class="metric-key">{{ h.key }}</div>
          <div class="metric-value">{{ h.value }}</div>
          <div class="metric-desc">{{ h.desc }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="entry" shadow="never">
      <h3>快速入口</h3>
      <div class="entries">
        <router-link to="/ask" class="entry-link">Chat 问答 →</router-link>
        <router-link to="/agent" class="entry-link">Agent 演示（含 HITL）→</router-link>
        <router-link to="/docs" class="entry-link">知识库文档 →</router-link>
        <router-link to="/eval" class="entry-link">RAGAS 评估报告 →</router-link>
      </div>
    </el-card>

    <el-alert type="info" :closable="false" class="notice">
      <template #title>
        <strong>演示环境说明：</strong>
        知识库为脱敏示例；上传接口已关闭；LLM 调用有每日上限；如遇限流请查看录屏。
      </template>
    </el-alert>
  </div>
</template>

<style scoped>
.home { display: flex; flex-direction: column; gap: 16px; }
.hero { background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%); color: #fff; border: none; }
.hero :deep(.el-card__body) { padding: 32px; }
.hero h1 { margin: 0 0 8px; font-size: 24px; }
.sub { margin: 0 0 16px; opacity: 0.9; }
.stack { display: flex; flex-wrap: wrap; gap: 8px; }
.stack-tag { background: rgba(255, 255, 255, 0.15); border-color: rgba(255, 255, 255, 0.3); color: #fff; }
.highlights { margin-top: 4px; }
.metric-key { color: #6b7280; font-size: 13px; }
.metric-value { font-size: 22px; font-weight: 700; color: #111827; margin: 6px 0; }
.metric-desc { font-size: 12px; color: #6b7280; }
.entry h3 { margin: 0 0 12px; font-size: 15px; color: #111827; }
.entries { display: flex; flex-wrap: wrap; gap: 16px; }
.entry-link { color: #6366f1; text-decoration: none; font-size: 14px; }
.entry-link:hover { text-decoration: underline; }
.notice { border: none; }
</style>
