<script setup lang="ts">
import { ref, nextTick, useTemplateRef } from 'vue'
import { ElMessage } from 'element-plus'
import { Cpu, Search, Document, WarningFilled } from '@element-plus/icons-vue'
import {
  postAgentLoop,
  postAgentConfirm,
  type AgentLoopResponse,
  type AgentStep,
} from '../api/agent'

interface UiMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
  steps?: AgentStep[]
  requiresConfirmation?: boolean
  confirmationId?: string
  stopReason?: string
  requestId?: string
  loading?: boolean
  confirmed?: boolean
}

const conversationId = ref<string>('')
const input = ref('')
const sending = ref(false)
const messages = ref<UiMessage[]>([])
const listRef = useTemplateRef<HTMLDivElement>('listRef')

interface Scenario {
  key: string
  title: string
  desc: string
  icon: unknown
  color: string
  prompt: string
}

const scenarios: Scenario[] = [
  {
    key: 'search',
    title: 'search_knowledge_base',
    desc: '低危工具 · 自动执行',
    icon: Search,
    color: '#10b981',
    prompt: '公司请年假的流程是什么？',
  },
  {
    key: 'query',
    title: 'query_order',
    desc: '中危工具 · 业务级授权',
    icon: Document,
    color: '#6366f1',
    prompt: '帮我查一下订单 O-1001 的状态',
  },
  {
    key: 'cancel',
    title: 'cancel_order',
    desc: '高危工具 · HITL 人工确认',
    icon: WarningFilled,
    color: '#ef4444',
    prompt: '取消订单 O-1001',
  },
]

function scrollBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

async function send(text?: string) {
  const q = (text ?? input.value).trim()
  if (!q || sending.value) return
  messages.value.push({ role: 'user', content: q })
  input.value = ''
  const assistant: UiMessage = { role: 'assistant', content: '', loading: true }
  messages.value.push(assistant)
  scrollBottom()
  sending.value = true
  try {
    const resp: AgentLoopResponse = await postAgentLoop({
      conversationId: conversationId.value || undefined,
      message: q,
    })
    if (resp.conversationId) conversationId.value = resp.conversationId
    assistant.content = resp.answer || '（无回答）'
    assistant.steps = resp.steps
    assistant.requiresConfirmation = resp.requiresConfirmation
    assistant.confirmationId = resp.confirmationId
    assistant.stopReason = resp.stopReason
    assistant.requestId = resp.requestId
    assistant.loading = false
  } catch {
    assistant.content = '请求失败，请稍后重试'
    assistant.loading = false
  } finally {
    sending.value = false
    scrollBottom()
  }
}

async function confirm(msg: UiMessage) {
  if (!msg.confirmationId) return
  try {
    const result = await postAgentConfirm({ confirmationId: msg.confirmationId })
    ElMessage.success('高危工具已确认执行（HITL 通过）')
    messages.value.push({
      role: 'system',
      content: `HITL 确认通过，工具执行结果：\n${JSON.stringify(result, null, 2)}`,
    })
    msg.confirmed = true
    msg.requiresConfirmation = false
  } catch {
    // interceptor 已提示
  } finally {
    scrollBottom()
  }
}

function resetConversation() {
  conversationId.value = ''
  messages.value = []
  ElMessage.info('已开始新会话')
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}
</script>

<template>
  <div class="agent-page">
    <el-card class="header-card" shadow="never">
      <h2 class="title">
        <el-icon><Cpu /></el-icon>
        Agent Loop 演示（ReAct + HITL）
      </h2>
      <p class="subtitle">
        观察 Agent 的完整思考链：thought → tool call → observation → 高危工具触发 HITL 人工确认 → 恢复执行 → 最终 answer。
        三层授权 + 6 道防线的可视化。
      </p>
      <div class="scenarios">
        <div
          v-for="s in scenarios"
          :key="s.key"
          class="scenario-card"
          @click="send(s.prompt)"
        >
          <el-icon :size="20" :color="s.color"><component :is="s.icon" /></el-icon>
          <div class="scenario-body">
            <div class="scenario-title">{{ s.title }}</div>
            <div class="scenario-desc">{{ s.desc }}</div>
            <div class="scenario-prompt">"{{ s.prompt }}"</div>
          </div>
        </div>
      </div>
    </el-card>

    <el-card class="chat-card" shadow="never">
      <div class="chat-toolbar">
        <div class="cid">
          <span class="label">Conversation:</span>
          <span class="value">{{ conversationId || '（新对话）' }}</span>
        </div>
        <el-button size="small" @click="resetConversation">新会话</el-button>
      </div>

      <div ref="listRef" class="messages">
        <div v-if="!messages.length" class="empty-hint">
          点击上方场景卡片开始，或直接在下方输入问题
        </div>
        <div v-for="(m, idx) in messages" :key="idx" :class="['msg', m.role]">
          <div class="bubble">
            <div v-if="m.loading" class="loading">
              <el-icon class="is-loading"><Search /></el-icon>
              Agent 思考中…
            </div>
            <template v-else>
              <div class="content">{{ m.content }}</div>

              <div v-if="m.steps && m.steps.length" class="steps">
                <el-collapse>
                  <el-collapse-item
                    :title="`Agent 执行时间线 (${m.steps.length} 步)`"
                    :name="`s-${idx}`"
                  >
                    <div v-for="(s, i) in m.steps" :key="i" class="step">
                      <div class="step-head">
                        <el-tag size="small" type="info">Step {{ s.stepIndex ?? i + 1 }}</el-tag>
                        <span class="step-action">{{ s.action || s.toolName || 'thought' }}</span>
                      </div>
                      <pre v-if="s.thought" class="step-block thought">💭 {{ s.thought }}</pre>
                      <pre v-if="s.toolName" class="step-block tool">🔧 {{ s.toolName }}({{ s.toolArguments }})</pre>
                      <pre v-if="s.toolResult" class="step-block result">📤 {{ s.toolResult }}</pre>
                      <pre v-if="s.observation" class="step-block obs">👀 {{ s.observation }}</pre>
                    </div>
                  </el-collapse-item>
                </el-collapse>
              </div>

              <div v-if="m.requiresConfirmation" class="hitl">
                <el-alert
                  type="warning"
                  :closable="false"
                  show-icon
                >
                  <template #title>
                    <strong>检测到高危工具调用 · HITL 拦截</strong>
                  </template>
                  <div class="hitl-body">
                    Agent 判断需要人工确认才能继续执行。<br />
                    confirmationId: <code>{{ m.confirmationId }}</code>
                  </div>
                </el-alert>
                <div class="hitl-actions">
                  <el-button type="danger" @click="confirm(m)">确认执行</el-button>
                  <el-button @click="m.requiresConfirmation = false">拒绝</el-button>
                </div>
              </div>

              <div v-if="m.confirmed" class="confirmed">
                <el-tag type="success" size="small">✓ HITL 已确认</el-tag>
              </div>

              <div v-if="m.stopReason || m.requestId" class="meta">
                <span v-if="m.stopReason">stopReason: {{ m.stopReason }}</span>
                <span v-if="m.requestId">requestId: {{ m.requestId }}</span>
              </div>
            </template>
          </div>
        </div>
      </div>

      <div class="input-bar">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="输入问题，Enter 发送，Shift+Enter 换行"
          @keydown="onKeydown"
        />
        <el-button type="primary" :loading="sending" @click="send()">发送</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.agent-page { display: flex; flex-direction: column; gap: 12px; height: calc(100vh - 112px); }
.header-card :deep(.el-card__body) { padding: 16px 20px; }
.title { margin: 0 0 4px; font-size: 18px; color: #111827; display: flex; align-items: center; gap: 8px; }
.subtitle { margin: 0 0 12px; color: #6b7280; font-size: 13px; }
.scenarios { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.scenario-card {
  display: flex; gap: 10px; padding: 12px; border-radius: 8px;
  border: 1px solid #e5e7eb; background: #fafafa; cursor: pointer;
  transition: all 0.15s;
}
.scenario-card:hover { border-color: #6366f1; background: #eef2ff; transform: translateY(-1px); }
.scenario-body { flex: 1; }
.scenario-title { font-family: ui-monospace, monospace; font-size: 13px; font-weight: 600; color: #111827; }
.scenario-desc { font-size: 11px; color: #6b7280; margin: 2px 0 6px; }
.scenario-prompt { font-size: 12px; color: #4b5563; font-style: italic; }

.chat-card { flex: 1; display: flex; flex-direction: column; overflow: hidden; }
.chat-card :deep(.el-card__body) { flex: 1; display: flex; flex-direction: column; padding: 0; overflow: hidden; }
.chat-toolbar { display: flex; justify-content: space-between; align-items: center; padding: 8px 16px; border-bottom: 1px solid #e5e7eb; background: #f9fafb; }
.cid { font-size: 12px; color: #6b7280; }
.cid .label { margin-right: 6px; }
.cid .value { font-family: ui-monospace, monospace; color: #374151; }
.messages { flex: 1; overflow-y: auto; padding: 16px; }
.empty-hint { color: #9ca3af; font-size: 13px; padding: 40px; text-align: center; }
.msg { display: flex; margin-bottom: 12px; }
.msg.user { justify-content: flex-end; }
.msg.assistant { justify-content: flex-start; }
.msg.system { justify-content: center; }
.bubble { max-width: 82%; padding: 10px 14px; border-radius: 10px; font-size: 14px; line-height: 1.6; }
.msg.user .bubble { background: #6366f1; color: #fff; }
.msg.assistant .bubble { background: #fff; border: 1px solid #e5e7eb; color: #111827; }
.msg.system .bubble { background: #fef3c7; color: #92400e; font-family: ui-monospace, monospace; font-size: 12px; white-space: pre-wrap; max-width: 90%; }
.content { white-space: pre-wrap; word-break: break-word; }
.loading { color: #9ca3af; display: flex; align-items: center; gap: 6px; }
.steps { margin-top: 10px; }
.step { margin-bottom: 8px; }
.step-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.step-action { font-family: ui-monospace, monospace; font-size: 12px; color: #4b5563; }
.step-block { padding: 8px 10px; border-radius: 6px; font-size: 12px; white-space: pre-wrap; margin: 2px 0; max-height: 220px; overflow: auto; font-family: ui-monospace, monospace; }
.step-block.thought { background: #eff6ff; color: #1e40af; }
.step-block.tool { background: #f3f4f6; color: #4b5563; }
.step-block.result { background: #f0fdf4; color: #166534; }
.step-block.obs { background: #fefce8; color: #854d0e; }
.hitl { margin-top: 10px; }
.hitl-body { margin-top: 4px; font-size: 12px; color: #78350f; }
.hitl-body code { background: #fff; padding: 1px 6px; border-radius: 3px; font-size: 11px; }
.hitl-actions { display: flex; gap: 8px; margin-top: 8px; }
.confirmed { margin-top: 8px; }
.meta { margin-top: 8px; font-size: 11px; color: #9ca3af; display: flex; gap: 12px; flex-wrap: wrap; }
.input-bar { border-top: 1px solid #e5e7eb; padding: 12px; display: flex; gap: 8px; align-items: flex-end; background: #fff; }
.input-bar :deep(.el-textarea) { flex: 1; }
</style>
