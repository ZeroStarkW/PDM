<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useTheme, useUiDialog, type DialogFieldOption } from '@/composables/ui'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const { theme, toggleTheme } = useTheme()
const dialog = useUiDialog()
const dialogEl = ref<HTMLElement | null>(null)
let previousFocus: HTMLElement | null = null
const pageTitle = computed(() => ({
  '/dashboard': '全厂健康总览', '/devices': '设备与测点', '/alerts': '告警中心',
  '/work-orders': '维修工单', '/baseline': '基线管理',
  '/maintenance': '预防性维护',
  '/diagnostic-rules': '主导因素管理',
  '/workbench': '多模态专家诊断工作站',
} as Record<string, string>)[route.path]
  || (route.path.startsWith('/devices/') ? '设备健康详情' : route.path.startsWith('/points/') ? '测点趋势详情' : '设备健康总览'))

watch(dialog.isOpen, async (open) => {
  if (open) {
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await nextTick()
    const root = dialogEl.value
    const firstField = root?.querySelector<HTMLElement>('.dialog-fields input, .dialog-fields select, .dialog-fields textarea')
    const target = firstField ?? root?.querySelector<HTMLElement>('.dialog-actions button, .dialog-close')
    target?.focus()
  } else {
    await nextTick()
    if (previousFocus?.isConnected) previousFocus.focus()
    previousFocus = null
  }
})

function keepDialogFocus(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); dialog.cancel(); return }
  if (event.key !== 'Tab' || !dialogEl.value) return
  const controls = [...dialogEl.value.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled)')]
  if (!controls.length) { event.preventDefault(); dialogEl.value.focus(); return }
  const first = controls[0]
  const last = controls[controls.length - 1]
  if (event.shiftKey && (document.activeElement === first || !dialogEl.value.contains(document.activeElement))) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && (document.activeElement === last || !dialogEl.value.contains(document.activeElement))) { event.preventDefault(); first.focus() }
}

async function signOut() {
  await auth.logout()
  await router.push('/login')
}

/** 下拉字段选项：函数形式（可搜索刷新的选项）每次渲染取最新值。 */
function fieldOptions(field: DialogFieldOption[] | (() => DialogFieldOption[]) | undefined) {
  return typeof field === 'function' ? field() : field ?? []
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand"><div class="brand-mark">PD</div><div><strong>VIBRA</strong><small>预测性维护平台</small></div></div>
      <div class="nav-label">工作台</div>
      <nav class="nav-list">
        <RouterLink to="/dashboard"><span class="nav-icon">◫</span>健康总览</RouterLink>
        <RouterLink to="/workbench"><span class="nav-icon" aria-hidden="true">∿</span>诊断工作站</RouterLink>
        <RouterLink to="/devices"><span class="nav-icon">▦</span>设备与测点</RouterLink>
        <RouterLink to="/diagnostic-rules"><span class="nav-icon" aria-hidden="true">≋</span>主导因素管理</RouterLink>
        <RouterLink to="/alerts"><span class="nav-icon">◉</span>告警中心</RouterLink>
        <RouterLink to="/work-orders"><span class="nav-icon">✓</span>维修工单</RouterLink>
        <RouterLink to="/maintenance"><span class="nav-icon" aria-hidden="true">▣</span>预防性维护</RouterLink>
        <RouterLink to="/baseline"><span class="nav-icon">⌁</span>基线管理</RouterLink>
      </nav>
      <div class="sidebar-bottom"><div class="system-state"><i></i><div><b>系统运行正常</b><small>数据链路已连接</small></div></div><div class="version">PdM MVP · v0.1</div></div>
    </aside>
    <section class="main-area">
      <header class="topbar">
        <div><div class="eyebrow">运维中心 / 多模态设备</div><h1>{{ pageTitle }}</h1></div>
        <div class="user-menu"><button class="theme-toggle" :aria-label="theme === 'light' ? '切换到深色主题' : '切换到亮色主题'" @click="toggleTheme"><span aria-hidden="true">{{ theme === 'light' ? '☾' : '☀' }}</span>{{ theme === 'light' ? '深色' : '亮色' }}</button><div class="avatar">{{ auth.user?.username.slice(0, 1).toUpperCase() }}</div><div><b>{{ auth.user?.username }}</b><small>{{ auth.user?.roles.join(' · ') }}</small></div><button class="icon-button" title="退出登录" @click="signOut">⇥</button></div>
      </header>
      <main class="page-content"><RouterView /></main>
    </section>
    <div v-if="dialog.dialog.value" class="dialog-backdrop" role="presentation" @click.self="dialog.cancel">
      <section ref="dialogEl" class="app-dialog" role="dialog" aria-modal="true" aria-labelledby="app-dialog-title" tabindex="-1" @keydown="keepDialogFocus">
        <form v-if="dialog.dialog.value.kind === 'form'" @submit.prevent="dialog.submit">
          <div class="dialog-heading"><div><span class="dialog-kicker">VIBRA · 操作确认</span><h2 id="app-dialog-title">{{ dialog.dialog.value.title }}</h2><p v-if="dialog.dialog.value.description">{{ dialog.dialog.value.description }}</p></div><button type="button" class="dialog-close" aria-label="关闭" @click="dialog.cancel">×</button></div>
          <div class="dialog-fields">
            <label v-for="field in dialog.dialog.value.fields" :key="field.name">
              {{ field.label }}
              <input v-if="field.search" class="dialog-search" type="text" :placeholder="field.search.placeholder" aria-label="搜索下拉选项" @input="field.search.onSearch(($event.target as HTMLInputElement).value)" />
              <select v-if="field.type === 'select'" v-model="dialog.fieldValues.value[field.name]" :required="field.required">
                <option v-for="option in fieldOptions(field.options)" :key="option.value" :value="option.value">{{ option.label }}</option>
              </select>
              <textarea v-else-if="field.type === 'textarea'" v-model="dialog.fieldValues.value[field.name]" :placeholder="field.placeholder" :required="field.required" :maxlength="field.maxLength" rows="4"></textarea>
              <input v-else v-model="dialog.fieldValues.value[field.name]" :type="field.type || 'text'" :placeholder="field.placeholder" :required="field.required" :min="field.min" :maxlength="field.maxLength" :step="field.step" />
            </label>
          </div>
          <div class="dialog-actions"><button type="button" class="quiet-button" @click="dialog.cancel">取消</button><button type="submit" class="primary-button">{{ dialog.dialog.value.confirmLabel }} <span>→</span></button></div>
        </form>
        <div v-else-if="dialog.dialog.value" class="dialog-confirm"><div class="dialog-heading"><div><span class="dialog-kicker">VIBRA · 操作确认</span><h2 id="app-dialog-title">{{ dialog.dialog.value.title }}</h2></div><button type="button" class="dialog-close" aria-label="关闭" @click="dialog.cancel">×</button></div><p>{{ dialog.dialog.value.description }}</p><div class="dialog-actions"><button class="quiet-button" @click="dialog.cancel">取消</button><button :class="['primary-button', { 'dialog-danger': dialog.dialog.value.danger }]" @click="dialog.submit">{{ dialog.dialog.value.confirmLabel }} <span>→</span></button></div></div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.app-dialog {
  max-height: calc(100dvh - 40px);
  overflow-y: auto;
}

.dialog-fields select {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--bg);
  color: var(--text);
  padding: 10px 11px;
  font-size: 12px;
}

.dialog-fields select:focus-visible {
  outline: 2px solid var(--teal);
  outline-offset: 1px;
}

.dialog-fields .dialog-search {
  margin-bottom: 2px;
  font-size: 11px;
}
</style>
