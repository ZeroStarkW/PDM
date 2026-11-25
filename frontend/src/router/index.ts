import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AppLayout from '@/layouts/AppLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    { path: '/', component: AppLayout, redirect: '/dashboard', children: [
      { path: 'dashboard', component: () => import('@/views/DashboardView.vue') },
      { path: 'workbench', component: () => import('@/views/WorkbenchView.vue') },
      { path: 'devices', component: () => import('@/views/DevicesView.vue') },
      { path: 'devices/:id', component: () => import('@/views/DeviceDetailView.vue') },
      { path: 'diagnostic-rules', component: () => import('@/views/DiagnosticRulesView.vue') },
      { path: 'alerts', component: () => import('@/views/AlertsView.vue') },
      { path: 'work-orders', component: () => import('@/views/WorkOrdersView.vue') },
      { path: 'maintenance', component: () => import('@/views/MaintenanceView.vue') },
      { path: 'baseline', component: () => import('@/views/BaselineView.vue') },
      { path: 'points/:id', component: () => import('@/views/PointDetailView.vue') },
    ] },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.loadCurrentUser()
  if (!to.meta.public && !auth.authenticated) return '/login'
  if (to.path === '/login' && auth.authenticated) return '/dashboard'
})

export default router
