<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError } from '@/api/http'
import {
  assignMaintenanceTask, cancelMaintenanceTask, completeMaintenanceTask, createMaintenancePlan,
  getDevices, getFactories, getMaintenancePlan, getMaintenancePlans, getMaintenanceTasks, startMaintenanceTask, updateMaintenancePlan,
} from '@/api/pdm'
import type {
  Device, MaintenanceDueState, MaintenancePage, MaintenancePlan, MaintenancePlanInput, MaintenanceTask, MaintenanceTaskStatus,
} from '@/api/types'
import { useUiDialog } from '@/composables/ui'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const dialog = useUiDialog()
const maxIntervalDays = 36_500
const isAdmin = computed(() => auth.hasRole('ADMIN'))
const canOperate = computed(() => isAdmin.value || auth.hasRole('OPERATOR'))
const plans = ref<MaintenancePage<MaintenancePlan>>({ items: [], page: 0, totalPages: 0, totalItems: 0 })
const tasks = ref<MaintenancePage<MaintenanceTask>>({ items: [], page: 0, totalPages: 0, totalItems: 0 })
const devices = ref<Device[]>([])
const showHistory = ref(false)
const error = ref('')
const deviceError = ref('')
const loading = ref(true)
const saving = ref(false)
const editing = ref<MaintenancePlan | null>(null)
const form = ref<MaintenancePlanInput | null>(null)
const planConflict = ref(false)

const dueLabels: Record<MaintenanceDueState, { text: string; color: string }> = {
  UPCOMING: { text: '未到期', color: 'blue' },
  DUE: { text: '24 小时内到期', color: 'amber' },
  OVERDUE: { text: '已逾期', color: 'red' },
  INACTIVE: { text: '停用', color: 'gray' },
}
const taskLabels: Record<MaintenanceTaskStatus, { text: string; color: string }> = {
  OPEN: { text: '待处理', color: 'amber' },
  IN_PROGRESS: { text: '进行中', color: 'blue' },
  COMPLETED: { text: '已完成', color: 'green' },
  CANCELLED: { text: '已取消', color: 'gray' },
}

const activeTask = (task: MaintenanceTask) => task.status === 'OPEN' || task.status === 'IN_PROGRESS'
const time = (value: string) => new Date(value).toLocaleString()

function localDateTime(value: string) {
  const date = new Date(value)
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 19)
}

type LoadScope = 'all' | 'plans' | 'tasks'

async function loadPlans(page: number) {
  const result = await getMaintenancePlans(page)
  const lastPage = Math.max(0, result.totalPages - 1)
  plans.value = page > lastPage ? await getMaintenancePlans(lastPage) : result
}

async function loadTasks(page: number, history: boolean) {
  const filter = history ? 'HISTORY' : 'ACTIVE'
  const result = await getMaintenanceTasks(filter, page)
  const lastPage = Math.max(0, result.totalPages - 1)
  tasks.value = page > lastPage ? await getMaintenanceTasks(filter, lastPage) : result
  showHistory.value = history
}

async function load(scope: LoadScope = 'all', page?: number, history = showHistory.value) {
  loading.value = true
  error.value = ''
  try {
    if (scope === 'plans') await loadPlans(page ?? plans.value.page)
    else if (scope === 'tasks') await loadTasks(page ?? tasks.value.page, history)
    else {
      const results = await Promise.allSettled([loadPlans(plans.value.page), loadTasks(tasks.value.page, showHistory.value)])
      const failure = results.find((result) => result.status === 'rejected')
      if (failure?.status === 'rejected') throw failure.reason
    }
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '维护数据加载失败'
  } finally {
    loading.value = false
  }
}

async function loadDevices() {
  deviceError.value = ''
  try {
    const factories = await getFactories()
    devices.value = (await Promise.all(factories.map((factory) => getDevices(factory.factoryCode)))).flat()
  } catch (failure) {
    deviceError.value = failure instanceof Error ? failure.message : '设备列表加载失败'
  }
}

async function refresh() {
  await Promise.all([load(), isAdmin.value ? loadDevices() : undefined])
}

onMounted(refresh)

function editPlan(plan: MaintenancePlan | null) {
  planConflict.value = false
  editing.value = plan
  form.value = plan
    ? { ...plan, nextDueAt: localDateTime(plan.nextDueAt) }
    : { deviceId: devices.value[0]?.id ?? 0, title: '', intervalDays: 30, nextDueAt: '',
        assignee: '', instructions: '', enabled: true }
}

async function mutate(operation: () => Promise<unknown>, scope: LoadScope = 'tasks') {
  saving.value = true
  error.value = ''
  try {
    await operation()
    await load(scope)
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '维护操作失败'
    return failure
  } finally {
    saving.value = false
  }
}

async function savePlan() {
  const input = form.value
  const plan = editing.value
  if (!input || planConflict.value) return
  const title = input.title.trim()
  const due = new Date(input.nextDueAt)
  if (!title || Number.isNaN(due.valueOf()) || !Number.isInteger(input.intervalDays) ||
      input.intervalDays < 1 || input.intervalDays > maxIntervalDays) {
    error.value = `请输入计划名称、1 至 ${maxIntervalDays} 天的整数周期和有效到期时间。`
    return
  }
  const data: MaintenancePlanInput = {
    deviceId: input.deviceId,
    title,
    intervalDays: input.intervalDays,
    nextDueAt: plan && input.nextDueAt === localDateTime(plan.nextDueAt) ? plan.nextDueAt : due.toISOString(),
    instructions: input.instructions?.trim() || null,
    assignee: input.assignee?.trim() || null,
    enabled: input.enabled,
  }
  const failure = await mutate(async () => {
    if (plan) await updateMaintenancePlan(plan.id, data, plan.version)
    else await createMaintenancePlan(data)
    form.value = null
  }, plan ? 'plans' : 'all')
  if (plan && failure instanceof ApiError && failure.status === 409) {
    planConflict.value = true
    error.value = ''
  }
}

async function reloadPlanEditor() {
  const plan = editing.value
  if (!plan) return
  const confirmed = await dialog.confirm({
    title: '重新载入维护计划',
    description: '重新载入会丢弃当前未保存的修改。你可以先取消并复制需要保留的内容。',
    confirmLabel: '载入最新版本',
  })
  if (confirmed) {
    await mutate(async () => editPlan(await getMaintenancePlan(plan.id)), 'plans')
  }
}

async function togglePlan(plan: MaintenancePlan) {
  await mutate(() => updateMaintenancePlan(plan.id, {
    deviceId: plan.deviceId,
    title: plan.title,
    intervalDays: plan.intervalDays,
    nextDueAt: plan.nextDueAt,
    instructions: plan.instructions,
    assignee: plan.assignee,
    enabled: !plan.enabled,
  }, plan.version), 'plans')
}

async function assign(task: MaintenanceTask) {
  const values = await dialog.form({
    title: '分派维护任务',
    description: task.planTitle ?? task.deviceName,
    confirmLabel: '确认分派',
    fields: [{ name: 'assignee', label: '处理人用户名', value: task.assignee ?? auth.user?.username,
      required: true, maxLength: 80 }],
  })
  if (!values) return
  const assignee = values.assignee.trim()
  if (!assignee) {
    error.value = '请输入处理人用户名。'
    return
  }
  await mutate(() => assignMaintenanceTask(task.id, assignee))
}

async function complete(task: MaintenanceTask) {
  const values = await dialog.form({
    title: '完成维护任务',
    description: task.planTitle ?? task.deviceName,
    confirmLabel: '完成任务',
    fields: [
      { name: 'findings', label: '现场发现', type: 'textarea', required: true, maxLength: 2000 },
      { name: 'action', label: '已采取的措施', type: 'textarea', required: true, maxLength: 2000 },
    ],
  })
  if (!values) return
  const findings = values.findings.trim()
  const action = values.action.trim()
  if (!findings || !action) {
    error.value = '请输入现场发现和已采取的措施。'
    return
  }
  await mutate(() => completeMaintenanceTask(task.id, findings, action))
}

async function cancel(task: MaintenanceTask) {
  const confirmed = await dialog.confirm({
    title: '取消维护任务',
    description: `确认取消 ${task.planTitle ?? task.deviceName}（任务 #${task.id}）？`,
    confirmLabel: '确认取消',
    danger: true,
  })
  if (confirmed) await mutate(() => cancelMaintenanceTask(task.id))
}
</script>

<template>
  <div class="page-stack">
    <div class="page-intro">
      <button class="quiet-button" :disabled="loading || saving" @click="refresh">↻ 刷新维护记录</button>
    </div>
    <div v-if="error" class="notice error-notice" role="alert">{{ error }}</div>
    <section class="panel maintenance-panel" aria-labelledby="maintenance-tasks">
      <div class="panel-heading">
        <h3 id="maintenance-tasks">维护任务</h3>
        <div class="action-row maintenance-actions" aria-label="任务列表范围">
          <button class="small-button" :class="{ 'primary-small': !showHistory }"
            :aria-pressed="!showHistory" :disabled="loading || saving" @click="load('tasks', 0, false)">待办</button>
          <button class="small-button" :class="{ 'primary-small': showHistory }"
            :aria-pressed="showHistory" :disabled="loading || saving" @click="load('tasks', 0, true)">历史</button>
        </div>
      </div>
      <p v-if="!tasks.items.length" class="muted">{{ loading ? '正在加载任务…' : showHistory ? '暂无历史记录。' : '暂无待办任务。' }}</p>
      <div v-else class="maintenance-grid">
        <article v-for="task in tasks.items" :key="task.id" class="maintenance-card">
          <div class="panel-heading">
            <div>
              <h4>{{ task.planTitle ?? `维护任务 #${task.id}` }}</h4>
              <RouterLink :to="`/devices/${task.deviceId}`" class="text-link">
                {{ task.deviceCode }} · {{ task.deviceName }}
              </RouterLink>
            </div>
            <div class="maintenance-tags">
              <span :class="['status-tag', taskLabels[task.status].color]">{{ taskLabels[task.status].text }}</span>
              <span v-if="activeTask(task)" :class="['status-tag', dueLabels[task.dueState].color]">{{ dueLabels[task.dueState].text }}</span>
            </div>
          </div>
          <dl class="maintenance-details">
            <div><dt>到期时间</dt><dd>{{ time(task.dueAt) }}</dd></div>
            <div><dt>处理人</dt><dd>{{ task.assignee || '未分派' }}</dd></div>
            <div v-if="task.completedAt"><dt>完成时间</dt><dd>{{ time(task.completedAt) }}</dd></div>
            <div v-if="task.status === 'CANCELLED'"><dt>取消时间</dt><dd>{{ time(task.updatedAt) }}</dd></div>
            <div v-if="task.instructions" class="full-detail"><dt>维护说明</dt><dd>{{ task.instructions }}</dd></div>
            <div v-if="task.findings" class="full-detail"><dt>现场发现</dt><dd>{{ task.findings }}</dd></div>
            <div v-if="task.action" class="full-detail"><dt>已采取的措施</dt><dd>{{ task.action }}</dd></div>
          </dl>
          <div v-if="canOperate && activeTask(task)" class="action-row maintenance-actions">
            <button class="small-button" :disabled="saving || loading" @click="assign(task)">分派</button>
            <button v-if="task.status === 'OPEN'" class="small-button" :disabled="saving || loading"
              @click="mutate(() => startMaintenanceTask(task.id))">开始</button>
            <button class="small-button primary-small" :disabled="saving || loading" @click="complete(task)">完成</button>
            <button class="small-button" :disabled="saving || loading" @click="cancel(task)">取消任务</button>
          </div>
        </article>
      </div>
      <nav v-if="tasks.totalItems" class="maintenance-pager" aria-label="维护任务分页">
        <button class="small-button" :disabled="loading || saving || tasks.page === 0" @click="load('tasks', tasks.page - 1)">上一页</button>
        <span class="muted">第 {{ tasks.page + 1 }} / {{ tasks.totalPages }} 页 · 共 {{ tasks.totalItems }} 项</span>
        <button class="small-button" :disabled="loading || saving || tasks.page + 1 >= tasks.totalPages"
          @click="load('tasks', tasks.page + 1)">下一页</button>
      </nav>
    </section>

    <section class="panel maintenance-panel" aria-labelledby="maintenance-plans">
      <div class="panel-heading">
        <h3 id="maintenance-plans">维护计划</h3>
        <button v-if="isAdmin && !form" class="small-button primary-small" :disabled="saving || loading || !devices.length"
          @click="editPlan(null)">新建计划</button>
      </div>
      <div v-if="deviceError" class="notice error-notice" role="alert">{{ deviceError }}</div>
      <p v-else-if="isAdmin && !loading && !devices.length" class="muted">添加设备后即可新建维护计划。</p>

      <form v-if="isAdmin && form" class="form-panel maintenance-form" @submit.prevent="savePlan">
        <div class="panel-heading full-detail">
          <div>
            <h3>{{ editing ? '编辑计划' : '新建计划' }}</h3>
            <p v-if="editing">调整适用于后续生成的任务。</p>
          </div>
        </div>
        <div v-if="planConflict" class="notice error-notice full-detail maintenance-actions" role="alert">
          <span>服务器上的计划已更新。请载入最新版本后继续编辑。</span>
          <button type="button" class="small-button" :disabled="saving || loading" @click="reloadPlanEditor">载入最新版本</button>
        </div>
        <label>设备
          <select v-model.number="form.deviceId" required>
            <option v-for="device in devices" :key="device.id" :value="device.id">
              {{ device.factoryCode }} / {{ device.deviceCode }} · {{ device.deviceName }}
            </option>
          </select>
        </label>
        <label>计划名称 <input v-model="form.title" type="text" maxlength="128" required /></label>
        <label>周期（天）
          <input v-model.number="form.intervalDays" type="number" min="1" :max="maxIntervalDays" step="1" required />
        </label>
        <label>{{ editing ? '下次到期时间' : '首次到期时间' }}（本地时间）
          <input v-model="form.nextDueAt" type="datetime-local" step="1" required />
        </label>
        <label>默认处理人 <input v-model="form.assignee" type="text" maxlength="80" /></label>
        <label>计划状态
          <select v-model="form.enabled">
            <option :value="true">启用</option>
            <option :value="false">停用</option>
          </select>
        </label>
        <label class="full-detail">维护说明 <textarea v-model="form.instructions" rows="3" maxlength="2000"></textarea></label>
        <div class="action-row full-detail">
          <button type="submit" class="small-button primary-small" :disabled="saving || loading || planConflict">保存计划</button>
          <button type="button" class="small-button" :disabled="saving" @click="form = null">取消编辑</button>
        </div>
      </form>

      <p v-if="!plans.items.length" class="muted">{{ loading ? '正在加载计划…' : '暂无维护计划。' }}</p>
      <div v-else class="maintenance-grid">
        <article v-for="plan in plans.items" :key="plan.id" class="maintenance-card">
          <div class="panel-heading">
            <div>
              <h4>{{ plan.title }}</h4>
              <RouterLink :to="`/devices/${plan.deviceId}`" class="text-link">
                {{ plan.deviceCode }} · {{ plan.deviceName }}
              </RouterLink>
            </div>
            <div class="maintenance-tags">
              <span :class="['status-tag', plan.enabled ? 'green' : 'gray']">{{ plan.enabled ? '已启用' : '已停用' }}</span>
              <span v-if="plan.enabled" :class="['status-tag', dueLabels[plan.dueState].color]">{{ dueLabels[plan.dueState].text }}</span>
            </div>
          </div>
          <dl class="maintenance-details">
            <div><dt>下次到期时间</dt><dd>{{ time(plan.nextDueAt) }}</dd></div>
            <div><dt>周期</dt><dd>每 {{ plan.intervalDays }} 天</dd></div>
            <div><dt>默认处理人</dt><dd>{{ plan.assignee || '未分派' }}</dd></div>
            <div v-if="plan.instructions" class="full-detail"><dt>维护说明</dt><dd>{{ plan.instructions }}</dd></div>
          </dl>
          <div v-if="isAdmin" class="action-row maintenance-actions">
            <button class="small-button" :disabled="saving || loading || Boolean(form) || !devices.length"
              @click="editPlan(plan)">编辑</button>
            <button class="small-button" :disabled="saving || loading || Boolean(form)"
              @click="togglePlan(plan)">{{ plan.enabled ? '停用' : '启用' }}</button>
          </div>
        </article>
      </div>
      <nav v-if="plans.totalItems" class="maintenance-pager" aria-label="维护计划分页">
        <button class="small-button" :disabled="loading || saving || plans.page === 0" @click="load('plans', plans.page - 1)">上一页</button>
        <span class="muted">第 {{ plans.page + 1 }} / {{ plans.totalPages }} 页 · 共 {{ plans.totalItems }} 项</span>
        <button class="small-button" :disabled="loading || saving || plans.page + 1 >= plans.totalPages"
          @click="load('plans', plans.page + 1)">下一页</button>
      </nav>
    </section>
  </div>
</template>

<style scoped>
.maintenance-panel {
  display: grid;
  gap: 14px;
  padding: 18px;
  min-width: 0;
}

.maintenance-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 290px), 1fr));
  gap: 12px;
}

.maintenance-card {
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow-wrap: anywhere;
}

.maintenance-card h4 {
  margin: 0 0 4px;
  font-size: 13px;
}

.maintenance-tags,
.maintenance-actions,
.maintenance-pager {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.maintenance-pager {
  align-items: center;
  justify-content: flex-end;
}

.maintenance-tags {
  flex-shrink: 0;
  justify-content: flex-end;
  max-width: 48%;
}

.maintenance-details,
.maintenance-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 175px), 1fr));
  gap: 12px;
}

.maintenance-details dt {
  color: var(--muted);
  font-size: 10px;
}

.maintenance-details dd {
  margin: 4px 0 0;
  white-space: pre-wrap;
  font-size: 12px;
}

.maintenance-form {
  border: 1px solid var(--line);
  border-radius: 8px;
}

.maintenance-form textarea {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: var(--bg);
  color: var(--text);
  padding: 9px 10px;
  resize: vertical;
  font: inherit;
}

.full-detail {
  grid-column: 1 / -1;
}
</style>
