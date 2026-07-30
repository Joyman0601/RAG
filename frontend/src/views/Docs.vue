<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Upload, View } from '@element-plus/icons-vue'
import { listDocuments, listChunks, type DocumentInfo, type DocumentChunk } from '../api/documents'

const docs = ref<DocumentInfo[]>([])
const loading = ref(false)
const drawerVisible = ref(false)
const drawerDoc = ref<DocumentInfo | null>(null)
const chunks = ref<DocumentChunk[]>([])
const chunksLoading = ref(false)

async function loadDocs() {
  loading.value = true
  try {
    docs.value = await listDocuments()
  } catch {
    // interceptor 已提示
  } finally {
    loading.value = false
  }
}

async function openChunks(row: DocumentInfo) {
  drawerDoc.value = row
  drawerVisible.value = true
  chunksLoading.value = true
  chunks.value = []
  try {
    chunks.value = await listChunks(row.id)
  } catch {
    // ignore
  } finally {
    chunksLoading.value = false
  }
}

function humanSize(bytes?: number): string {
  if (!bytes && bytes !== 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

function formatTime(s?: string): string {
  if (!s) return '-'
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return s
  return d.toLocaleString('zh-CN', { hour12: false })
}

function statusTag(status?: string): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'READY') return 'success'
  if (status === 'PROCESSING') return 'warning'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

const totalChunks = computed(() => chunks.value.length)
const embeddingDim = 4096 // 简历口径：Qwen3-VL-Embedding-8B 4096 维

function onUploadClick() {
  ElMessage.info('演示环境为只读，上传接口已关闭。本地开发可参考 README')
}

onMounted(loadDocs)
</script>

<template>
  <div class="docs-page">
    <el-card class="toolbar" shadow="never">
      <div class="toolbar-inner">
        <div class="left">
          <span class="title">共 {{ docs.length }} 篇文档</span>
          <span class="hint">Embedding 维度：{{ embeddingDim }}（Qwen3-VL-Embedding-8B）</span>
        </div>
        <div class="right">
          <el-tooltip content="演示环境为只读，上传接口已关闭" placement="top">
            <el-button :icon="Upload" disabled @click="onUploadClick">上传文档</el-button>
          </el-tooltip>
          <el-button @click="loadDocs" :loading="loading">刷新</el-button>
        </div>
      </div>
    </el-card>

    <el-card class="table-card" shadow="never">
      <el-table :data="docs" v-loading="loading" stripe style="width: 100%" :row-key="(row: DocumentInfo) => row.id">
        <el-table-column label="文档名" prop="filename" min-width="220">
          <template #default="{ row }">
            <div class="filename-cell">
              <span class="filename">{{ row.filename }}</span>
              <span class="doc-id">{{ row.id }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ row.status || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本" prop="currentVersion" width="80" align="center" />
        <el-table-column label="可见性" prop="visibility" width="120" />
        <el-table-column label="大小" width="100" align="right">
          <template #default="{ row }">{{ humanSize(row.size) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link :icon="View" @click="openChunks(row)">查看切分</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无文档。演示环境需先在本地灌入脱敏文档。" />
        </template>
      </el-table>
    </el-card>

    <el-drawer v-model="drawerVisible" size="55%" :with-header="false">
      <template v-if="drawerDoc">
        <div class="drawer-head">
          <h3>{{ drawerDoc.filename }}</h3>
          <div class="meta">
            <el-tag size="small" :type="statusTag(drawerDoc.status)">{{ drawerDoc.status }}</el-tag>
            <span>版本 v{{ drawerDoc.currentVersion }}</span>
            <span>{{ humanSize(drawerDoc.size) }}</span>
            <span>{{ totalChunks }} 个 chunk</span>
            <span>Embedding {{ embeddingDim }} 维</span>
          </div>
        </div>
        <el-divider style="margin: 12px 0" />
        <div v-loading="chunksLoading" class="chunks-list">
          <el-empty v-if="!chunksLoading && !chunks.length" description="该文档暂无 chunk" />
          <div v-for="c in chunks" :key="c.chunkId" class="chunk-card">
            <div class="chunk-head">
              <el-tag size="small" type="info">chunk {{ c.chunkIndex }}</el-tag>
              <span v-if="c.modality && c.modality !== 'TEXT'" class="modality">{{ c.modality }}</span>
              <span v-if="c.parentId" class="parent">parent: {{ c.parentId }}</span>
              <span class="chunk-id">{{ c.chunkId }}</span>
            </div>
            <div class="chunk-content">{{ c.content }}</div>
          </div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.docs-page { display: flex; flex-direction: column; gap: 12px; }
.toolbar :deep(.el-card__body) { padding: 12px 16px; }
.toolbar-inner { display: flex; justify-content: space-between; align-items: center; }
.left { display: flex; flex-direction: column; gap: 4px; }
.title { font-size: 14px; color: #111827; font-weight: 500; }
.hint { font-size: 12px; color: #6b7280; }
.right { display: flex; gap: 8px; }
.filename-cell { display: flex; flex-direction: column; gap: 2px; }
.filename { font-weight: 500; color: #111827; }
.doc-id { font-size: 11px; color: #9ca3af; font-family: ui-monospace, monospace; }
.drawer-head { padding: 16px 20px 0; }
.drawer-head h3 { margin: 0 0 8px; font-size: 16px; }
.meta { display: flex; gap: 12px; flex-wrap: wrap; font-size: 12px; color: #6b7280; align-items: center; }
.chunks-list { padding: 0 20px 20px; }
.chunk-card { border: 1px solid #e5e7eb; border-radius: 6px; padding: 10px 12px; margin-bottom: 10px; background: #fafafa; }
.chunk-head { display: flex; gap: 12px; align-items: center; font-size: 12px; color: #6b7280; margin-bottom: 6px; flex-wrap: wrap; }
.chunk-id { font-family: ui-monospace, monospace; color: #9ca3af; margin-left: auto; }
.modality, .parent { color: #6366f1; }
.chunk-content { font-size: 13px; line-height: 1.6; color: #374151; white-space: pre-wrap; word-break: break-word; }
</style>
