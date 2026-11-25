<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { assignWorkOrder, closeWorkOrder, createManualWorkOrder, getDevices, getFactories, getPoints, getWorkOrdersPage } from '@/api/pdm'
import type { Device, DiagnosisVerdict, Factory, Point, WorkOrder, WorkOrderPage, WorkOrderPriority, WorkOrderQueryParams } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useUiDialog } from '@/composables/ui'
import { alertLevelState, pointStatusLabel, workOrderStatusState } from '@/telemetry-status'

const auth = useAuthStore()
const dialog = useUiDialog()
const PAGE_SIZE = 20
const pageData = ref<WorkOrderPage | null>(null)
const orders = computed<WorkOrder[]>(() => pageData.value?.items ?? [])
const error = ref('')
const note = ref('')
const busyId = ref<number | null>(null)
const factories = ref<Factory[]>([])

/** 服务端筛选条件：切换任何一项都会重置到第 0 页并重新加载。 */
const filters = reactive({ factoryCode: '', status: 'ALL', priority: 'ALL', source: 'ALL', keyword: '' })
const keywordInput = ref('')
const page = ref(0)
let keywordTimer: number | undefined
let pending: AbortController | undefined
let active = true

const canManage = computed(() => auth.hasRole('ADMIN') || auth.hasRole('OPERATOR'))
const pendingCount = computed(() => pageData.value?.pendingCount ?? 0)
const totalPages = computed(() => pageData.value?.totalPages ?? 0)

const priorityLabels: Record<WorkOrderPriority, string> = { URGENT: '紧急', HIGH: '高', MEDIUM: '中', LOW: '低' }

function queryParams(targetPage: number): WorkOrderQueryParams {
  return {
    page: targetPage,
    size: PAGE_SIZE,
    factoryCode: filters.factoryCode || undefined,
    status: filters.status,
    priority: filters.priority,
    source: filters.source,
    keyword: filters.keyword.trim() || undefined,
  }
}

async function load() {
  if (!active || pending || busyId.value != null) return
  const request = new AbortController()
  pending = request
  try {
    let result = await getWorkOrdersPage(queryParams(page.value), request.signal)
    // 闭环 / 分派导致当前页越界时自动回退到最后一页
    while (!request.signal.aborted && result.page > 0 && result.page >= result.totalPages) {
      page.value = Math.max(0, result.totalPages - 1)
      result = await getWorkOrdersPage(queryParams(page.value), request.signal)
    }
    if (request.signal.aborted) return
    pageData.value = result
    error.value = ''
  } catch (e) {
    if (!request.signal.aborted) error.value = e instanceof Error ? e.message : '工单加载失败'
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
  filters.status = 'ALL'
  filters.priority = 'ALL'
  filters.source = 'ALL'
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

onMounted(() => {
  void load()
  void getFactories().then((items) => { factories.value = items }).catch(() => { /* 车间下拉降级为仅“全部车间” */ })
})
onBeforeUnmount(() => {
  active = false
  if (keywordTimer != null) window.clearTimeout(keywordTimer)
  pending?.abort()
})

// ==================== 新建手工工单（级联：车间 → 设备 → 测点可选） ====================

const formDialog = ref<HTMLDialogElement | null>(null)
const manual = reactive({
  factoryCode: '',
  deviceId: null as number | null,
  pointId: null as number | null,
  title: '',
  priority: 'MEDIUM' as WorkOrderPriority,
  maintenanceAction: '',
  assignee: '',
})
const dialogDevices = ref<Device[]>([])
const dialogPoints = ref<Point[]>([])
const dialogBusy = ref(false)
const dialogError = ref('')

function openManualDialog() {
  dialogError.value = ''
  manual.factoryCode = ''
  manual.deviceId = null
  manual.pointId = null
  manual.title = ''
  manual.priority = 'MEDIUM'
  manual.maintenanceAction = ''
  manual.assignee = ''
  dialogDevices.value = []
  dialogPoints.value = []
  formDialog.value?.showModal()
}

async function onManualFactoryChange() {
  manual.deviceId = null
  manual.pointId = null
  dialogDevices.value = []
  dialogPoints.value = []
  if (!manual.factoryCode) return
  try { dialogDevices.value = await getDevices(manual.factoryCode) }
  catch (e) { dialogError.value = e instanceof Error ? e.message : '设备列表加载失败' }
}

async function onManualDeviceChange() {
  manual.pointId = null
  dialogPoints.value = []
  if (manual.deviceId == null) return
  try { dialogPoints.value = await getPoints(manual.deviceId) }
  catch (e) { dialogError.value = e instanceof Error ? e.message : '测点列表加载失败' }
}

async function submitManual() {
  if (manual.deviceId == null) { dialogError.value = '请选择目标设备'; return }
  const title = manual.title.trim()
  if (!title) { dialogError.value = '工单标题 / 故障现象为必填项'; return }
  if (title.length > 128) { dialogError.value = '工单标题不能超过 128 个字符'; return }
  dialogBusy.value = true
  dialogError.value = ''
  try {
    const created = await createManualWorkOrder({
      deviceId: manual.deviceId,
      pointId: manual.pointId,
      title,
      priority: manual.priority,
      maintenanceAction: manual.maintenanceAction.trim() || null,
      assignee: manual.assignee.trim() || null,
    })
    formDialog.value?.close()
    note.value = `手工工单 WO-${String(created.id).padStart(5, '0')} 已创建${created.status === 'IN_PROGRESS' ? `并分派给 ${created.assignee}` : '，待分派'}`
    page.value = 0
    await load()
  } catch (e) {
    dialogError.value = e instanceof Error ? e.message : '手工工单创建失败'
  } finally {
    dialogBusy.value = false
  }
}

// ==================== 分派 / 闭环 ====================

async function assign(order: WorkOrder) {
  const values = await dialog.form({ title: '分派维修工单', description: `工单 WO-${String(order.id).padStart(5, '0')} · ${order.deviceCode}`, confirmLabel: '确认分派', fields: [{ name: 'assignee', label: '处理人用户名', value: order.assignee || auth.user?.username || '', placeholder: '请输入处理人用户名', required: true }] })
  if (!values?.assignee.trim()) return
  busyId.value = order.id
  try { await assignWorkOrder(order.id, values.assignee.trim()); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '分派失败' }
  finally { busyId.value = null }
}

const verdictOptions: { value: DiagnosisVerdict; label: string }[] = [
  { value: 'UNVERIFIED', label: '尚未核实' },
  { value: 'CONFIRMED', label: '与现场发现相符' },
  { value: 'DIFFERENT', label: '与现场发现不符' },
]

function verdictLabel(value: DiagnosisVerdict) {
  return verdictOptions.find((option) => option.value === value)?.label
}

async function close(order: WorkOrder) {
  const values = await dialog.form({
    title: '闭环维修工单',
    description: `工单 WO-${String(order.id).padStart(5, '0')} · ${order.source === 'MANUAL' || order.alertId == null ? '人工点检工单' : `系统诊断：${order.dominantFactor}`}`,
    confirmLabel: '完成闭环',
    fields: [
      { name: 'verdict', label: '现场诊断核对', type: 'select', value: 'UNVERIFIED', options: verdictOptions, required: true },
      { name: 'finding', label: '现场发现', type: 'textarea', placeholder: '记录现场发现的异常或确认的故障', maxLength: 2000 },
      { name: 'action', label: '已采取的措施', type: 'textarea', placeholder: '记录复测、检修、更换等处理措施', maxLength: 2000 },
      { name: 'note', label: '检修结论 / 处理记录', type: 'textarea', placeholder: '记录处理结果或后续事项', required: true },
      { name: 'resetBaseline', label: '检修后基线处理', type: 'select', value: 'yes', options: [{ value: 'yes', label: '申请设备基线重学（换件/对中大修推荐）' }, { value: 'no', label: '保留当前锁定基线' }], required: true },
    ],
  })
  if (!values) return
  const resolutionNote = values.note.trim()
  if (!resolutionNote) {
    error.value = '请输入检修结论或处理记录。'
    return
  }
  busyId.value = order.id
  error.value = ''
  try {
    await closeWorkOrder(order.id, {
      resolutionNote,
      diagnosisVerdict: verdictOptions.find((option) => option.value === values.verdict)?.value,
      actualFinding: values.finding.trim() || undefined,
      actionTaken: values.action.trim() || undefined,
      requestBaselineReset: values.resetBaseline === 'yes',
    })
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '闭环失败'
  } finally {
    busyId.value = null
  }
}
const time = (value: string) => new Date(value).toLocaleString()
</script>

<template>
  <div class="page-stack">
    <div class="page-intro">
      <div><h2>设备维修工单</h2><p>工单围绕设备级风险建立，支持告警转单与日常点检手工建单；维修完成后记录现场发现、处置措施和诊断反馈。</p></div>
      <div class="intro-actions">
        <button v-if="canManage" class="small-button primary-small" @click="openManualDialog">＋ 新建手工工单</button>
        <button class="quiet-button" @click="load">↻ 刷新工单</button>
      </div>
    </div>
    <div v-if="error" class="notice error-notice">{{ error }}</div>
    <div v-if="note" class="notice info-notice">{{ note }}</div>
    <section class="panel filter-panel" aria-label="工单筛选">
      <label class="filter-item">车间
        <select v-model="filters.factoryCode" @change="applyFilters"><option value="">全部车间</option><option v-for="factory in factories" :key="factory.id" :value="factory.factoryCode">{{ factory.factoryName }}（{{ factory.factoryCode }}）</option></select>
      </label>
      <label class="filter-item">状态
        <select v-model="filters.status" @change="applyFilters"><option value="ALL">全部状态</option><option value="PENDING">待办（未闭环）</option><option value="OPEN">待处理</option><option value="IN_PROGRESS">处理中</option><option value="CLOSED">已闭环</option><option value="SUPPRESSED">已抑制</option></select>
      </label>
      <label class="filter-item">优先级
        <select v-model="filters.priority" @change="applyFilters"><option value="ALL">全部优先级</option><option value="URGENT">紧急</option><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option></select>
      </label>
      <label class="filter-item">来源
        <select v-model="filters.source" @change="applyFilters"><option value="ALL">全部来源</option><option value="ALERT">告警转单</option><option value="MANUAL">手工建单</option></select>
      </label>
      <label class="filter-item grow">关键字
        <input v-model="keywordInput" placeholder="设备 / 工单标题 / 处理人，回车查询" @keyup.enter="applyFilters" />
      </label>
      <div class="filter-actions">
        <button class="small-button primary-small" @click="applyFilters">查询</button>
        <button class="quiet-button" @click="resetFilters">重置筛选</button>
      </div>
    </section>
    <section class="panel table-panel"><div class="panel-heading"><div><h3>工单列表</h3><p>服务端分页筛选，每页 {{ PAGE_SIZE }} 条</p></div><span class="count-chip">{{ pendingCount }} 项待办 · 共 {{ pageData?.totalItems ?? 0 }} 条</span></div><div class="table-wrap"><table><thead><tr><th>工单</th><th>设备 / 测点</th><th>等级 / 优先级</th><th>主导因素</th><th>处理人</th><th>状态</th><th>创建时间</th><th>操作 / 维修反馈</th></tr></thead><tbody>
      <tr v-for="order in orders" :key="order.id">
        <td>
          <b class="mono">WO-{{ String(order.id).padStart(5, '0') }}</b>
          <small v-if="order.source === 'MANUAL' || order.alertId == null">手工建单</small>
          <small v-else>关联告警 #{{ order.alertId }}</small>
        </td>
        <td><RouterLink :to="`/devices/${order.deviceId}`" class="table-link"><b>{{ order.deviceName }}</b></RouterLink><small>{{ order.deviceCode }} · {{ order.pointCode ? `关联测点 ${order.pointCode}` : '整机点检 / 检修' }}</small><small v-if="order.evidencePoints.length">证据：{{ order.evidencePoints.map(point => `${point.pointCode} ${point.healthIndex?.toFixed(1) ?? '—'} · ${pointStatusLabel(point.status)}`).join('；') }}</small></td>
        <td>
          <span v-if="order.alertLevel" :class="`status-tag ${alertLevelState(order.alertLevel).tone}`"><i></i>{{ alertLevelState(order.alertLevel).label }}</span>
          <span v-else class="status-tag blue"><i></i>优先级 {{ priorityLabels[order.priority ?? 'MEDIUM'] }}</span>
          <small v-if="order.healthIndex != null">HI {{ order.healthIndex.toFixed(1) }}</small>
        </td>
        <td class="factor-cell">{{ order.dominantFactor }}</td>
        <td>{{ order.assignee || '未分派' }}</td>
        <td><span :class="`status-tag ${workOrderStatusState(order.status).tone}`"><i></i>{{ workOrderStatusState(order.status).label }}</span></td>
        <td>{{ time(order.createdAt) }}</td>
        <td>
          <div v-if="!['CLOSED','SUPPRESSED'].includes(order.status)" class="action-row">
            <button class="small-button" :disabled="busyId === order.id || !auth.hasRole('OPERATOR') && !auth.hasRole('ADMIN')" @click="assign(order)">分派</button>
            <button class="small-button primary-small" :disabled="busyId === order.id || !auth.hasRole('OPERATOR') && !auth.hasRole('ADMIN')" @click="close(order)">闭环</button>
          </div>
          <div v-else class="feedback">
            <b v-if="order.diagnosisVerdict">{{ verdictLabel(order.diagnosisVerdict) }}</b>
            <small v-if="order.actualFinding">发现：{{ order.actualFinding }}</small>
            <small v-if="order.actionTaken">措施：{{ order.actionTaken }}</small>
            <small>{{ order.resolutionNote || '—' }}</small>
          </div>
        </td>
      </tr>
      <tr v-if="!orders.length"><td colspan="8" class="empty-cell">{{ pageData == null ? '正在加载工单…' : '当前筛选条件下暂无维修工单。告警会自动转单，也可点击右上角“＋ 新建手工工单”为日常点检问题建单。' }}</td></tr>
    </tbody></table></div>
      <nav v-if="totalPages > 1" class="page-nav" aria-label="工单分页">
        <button class="small-button" :disabled="busyId != null || page === 0" @click="goToPage(page - 1)">上一页</button>
        <span>第 {{ page + 1 }} / {{ totalPages }} 页 · 共 {{ pageData?.totalItems ?? 0 }} 条</span>
        <button class="small-button" :disabled="busyId != null || page + 1 >= totalPages" @click="goToPage(page + 1)">下一页</button>
      </nav>
    </section>

    <dialog ref="formDialog" class="manual-dialog">
      <form class="manual-form" @submit.prevent="submitManual">
        <h3>新建手工维修工单</h3>
        <p>日常点检发现跑冒滴漏、异响、渗漏等问题时，直接为设备创建独立检修工单（可精确到测点，默认整机检修）。</p>
        <div v-if="dialogError" class="notice error-notice">{{ dialogError }}</div>
        <label>所属车间
          <select v-model="manual.factoryCode" @change="onManualFactoryChange"><option value="">请选择车间…</option><option v-for="factory in factories" :key="factory.id" :value="factory.factoryCode">{{ factory.factoryName }}（{{ factory.factoryCode }}）</option></select>
        </label>
        <label>目标设备
          <select v-model="manual.deviceId" :disabled="!manual.factoryCode" @change="onManualDeviceChange"><option :value="null">{{ manual.factoryCode ? '请选择设备…' : '请先选择车间' }}</option><option v-for="item in dialogDevices" :key="item.id" :value="item.id">{{ item.deviceName }}（{{ item.deviceCode }}）</option></select>
        </label>
        <label>关联测点（可选）
          <select v-model="manual.pointId" :disabled="manual.deviceId == null"><option :value="null">整机点检 / 检修</option><option v-for="item in dialogPoints" :key="item.id" :value="item.id">{{ item.pointName }}（{{ item.pointCode }}）</option></select>
        </label>
        <label>工单标题 / 故障现象<span class="required-mark">*</span>
          <input v-model="manual.title" :maxlength="128" placeholder="例如：填料函滴漏、驱动端异响" required />
        </label>
        <label>优先级
          <select v-model="manual.priority"><option value="URGENT">紧急</option><option value="HIGH">高</option><option value="MEDIUM">中（默认）</option><option value="LOW">低</option></select>
        </label>
        <label>计划检修措施
          <textarea v-model="manual.maintenanceAction" :maxlength="2000" rows="3" placeholder="记录拟采取的检修步骤、备件需求等"></textarea>
        </label>
        <label>指派处理人（可选，填写后工单直接进入“处理中”）
          <input v-model="manual.assignee" :maxlength="80" placeholder="处理人用户名，留空则待分派" />
        </label>
        <div class="dialog-actions">
          <button type="button" class="quiet-button" @click="formDialog?.close()">取消</button>
          <button type="submit" class="small-button primary-small" :disabled="dialogBusy">{{ dialogBusy ? '创建中…' : '创建工单' }}</button>
        </div>
      </form>
    </dialog>
  </div>
</template>

<style scoped>
.feedback { min-width: 140px; max-width: 260px; white-space: normal; overflow-wrap: anywhere; }
.intro-actions { display: flex; align-items: center; gap: .6rem; }
.filter-panel { display: flex; flex-wrap: wrap; align-items: flex-end; gap: .85rem; padding: .9rem 1rem; }
.filter-item { display: grid; gap: .3rem; color: #92a4ba; font-size: 10px; }
.filter-item.grow { flex: 1; min-width: 200px; }
.filter-item select, .filter-item input { border: 1px solid #293c53; border-radius: 7px; background: #0b1727; color: #e0e9f4; padding: 8px 9px; outline: 0; font-size: 11px; min-width: 130px; }
.filter-item input { width: 100%; min-width: 0; }
.filter-item select:focus, .filter-item input:focus { border-color: #49bfae; box-shadow: 0 0 0 3px #49bfae17; }
.filter-actions { display: flex; align-items: center; gap: .5rem; }
.page-nav { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: .75rem; padding: .75rem 1rem; color: #8ea1b9; font-size: 10px; }
.manual-dialog { border: 1px solid #2a3f58; border-radius: 12px; background: #101d2e; color: #e7eef8; padding: 0; width: min(480px, calc(100vw - 32px)); }
.manual-dialog::backdrop { background: #040913b3; backdrop-filter: blur(2px); }
.manual-form { display: grid; gap: .8rem; padding: 1.2rem 1.3rem 1.3rem; }
.manual-form h3 { margin: 0; font-size: 14px; }
.manual-form p { margin: 0; color: #7e91aa; font-size: 10px; line-height: 1.55; }
.manual-form label { display: grid; gap: .35rem; color: #92a4ba; font-size: 10px; }
.manual-form select, .manual-form input, .manual-form textarea { width: 100%; border: 1px solid #293c53; border-radius: 7px; background: #0b1727; color: #e0e9f4; padding: 9px 10px; outline: 0; font-size: 11px; font-family: inherit; resize: vertical; }
.manual-form select:focus, .manual-form input:focus, .manual-form textarea:focus { border-color: #49bfae; box-shadow: 0 0 0 3px #49bfae17; }
.manual-form select:disabled, .manual-form input:disabled { opacity: .55; }
.required-mark { color: var(--red); }
.dialog-actions { display: flex; justify-content: flex-end; gap: .6rem; margin-top: .2rem; }
</style>
