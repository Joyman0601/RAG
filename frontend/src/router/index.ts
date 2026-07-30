import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('../layouts/AppLayout.vue'),
    children: [
      { path: '', name: 'home', component: () => import('../views/Home.vue'), meta: { title: '首页' } },
      { path: 'ask', name: 'ask', component: () => import('../views/Ask.vue'), meta: { title: 'Chat 问答' } },
      { path: 'agent', name: 'agent', component: () => import('../views/Agent.vue'), meta: { title: 'Agent 演示' } },
      { path: 'docs', name: 'docs', component: () => import('../views/Docs.vue'), meta: { title: '知识库' } },
      { path: 'eval', name: 'eval', component: () => import('../views/Eval.vue'), meta: { title: '评估报告' } },
    ],
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})
