<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { acknowledgeAlert, createWorkOrder, getAlertsPage, getFactories, getFailedNotifications, getOverview, retryNotification, suppressAlert } from '@/api/pdm'
import NotificationDeliveries from '@/components/NotificationDeliveries.vue'
import type { Alert, AlertPage, AlertQueryParams, Factory, FailedNotificationPage, NotificationDelivery } from '@/api/types'
import { diagnosticMetrics, formatDiagnosticCondition, formatDiagnosticNumber } from '@/diagnostics'
import { featureLabel, formatFeatureValue, triggerFeature } from '@/modality'
import { alertLevelState, pointStatusLabel } from '@/telemetry-status'
import { useAuthStore } from '@/stores/auth'
import { useUiDialog } from '@/composables/ui'

const auth = useAuthStore()
const dialog = useUiDialog()
const PAGE_SIZE = 20
const pageData = ref<AlertPage | null>(null)
const alerts = computed<Alert[]>(() => pageData.value?.items ?? [])
const error = ref('')
const busyId = ref<number | null>(null)
const failedPage = ref<FailedNotificationPage | null>(null)
const failuresError = ref('')
const failuresLoading = ref(false)
const factories = ref<Factory[]>([])

/** 服务端筛选条件：切换任何一项都会重置到第 0 页并重新加载。 */
const filters = reactive({ factoryCode: '', level: 'ALL', status: 'ALL', keyword: '' })
const keywordInput = ref('')
const page = ref(0)
let keywordTimer: number | undefined

const dangerOpenCount = computed(() => pageData.value?.dangerOpenCount ?? 0)
const warningOpenCount = computed(() => pageData.value?.warningOpenCount ?? 0)
const resolvedCount = computed(() => pageData.value?.resolvedCount ?? 0)
const totalPages = computed(() => pageData.value?.totalPages ?? 0)

/**
 * 测点 → 模态。触发 / 基线幅值所指的特征按测点模态不同（振动 mm/s、断路器 ms、调节阀 %、MCSA 无量纲比值），
 * 优先用告警自带的 triggerFeatureCode / modalityType；旧版后端不返回时才取一次总览来推断，避免把所有告警都标成 mm/s。
 */
const modalityByPoint = ref<Record<number, string>>({})
let overviewRequest: AbortController | undefined

function alertFeature(alert: Alert) {
  return triggerFeature(alert.triggerFeatureCode, alert.modalityType ?? modalityByPoint.value[alert.pointId] ?? null)
}

async function loadPointModalities() {
  if (overviewRequest) return
  const request = new AbortController()
  overviewRequest = request
  try {
    const overview = await getOverview(request.signal)
    if (request.signal.aborted) return
    modalityByPoint.value = Object.fromEntries(overview.points.flatMap((point) => (point.modalityType ? [[point.pointId, point.modalityType] as const] : [])))
  } catch {
    /* 总览不可用时单位留空，不臆测 */
  } finally {
    if (overviewRequest === request) overviewRequest = undefined
  }
}
const canRetry = computed(() => auth.hasRole('OPERATOR') || auth.hasRole('ADMIN'))
const canDispatch = computed(() => auth.hasRole('OPERATOR') || auth.hasRole('ADMIN'))
const dispatchingId = ref<number | null>(null)
const dispatchNote = ref('')
const dispatchedIds = ref<number[]>([])

/** CMMS 一键派单（文档 §8.3）：以告警建议措施为初始维修方案生成/更新工单。 */
async function dispatchWorkOrder(alert: Alert) {
  dispatchingId.value = alert.id
  dispatchNote.value = ''
  try {
    const order = await createWorkOrder({
      alarmEventId: alert.id,
      action: alert.diagnosticAdvice || alert.defectDetail?.primary_defect || '按告警根因安排检修',
      priority: alert.level === 'DANGER' ? 'HIGH' : 'MEDIUM',
    })
    dispatchedIds.value = [...dispatchedIds.value, alert.id]
    dispatchNote.value = `工单 #${order.id} 已派发（${order.priority ?? 'MEDIUM'}），可在维修工单页流转`
  } catch (e) {
    dispatchNote.value = e instanceof Error ? e.message : '派单失败'
  } finally {
    dispatchingId.value = null
  }
}
let active = true
let timer: number | undefined
let pending: AbortController | undefined
let failuresRequest: AbortController | undefined

function queryParams(targetPage: number): AlertQueryParams {
  return {
    page: targetPage,
    size: PAGE_SIZE,
    factoryCode: filters.factoryCode || undefined,
    level: filters.level,
    status: filters.status,
    keyword: filters.keyword.trim() || undefined,
  }
}

async function load() {
  if (!active || pending || busyId.value != null) return
  const request = new AbortController()
  pending = request
  try {
    let result = await getAlertsPage(queryParams(page.value), request.signal)
    // 轮询期间当前页数据被处理 / 删除导致越界时，自动回退到最后一页
    while (!request.signal.aborted && result.page > 0 && result.page >= result.totalPages) {
      page.value = Math.max(0, result.totalPages - 1)
      result = await getAlertsPage(queryParams(page.value), request.signal)
    }
    if (request.signal.aborted) return
    pageData.value = result
    error.value = ''
    // 仅当有告警缺少模态 / 触发特征字段时才取总览来推断单位
    if (result.items.some((alert) => !alert.triggerFeatureCode && !alert.modalityType)) void loadPointModalities()
  } catch (e) {
    if (!request.signal.aborted) error.value = e instanceof Error ? e.message : '告警加载失败'
  } finally {
    if (pending === request) pending = undefined
  }
}

function applyFilters() {
  page.value = 0
  filters.keyword = keywordInput.value
  void load()
}

function resetFilters() {
  filters.factoryCode = ''
  filters.level = 'ALL'
  filters.status = 'ALL'
  keywordInput.value = ''
  applyFilters()
}

function goToPage(target: number) {
  if (target < 0 || target === page.value || target >= totalPages.value) return
  page.value = target
  void load()
}

// 关键字 250ms 防抖自动查询（回车 / 查询按钮即时生效）
watch(keywordInput, () => {
  if (keywordTimer != null) window.clearTimeout(keywordTimer)
  keywordTimer = window.setTimeout(applyFilters, 250)
})

async function loadFailures(pageArg = failedPage.value?.page ?? 0) {
  if (!active || failuresRequest || busyId.value != null) return
  const request = new AbortController()
  failuresRequest = request
  failuresLoading.value = true
  try {
    let result = await getFailedNotifications(pageArg, 10, request.signal)
    // A retry in this session or another operator's session can empty the last page.
    while (!request.signal.aborted && result.page > 0 && result.page >= result.totalPages) {
      result = await getFailedNotifications(Math.max(0, result.totalPages - 1), 10, request.signal)
    }
    if (!request.signal.aborted) {
      failedPage.value = result
      failuresError.value = ''
    }
  } catch (e) {
    if (!request.signal.aborted) failuresError.value = e instanceof Error ? e.message : '失败通知加载失败'
  } finally {
    if (failuresRequest === request) {
      failuresRequest = undefined
      failuresLoading.value = false
    }
  }
}

function refresh() {
  void load()
  void loadFailures()
}

onMounted(() => {
  refresh()
  void getFactories().then((items) => { factories.value = items }).catch(() => { /* 车间下拉降级为仅“全部车间” */ })
  timer = window.setInterval(refresh, 15000)
})

onBeforeUnmount(() => {
  active = false
  if (timer != null) window.clearInterval(timer)
  if (keywordTimer != null) window.clearTimeout(keywordTimer)
  pending?.abort()
  overviewRequest?.abort()
  failuresRequest?.abort()
})

async function updateAlert(id: number, submit: () => Promise<Alert | NotificationDelivery>) {
  if (!active || busyId.value != null) return
  busyId.value = id
  pending?.abort()
  pending = undefined
  failuresRequest?.abort()
  failuresRequest = undefined
  failuresLoading.value = false
  error.value = ''
  try {
    const updated = await submit()
    if (!active) return
    if ('eventKind' in updated) {
      pageData.value = pageData.value && {
        ...pageData.value,
        items: pageData.value.items.map(alert => alert.id !== updated.alertId ? alert : {
          ...alert, notifications: alert.notifications.map(event => event.id === updated.id ? updated : event),
        }),
      }
    } else {
      pageData.value = pageData.value && {
        ...pageData.value,
        items: pageData.value.items.map(alert => alert.id !== updated.id ? alert : updated),
      }
    }
  } catch (e) {
    if (active) error.value = e instanceof Error ? e.message : '操作失败'
    return
  } finally {
    busyId.value = null
  }
  refresh()
}

function acknowledge(id: number) {
  return updateAlert(id, () => acknowledgeAlert(id))
}

function retry(alertId: number, id: number) {
  return updateAlert(alertId, () => retryNotification(id))
}

async function suppress(id: number) {
  const values = await dialog.form({
    title: '抑制告警',
    description: '请记录误报或抑制原因，便于后续追溯。',
    confirmLabel: '确认抑制',
    fields: [{ name: 'note', label: '抑制原因', type: 'textarea', placeholder: '填写原因或补充说明', required: true }],
  })
  const note = values?.note.trim()
  if (note) await updateAlert(id, () => suppressAlert(id, note))
}
const time = (value: string) => new Date(value).toLocaleString()

function condition(alert: Alert) {
  const { diagnosticMetric, diagnosticOperator, diagnosticThreshold, diagnosticFrequencyOrder, diagnosticObservedValue } = alert
  if (!diagnosticMetric || !diagnosticOperator || diagnosticThreshold == null) return ''
  const rule = formatDiagnosticCondition(diagnosticMetric, diagnosticOperator, diagnosticThreshold, diagnosticFrequencyOrder)
  if (diagnosticObservedValue == null) return rule
  return `${rule} · 实测 ${formatDiagnosticNumber(diagnosticObservedValue)} ${diagnosticMetrics[diagnosticMetric].unit}`
}
</script>

<template>
  <div class="page-stack">
    <div class="page-intro"><div><h2>设备告警</h2><p>连续确认设备级健康风险后创建告警；测点数据作为诊断证据保留。</p></div><button class="quiet-button" :disabled="busyId != null" @click="refresh">↻ 刷新告警</button></div>
    <div v-if="error" class="notice error-notice">{{ error }}</div>
    <div v-if="dispatchNote" class="notice info-notice">{{ dispatchNote }}</div>
    <div class="alert-summary">
      <div><span class="summary-dot red-dot"></span><b>危险</b><strong>{{ dangerOpenCount }}</strong></div>
      <div><span class="summary-dot orange-dot"></span><b>警告</b><strong>{{ warningOpenCount }}</strong></div>
      <div><span class="summary-dot gray-dot"></span><b>已闭环 / 抑制</b><strong>{{ resolvedCount }}</strong></div>
    </div>
    <section class="panel filter-panel" aria-label="告警筛选">
      <label class="filter-item">车间
        <select v-model="filters.factoryCode" @change="applyFilters"><option value="">全部车间</option><option v-for="factory in factories" :key="factory.id" :value="factory.factoryCode">{{ factory.factoryName }}（{{ factory.factoryCode }}）</option></select>
      </label>
      <label class="filter-item">级别
        <select v-model="filters.level" @change="applyFilters"><option value="ALL">全部级别</option><option value="DANGER">危险</option><option value="WARNING">警告</option></select>
      </label>
      <label class="filter-item">状态
        <select v-model="filters.status" @change="applyFilters"><option value="ALL">全部状态</option><option value="OPEN">待处理（未闭环）</option><option value="UNACKNOWLEDGED">未确认</option><option value="ACKNOWLEDGED">已确认</option><option value="CLOSED">已闭环</option><option value="SUPPRESSED">已抑制</option></select>
      </label>
      <label class="filter-item grow">关键字
        <input v-model="keywordInput" placeholder="设备 / 测点编码、名称或主导因素，回车查询" @keyup.enter="applyFilters" />
      </label>
      <div class="filter-actions">
        <button class="small-button primary-small" @click="applyFilters">查询</button>
        <button class="quiet-button" @click="resetFilters">重置筛选</button>
      </div>
    </section>
    <section class="panel table-panel" aria-label="投递失败的通知">
      <div class="panel-heading">
        <div><h3>投递失败的通知</h3><p>包含全部历史告警中已用尽自动重试次数的通知</p></div>
        <span v-if="failedPage" class="count-chip">{{ failedPage.totalElements }} 条</span>
      </div>
      <div v-if="failuresError" class="notice error-notice">{{ failuresError }}</div>
      <div v-if="failedPage?.items.length" class="table-wrap"><table>
        <thead><tr><th>通知 / 告警</th><th>通知类型</th><th>创建时间</th><th>投递次数</th><th>最近错误</th><th v-if="canRetry">操作</th></tr></thead>
        <tbody><tr v-for="event in failedPage.items" :key="event.id">
          <td>通知 #{{ event.id }}<small>告警 #{{ event.alertId }}</small></td>
          <td>{{ event.eventKind === 'ALERT' ? '首次通知' : '超时升级' }}</td>
          <td>{{ time(event.createdAt) }}</td><td>{{ event.attempts }}</td><td>{{ event.lastError || '—' }}</td>
          <td v-if="canRetry"><button class="small-button" :disabled="busyId != null || failuresLoading"
            @click="retry(event.alertId, event.id)">重新投递</button></td>
        </tr></tbody>
      </table></div>
      <p v-else-if="!failuresError" class="empty-cell">{{ failuresLoading ? '正在加载失败通知…' : '当前没有投递失败的通知。' }}</p>
      <nav v-if="failedPage && failedPage.totalPages > 1" class="failure-pages" aria-label="失败通知分页">
        <button class="small-button" :disabled="busyId != null || failuresLoading || failedPage.page === 0"
          @click="loadFailures(failedPage.page - 1)">上一页</button>
        <span>第 {{ failedPage.page + 1 }} / {{ failedPage.totalPages }} 页</span>
        <button class="small-button" :disabled="busyId != null || failuresLoading || failedPage.page + 1 >= failedPage.totalPages"
          @click="loadFailures(failedPage.page + 1)">下一页</button>
      </nav>
    </section>
    <section class="panel table-panel"><div class="panel-heading"><div><h3>设备告警记录</h3><p>服务端分页筛选，每页 {{ PAGE_SIZE }} 条</p></div><span class="count-chip">{{ pageData?.totalItems ?? 0 }} 条记录</span></div><div class="table-wrap"><table><thead><tr><th>级别</th><th>设备与风险测点</th><th>设备 HI</th><th>主导因素与测点证据</th><th>触发测点特征 当前值 / 基线</th><th>发生时间</th><th>通知投递</th><th>处理</th></tr></thead><tbody>
      <tr v-for="alert in alerts" :key="alert.id">
        <td><span :class="['status-tag', alertLevelState(alert.level).tone]"><i></i>{{ alertLevelState(alert.level).label }}</span></td>
        <td><RouterLink :to="`/devices/${alert.deviceId}`" class="table-link"><b>{{ alert.deviceName }}</b></RouterLink><small>{{ alert.deviceCode }} · 触发测点 {{ alert.pointCode }}</small></td>
        <td><b :class="`hi-value ${alert.level === 'DANGER' ? 'red' : 'orange'}`">{{ alert.healthIndex.toFixed(1) }}</b></td>
        <td class="factor-cell">
          <b>{{ alert.dominantFactor }}</b>
          <small v-if="alert.diagnosticEvidence">触发测点 {{ alert.pointCode }} 的诊断依据：{{ alert.diagnosticEvidence }}</small>
          <small v-if="alert.diagnosticMetric">{{ condition(alert) }}</small>
          <small v-if="alert.diagnosticObservedFrequencyHz != null && alert.diagnosticTargetFrequencyHz != null">
            实测峰频 {{ formatDiagnosticNumber(alert.diagnosticObservedFrequencyHz) }} Hz · 目标频率 {{ formatDiagnosticNumber(alert.diagnosticTargetFrequencyHz) }} Hz
          </small>
          <small v-if="alert.defectDetail?.primary_defect && alert.defectDetail.primary_defect !== alert.dominantFactor"><b>主因分析：{{ alert.defectDetail.primary_defect }}</b></small>
          <small v-if="alert.defectDetail?.slope_dk_dt_per_day != null">劣化速率 dk/dt {{ alert.defectDetail.slope_dk_dt_per_day.toFixed(2) }}/24h · 雪崩乘子 η_dyn {{ alert.defectDetail.eta_dyn?.toFixed(3) }}</small>
          <small v-if="alert.defectDetail?.contributions?.length">机理佐证：{{ alert.defectDetail.contributions.map(c => `${featureLabel(c.feature)} k=${c.ratio?.toFixed(2) ?? '—'} · 得分 ${c.score.toFixed(1)}`).join('；') }}</small>
          <small v-if="alert.diagnosticAdvice">建议措施：{{ alert.diagnosticAdvice }}</small>
          <small v-if="alert.rawSnapshotUrl">关联波形：<RouterLink :to="`/workbench?pointId=${alert.pointId}`" class="text-link">在工作站中查看快照</RouterLink></small>
          <small v-if="alert.evidencePoints.length">设备证据：{{ alert.evidencePoints.map(point => `${point.pointCode} HI ${point.healthIndex?.toFixed(1) ?? '—'} · ${pointStatusLabel(point.status)}`).join('；') }}</small>
          <small v-if="alert.deviceTypeName">{{ alert.deviceTypeName }}<template v-if="alert.diagnosticRuleId != null"> · 规则 #{{ alert.diagnosticRuleId }}<template v-if="alert.diagnosticRuleVersion != null"> · 版本 {{ alert.diagnosticRuleVersion }}</template></template></small>
        </td>
        <!-- 触发特征的名称与单位随测点模态变化（振动 mm/s、断路器 ms、调节阀 %、MCSA 无量纲），不能再一律标成 1X 振幅 mm/s -->
        <td><b>{{ alertFeature(alert).label }}</b> <small>{{ formatFeatureValue(alert.triggerAmplitude, alertFeature(alert).unit) }} / {{ formatFeatureValue(alert.baselineAmplitude, alertFeature(alert).unit) }}</small></td>
        <td>{{ time(alert.createdAt) }}<small>最近 {{ time(alert.lastSeenAt) }} · {{ alert.occurrenceCount }} 次</small></td>
        <td><NotificationDeliveries :deliveries="alert.notifications" :notified="alert.notified"
          :can-retry="canRetry" :busy="busyId != null" @retry="retry(alert.id, $event)" /></td>
        <td><div v-if="alert.status < 2" class="action-row"><button v-if="alert.status === 0" class="small-button" :disabled="busyId != null || !auth.hasRole('OPERATOR') && !auth.hasRole('ADMIN')" @click="acknowledge(alert.id)">{{ busyId === alert.id ? '提交中…' : '确认' }}</button><span v-else class="muted">已确认，待处理</span><button v-if="auth.hasRole('ADMIN')" class="small-button danger-button" :disabled="busyId != null" @click="suppress(alert.id)">抑制</button><button v-if="canDispatch && !dispatchedIds.includes(alert.id)" class="small-button" :disabled="dispatchingId === alert.id" @click="dispatchWorkOrder(alert)">{{ dispatchingId === alert.id ? '派单中…' : '一键派单' }}</button><span v-else-if="dispatchedIds.includes(alert.id)" class="muted">已派单</span></div><span v-else class="muted">{{ alert.status === 3 ? '已抑制' : '已闭环' }}</span></td>
      </tr>
      <tr v-if="!alerts.length"><td colspan="8" class="empty-cell">{{ pageData == null ? '正在加载告警…' : '当前筛选条件下没有设备级告警。健康指数处于 50–70 的设备会显示为“关注”，不会创建告警或工单。' }}</td></tr>
    </tbody></table></div>
      <nav v-if="totalPages > 1" class="failure-pages" aria-label="告警分页">
        <button class="small-button" :disabled="busyId != null || page === 0" @click="goToPage(page - 1)">上一页</button>
        <span>第 {{ page + 1 }} / {{ totalPages }} 页 · 共 {{ pageData?.totalItems ?? 0 }} 条</span>
        <button class="small-button" :disabled="busyId != null || page + 1 >= totalPages" @click="goToPage(page + 1)">下一页</button>
      </nav>
    </section>
  </div>
</template>

<style scoped>
.factor-cell { overflow-wrap: anywhere; }
.failure-pages { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: .75rem; padding: .75rem 1rem; }
.filter-panel { display: flex; flex-wrap: wrap; align-items: flex-end; gap: .85rem; padding: .9rem 1rem; }
.filter-item { display: grid; gap: .3rem; color: #92a4ba; font-size: 10px; }
.filter-item.grow { flex: 1; min-width: 200px; }
.filter-item select, .filter-item input { border: 1px solid #293c53; border-radius: 7px; background: #0b1727; color: #e0e9f4; padding: 8px 9px; outline: 0; font-size: 11px; min-width: 150px; }
.filter-item input { width: 100%; min-width: 0; }
.filter-item select:focus, .filter-item input:focus { border-color: #49bfae; box-shadow: 0 0 0 3px #49bfae17; }
.filter-actions { display: flex; align-items: center; gap: .5rem; }
</style>
