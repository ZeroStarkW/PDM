<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/ui'

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)
const auth = useAuthStore()
const router = useRouter()
const { theme, toggleTheme } = useTheme()

async function submit() {
  loading.value = true; error.value = ''
  try { await auth.login(username.value, password.value); await router.push('/dashboard') }
  catch (e) { error.value = e instanceof Error ? e.message : '登录失败，请检查账号信息' }
  finally { loading.value = false }
}
</script>

<template>
  <div class="login-screen">
    <button class="theme-toggle login-theme-toggle" @click="toggleTheme"><span aria-hidden="true">{{ theme === 'light' ? '☾' : '☀' }}</span>{{ theme === 'light' ? '深色主题' : '亮色主题' }}</button>
    <div class="login-orbit orbit-one"></div><div class="login-orbit orbit-two"></div>
    <section class="login-card">
      <div class="brand login-brand"><div class="brand-mark">PD</div><div><strong>VIBRA</strong><small>多模态设备预测性维护</small></div></div>
      <div class="login-copy"><div class="eyebrow">PLANT RELIABILITY · 01</div><h1>让设备状态<br><span>始终清晰可见</span></h1><p>登录后查看设备健康、趋势变化与待处理告警。</p></div>
      <form class="login-form" @submit.prevent="submit">
        <label>用户名<input v-model="username" autocomplete="username" placeholder="请输入用户名" required /></label>
        <label>密码<input v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" required /></label>
        <div v-if="error" class="inline-error">{{ error }}</div>
        <button class="primary-button full-button" :disabled="loading">{{ loading ? '正在登录…' : '登录工作台' }}<span>→</span></button>
      </form>
      <footer>工业设备状态监测 · 安全会话已启用</footer>
    </section>
    <aside class="login-side"><div class="side-grid"></div><div class="side-ring"><span></span><i></i></div><div class="side-note"><small>CONTINUOUS MONITORING</small><b>从振动特征到维护闭环</b><p>健康趋势 · 机理诊断 · 工单跟踪</p></div><div class="side-foot">PREDICTIVE MAINTENANCE PLATFORM</div></aside>
  </div>
</template>
