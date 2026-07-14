import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/env-config' },
    { path: '/env-config', component: () => import('@/views/EnvConfigView.vue') },
    { path: '/ai-config', component: () => import('@/views/AiConfigView.vue') },
    { path: '/blacklist', component: () => import('@/views/BlacklistView.vue') },
    { path: '/boss', component: () => import('@/views/boss/BossView.vue') },
    { path: '/boss/analysis', component: () => import('@/views/boss/BossAnalysisView.vue') },
    { path: '/liepin', component: () => import('@/views/liepin/LiepinView.vue') },
    { path: '/liepin/analysis', component: () => import('@/views/liepin/LiepinAnalysisView.vue') },
    { path: '/51job', component: () => import('@/views/job51/Job51View.vue') },
    { path: '/51job/analysis', component: () => import('@/views/job51/Job51AnalysisView.vue') },
    { path: '/zhilian', component: () => import('@/views/zhilian/ZhilianView.vue') },
    { path: '/zhilian/analysis', component: () => import('@/views/zhilian/ZhilianAnalysisView.vue') },
  ],
})

export default router
