<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  House,
  ChatDotSquare,
  Cpu,
  Document,
  DataAnalysis,
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()

const activeKey = computed(() => route.path)

const menuItems = [
  { path: '/', title: '首页', icon: House },
  { path: '/ask', title: 'Chat 问答', icon: ChatDotSquare },
  { path: '/agent', title: 'Agent 演示', icon: Cpu },
  { path: '/docs', title: '知识库', icon: Document },
  { path: '/eval', title: '评估报告', icon: DataAnalysis },
]

function goTo(path: string) {
  router.push(path)
}
</script>

<template>
  <el-container class="app-layout">
    <el-aside width="220px" class="side">
      <div class="logo">
        <span class="logo-title">RAG + Agent</span>
        <span class="logo-sub">面试演示</span>
      </div>
      <el-menu :default-active="activeKey" class="menu" @select="goTo">
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <span class="title">{{ (route.meta.title as string) || '' }}</span>
      </el-header>
      <el-main class="main">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-layout {
  height: 100vh;
}
.side {
  background: #1f2937;
  color: #fff;
  display: flex;
  flex-direction: column;
}
.logo {
  padding: 20px 16px;
  border-bottom: 1px solid #374151;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.logo-title {
  font-size: 18px;
  font-weight: 600;
}
.logo-sub {
  font-size: 12px;
  color: #9ca3af;
}
.menu {
  background: transparent;
  border-right: none;
  flex: 1;
}
.menu :deep(.el-menu-item) {
  color: #d1d5db;
}
.menu :deep(.el-menu-item.is-active) {
  background: #374151;
  color: #fff;
}
.menu :deep(.el-menu-item:hover) {
  background: #374151;
}
.header {
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  align-items: center;
  padding: 0 24px;
}
.title {
  font-size: 16px;
  font-weight: 600;
  color: #111827;
}
.main {
  background: #f9fafb;
  padding: 24px;
  overflow: auto;
}
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
