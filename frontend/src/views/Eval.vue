<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, RadarChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import VChart from 'vue-echarts'
import { getRagEval, type RagEvalResponse } from '../api/eval'

use([
  CanvasRenderer,
  BarChart,
  RadarChart,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
])

const loading = ref(false)
const data = ref<RagEvalResponse | null>(null)
const error = ref('')
const onlySearch = ref(false)

async function loadEval() {
  loading.value = true
  error.value = ''
  try {
    data.value = await getRagEval(onlySearch.value)
  } catch (e) {
    error.value = (e as Error).message || '加载失败：可能未跑过评估，先在后端执行 POST /api/rag/eval/run'
    data.value = null
  } finally {
    loading.value = false
  }
}

const summary = computed(() => data.value?.summary)

// 用户简历口径（三次评估的对比数据，实事求是版）
// 这里展示 A/B 对比：本次实测 vs 简历招牌数据
const barOption = computed(() => {
  const s = summary.value
  const current = s
    ? [
        Number(s.averageHitAtK.toFixed(3)),
        Number(s.averageRecallAtK.toFixed(3)),
        Number(s.averageMrr.toFixed(3)),
      ]
    : [0, 0, 0]
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { data: ['本次评估'], top: 0 },
    grid: { left: 40, right: 20, bottom: 30, top: 30 },
    xAxis: { type: 'category', data: ['Hit@K', 'Recall@K', 'MRR'] },
    yAxis: { type: 'value', min: 0, max: 1 },
    series: [
      {
        name: '本次评估',
        type: 'bar',
        data: current,
        itemStyle: { color: '#6366f1' },
        label: { show: true, position: 'top', formatter: '{c}' },
      },
    ],
  }
})

const radarOption = computed(() => {
  const s = summary.value
  const values = s
    ? [s.averageHitAtK, s.averageRecallAtK, s.averageMrr, Math.min(1, s.averageLatencyMs / 3000)]
    : [0, 0, 0, 0]
  return {
    tooltip: {},
    radar: {
      indicator: [
        { name: 'Hit@K', max: 1 },
        { name: 'Recall@K', max: 1 },
        { name: 'MRR', max: 1 },
        { name: '延迟归一化 (1-x/3s)', max: 1 },
      ],
      radius: '60%',
    },
    series: [
      {
        type: 'radar',
        data: [{ value: values, name: '本次评估', areaStyle: { opacity: 0.3 } }],
        lineStyle: { color: '#6366f1' },
        itemStyle: { color: '#6366f1' },
      },
    ],
  }
})

function formatMs(v?: number) {
  if (!v && v !== 0) return '-'
  return `${v.toFixed(0)} ms`
}

onMounted(loadEval)
</script>

<template>
  <div class="eval-page">
    <el-card class="header" shadow="never">
      <div class="header-inner">
        <div>
          <h2 class="title">RAGAS 评估报告</h2>
          <p class="subtitle">
            评估驱动技术选型：每次调整检索策略/prompt 都跑同一份评估集，用数据说话。
          </p>
        </div>
        <div class="controls">
          <el-checkbox v-model="onlySearch" @change="loadEval">仅检索指标（不含 LLM 答案质量）</el-checkbox>
          <el-button type="primary" :loading="loading" @click="loadEval">刷新数据</el-button>
        </div>
      </div>
    </el-card>

    <el-alert v-if="error" :title="error" type="warning" show-icon :closable="false" />

    <div v-if="summary" class="metrics-cards">
      <el-row :gutter="12">
        <el-col :span="6">
          <el-card class="metric" shadow="never">
            <div class="metric-label">Hit@K</div>
            <div class="metric-value">{{ summary.averageHitAtK.toFixed(3) }}</div>
            <div class="metric-hint">Top-K 命中率</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card class="metric" shadow="never">
            <div class="metric-label">Recall@K</div>
            <div class="metric-value">{{ summary.averageRecallAtK.toFixed(3) }}</div>
            <div class="metric-hint">召回率</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card class="metric" shadow="never">
            <div class="metric-label">MRR</div>
            <div class="metric-value">{{ summary.averageMrr.toFixed(3) }}</div>
            <div class="metric-hint">平均倒数排名</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card class="metric" shadow="never">
            <div class="metric-label">平均延迟</div>
            <div class="metric-value">{{ formatMs(summary.averageLatencyMs) }}</div>
            <div class="metric-hint">{{ summary.total }} 条用例</div>
          </el-card>
        </el-col>
      </el-row>
    </div>

    <el-row v-if="summary" :gutter="12">
      <el-col :span="12">
        <el-card class="chart-card" shadow="never">
          <div class="chart-title">核心指标</div>
          <VChart :option="barOption" style="height: 300px" autoresize />
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card class="chart-card" shadow="never">
          <div class="chart-title">多维雷达</div>
          <VChart :option="radarOption" style="height: 300px" autoresize />
        </el-card>
      </el-col>
    </el-row>

    <el-card v-if="data && data.results?.length" class="cases-card" shadow="never">
      <div class="cases-head">
        <span class="chart-title">用例明细 ({{ data.results.length }} 条)</span>
        <span v-if="data.caseFile" class="case-file">来源：{{ data.caseFile }}</span>
      </div>
      <el-table :data="data.results" v-loading="loading" stripe max-height="500">
        <el-table-column label="Case ID" prop="caseId" width="120" />
        <el-table-column label="问题" prop="question" min-width="240" show-overflow-tooltip />
        <el-table-column label="Hit@K" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.hitAtK ? 'success' : 'danger'" size="small">
              {{ row.hitAtK ? '✓' : '✗' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Recall@K" width="100" align="right">
          <template #default="{ row }">{{ row.recallAtK.toFixed(3) }}</template>
        </el-table-column>
        <el-table-column label="MRR" width="90" align="right">
          <template #default="{ row }">{{ row.mrr.toFixed(3) }}</template>
        </el-table-column>
        <el-table-column label="有答案" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.hasAnswer ? 'success' : 'info'" size="small">
              {{ row.hasAnswer ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="含期望短语" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="row.answerContainsExpectedPhrase ? 'success' : 'warning'" size="small">
              {{ row.answerContainsExpectedPhrase ? '✓' : '✗' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="延迟" width="90" align="right">
          <template #default="{ row }">{{ row.latencyMs }}ms</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-empty v-if="!summary && !loading && !error" description="暂无评估数据。请先运行 POST /api/rag/eval/run" />
  </div>
</template>

<style scoped>
.eval-page { display: flex; flex-direction: column; gap: 12px; }
.header :deep(.el-card__body) { padding: 20px; }
.header-inner { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.title { margin: 0 0 4px; font-size: 18px; color: #111827; }
.subtitle { margin: 0; color: #6b7280; font-size: 13px; }
.controls { display: flex; align-items: center; gap: 12px; }
.metrics-cards { }
.metric :deep(.el-card__body) { padding: 16px; text-align: center; }
.metric-label { font-size: 12px; color: #6b7280; }
.metric-value { font-size: 24px; font-weight: 700; color: #111827; margin: 6px 0; font-family: ui-monospace, monospace; }
.metric-hint { font-size: 11px; color: #9ca3af; }
.chart-card :deep(.el-card__body) { padding: 16px; }
.chart-title { font-size: 14px; font-weight: 600; color: #111827; margin-bottom: 8px; }
.cases-card :deep(.el-card__body) { padding: 16px; }
.cases-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.case-file { font-size: 12px; color: #9ca3af; font-family: ui-monospace, monospace; }
</style>
