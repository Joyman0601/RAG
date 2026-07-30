<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { postRagAsk, type RetrievalMode, type RagAskResponse } from '../api/rag'

interface Panel {
  mode: RetrievalMode
  title: string
  desc: string
  loading: boolean
  resp?: RagAskResponse
  error?: string
  totalMs?: number
}

const question = ref('')
const running = ref(false)

const panels = ref<Panel[]>([
  {
    mode: 'VECTOR',
    title: '纯向量',
    desc: '仅向量检索（cosine similarity）',
    loading: false,
  },
  {
    mode: 'HYBRID',
    title: '混合检索',
    desc: 'BM25 + 向量，RRF 融合',
    loading: false,
  },
  {
    mode: 'HYBRID_RERANK',
    title: '混合 + 精排',
    desc: 'RRF 融合后再 bge-reranker 交叉编码精排',
    loading: false,
  },
])

const canAsk = computed(() => question.value.trim().length > 0 && !running.value)

const suggestions = [
  '企业年假的申请流程是什么？',
  '出差报销的额度上限是多少？',
  '员工离职后知识产权归属如何处理？',
]

async function askAll() {
  if (!canAsk.value) return
  const q = question.value.trim()
  running.value = true
  panels.value.forEach((p) => {
    p.loading = true
    p.resp = undefined
    p.error = undefined
    p.totalMs = undefined
  })

  await Promise.all(
    panels.value.map(async (p) => {
      const started = performance.now()
      try {
        p.resp = await postRagAsk({ question: q, mode: p.mode })
      } catch (e) {
        p.error = (e as Error).message || '请求失败'
      } finally {
        p.totalMs = Math.round(performance.now() - started)
        p.loading = false
      }
    }),
  )
  running.value = false
}

function fillSuggestion(s: string) {
  question.value = s
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    askAll()
  }
}

function scoreColor(score: number): string {
  if (score >= 0.7) return '#10b981'
  if (score >= 0.4) return '#f59e0b'
  return '#ef4444'
}

function showNoAnswer(p: Panel) {
  ElMessage.info(`${p.title}：本次未命中知识库，返回兜底回答`)
}
</script>

<template>
  <div class="ask-page">
    <el-card class="header-card" shadow="never">
      <h2 class="title">三模式检索对比</h2>
      <p class="subtitle">
        同一个问题并行跑三种检索策略，实景对比召回质量、答案与耗时。这是「评估驱动技术选型」在演示环境的可视化。
      </p>
      <div class="input-row">
        <el-input
          v-model="question"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="输入问题，回车提交，Shift+Enter 换行"
          @keydown="onKeydown"
        />
        <el-button type="primary" :icon="Search" :loading="running" :disabled="!canAsk" @click="askAll" size="large">
          三模式并行提问
        </el-button>
      </div>
      <div class="suggestions">
        <span class="sug-label">试试：</span>
        <el-tag
          v-for="s in suggestions"
          :key="s"
          class="sug"
          type="info"
          effect="plain"
          @click="fillSuggestion(s)"
        >{{ s }}</el-tag>
      </div>
    </el-card>

    <el-row :gutter="12" class="panels">
      <el-col v-for="p in panels" :key="p.mode" :span="8">
        <el-card class="panel" shadow="never">
          <div class="panel-head">
            <div>
              <div class="panel-title">{{ p.title }}</div>
              <div class="panel-desc">{{ p.desc }}</div>
            </div>
            <el-tag size="small" type="info">{{ p.mode }}</el-tag>
          </div>

          <el-divider style="margin: 12px 0" />

          <div v-if="p.loading" class="loading">
            <el-icon class="is-loading" :size="20"><Search /></el-icon>
            <span>检索中…</span>
          </div>

          <div v-else-if="p.error" class="error">
            <el-alert :title="p.error" type="error" :closable="false" />
          </div>

          <div v-else-if="!p.resp" class="empty">
            <el-empty description="等待提问" :image-size="80" />
          </div>

          <div v-else class="result">
            <div class="metrics">
              <el-tag size="small">总耗时 {{ p.totalMs }}ms</el-tag>
              <el-tag size="small" type="info" v-if="p.resp.embeddingDurationMs !== undefined">
                embed {{ p.resp.embeddingDurationMs }}ms
              </el-tag>
              <el-tag size="small" type="info" v-if="p.resp.searchDurationMs !== undefined">
                search {{ p.resp.searchDurationMs }}ms
              </el-tag>
              <el-tag size="small" type="warning" v-if="p.resp.tokenUsage?.totalTokens">
                {{ p.resp.tokenUsage.totalTokens }} tokens
              </el-tag>
            </div>

            <div class="answer-section">
              <div class="section-title">答案</div>
              <div class="answer" :class="{ 'no-answer': !p.resp.sources?.length }">
                {{ p.resp.answer }}
                <el-button
                  v-if="!p.resp.sources?.length"
                  size="small"
                  link
                  type="warning"
                  @click="showNoAnswer(p)"
                >无兜底原因</el-button>
              </div>
            </div>

            <div v-if="p.resp.sources?.length" class="sources-section">
              <div class="section-title">Sources ({{ p.resp.sources.length }})</div>
              <div v-for="(s, i) in p.resp.sources" :key="i" class="source-item">
                <div class="source-head">
                  <span class="source-idx">[{{ s.index ?? i + 1 }}]</span>
                  <span class="source-name">{{ s.documentName || s.filename || s.title || '未命名文档' }}</span>
                  <span
                    class="source-score"
                    :style="{ color: scoreColor(s.score) }"
                  >{{ s.score.toFixed(3) }}</span>
                </div>
                <div class="source-snippet" v-if="s.snippet">{{ s.snippet }}</div>
                <div class="source-meta">
                  chunk {{ s.chunkIndex ?? '?' }} · {{ s.chunkId }}
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.ask-page { display: flex; flex-direction: column; gap: 12px; }
.header-card :deep(.el-card__body) { padding: 20px; }
.title { margin: 0 0 4px; font-size: 18px; color: #111827; }
.subtitle { margin: 0 0 16px; color: #6b7280; font-size: 13px; }
.input-row { display: flex; gap: 12px; align-items: flex-end; }
.input-row :deep(.el-textarea) { flex: 1; }
.suggestions { margin-top: 12px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sug-label { font-size: 12px; color: #9ca3af; }
.sug { cursor: pointer; }
.sug:hover { background: #e0e7ff; color: #4338ca; border-color: #a5b4fc; }
.panels { margin-top: 0; }
.panel { height: 100%; min-height: 400px; }
.panel :deep(.el-card__body) { padding: 16px; height: 100%; display: flex; flex-direction: column; }
.panel-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.panel-title { font-size: 15px; font-weight: 600; color: #111827; }
.panel-desc { font-size: 12px; color: #6b7280; margin-top: 2px; }
.loading, .empty { flex: 1; display: flex; align-items: center; justify-content: center; gap: 8px; color: #9ca3af; }
.error { flex: 1; }
.result { flex: 1; display: flex; flex-direction: column; gap: 12px; overflow: hidden; }
.metrics { display: flex; gap: 6px; flex-wrap: wrap; }
.section-title { font-size: 12px; font-weight: 600; color: #4b5563; margin-bottom: 6px; }
.answer { font-size: 13px; line-height: 1.6; color: #111827; white-space: pre-wrap; word-break: break-word; padding: 8px; background: #f9fafb; border-radius: 6px; max-height: 180px; overflow-y: auto; }
.answer.no-answer { color: #92400e; background: #fef3c7; }
.sources-section { flex: 1; overflow-y: auto; }
.source-item { border: 1px solid #e5e7eb; border-radius: 6px; padding: 8px 10px; margin-bottom: 6px; }
.source-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; font-size: 12px; }
.source-idx { color: #6366f1; font-weight: 600; }
.source-name { flex: 1; color: #111827; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.source-score { font-family: ui-monospace, monospace; font-weight: 600; }
.source-snippet { font-size: 12px; color: #6b7280; line-height: 1.5; margin-bottom: 4px; max-height: 60px; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; }
.source-meta { font-size: 11px; color: #9ca3af; font-family: ui-monospace, monospace; }
</style>
