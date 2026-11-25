<script setup lang="ts">
import { computed, defineAsyncComponent, defineComponent, h, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import { getAlerts, getAssetTree, getChannelBaseline, getChannelSeries, getLatestSnapshot, getWorkOrders, loadModalityCatalog, openModalityStream } from '@/api/pdm'
import type { Alert, AssetFactoryNode, AssetMachineNode, AssetPointNode, ChannelBaseline, ChannelSeriesRow, ChannelSnapshot, ModalityCatalog, ModalityStreamFrame, WorkOrder } from '@/api/types'
import GenericMetricsWorkbench from '@/components/workbenches/GenericMetricsWorkbench.vue'
import ChannelTrendChart from '@/components/workbenches/ChannelTrendChart.vue'
import { errorMessage, STREAM, useStreamFallback, type SharedChannelData, type WorkbenchContext } from '@/components/workbenches/shared'
import { alertLevelState, baselineState, pointStatusLabel, statusTone, workOrderStatusState } from '@/telemetry-status'
import { cadenceLabel, defaultTrendFeature, featureTitle, formatDuration, formatNumber, freshnessRule, healthLevel, isEventChannel, isVibration, latestHealthIndex, mergeLiveRow, modalityName, modalityRank, modalityShortName, modalityTone, reportFreshness, staleAfterSeconds, timeAgo } from '@/modality'

/**
 * 多模态专家诊断工作站 · 统一外框（设计文档 §5）：
 * 资产树导航（按模态分组）+ 通道摘要 + 统一劣化趋势 + 本设备告警/待办工单侧栏，
 * 再按测点 modality_type 动态挂载专家工作台组件；v2 SSE 由外框统一持有（振动模态除外，
 * 其 2Hz 频谱流仍由旋转专家视图自持）。外框统一拉取时序 / 基线 / 最近快照并下发给子工作台复用。
 */

// ==== 专家视图分包加载：加载态 / 失败态（带重试）====
const workbenchReload = ref(0)
// inheritAttrs:false —— 外框传给专家视图的 point / context / shared 等会作为透传属性落到占位组件的根节点上，
// 不关闭就会以 point="[object Object]" 这类 DOM 属性渲染出来。
const WorkbenchLoading = defineComponent({
  name: 'WorkbenchLoading',
  inheritAttrs: false,
  setup: () => () => h('div', { class: 'panel empty-cell', role: 'status' }, '正在加载专家视图…'),
})
/** Vue 的 errorComponent 只接收 error 入参、不透传事件，重试经闭包回调通知外框重建异步组件。 */
function workbenchErrorComponent(retry: () => void) {
  return defineComponent({
    name: 'WorkbenchLoadError',
    inheritAttrs: false,
    setup: () => () => h('div', { class: 'notice error-notice', role: 'alert', style: 'flex-wrap: wrap; gap: 10px' }, [
      h('span', '专家视图加载失败，请检查网络后重试。'),
      h('button', { type: 'button', class: 'small-button', style: 'margin-left: auto', onClick: retry }, '重试'),
    ]),
  })
}
const workbenchLoaders: Record<string, () => Promise<{ default: Component }>> = {
  ROTATING_VIB: () => import('@/components/workbenches/RotatingVibrationWorkbench.vue'),
  SWITCHGEAR_EVENT: () => import('@/components/workbenches/SwitchgearStrokeWorkbench.vue'),
  CONTROL_VALVE_PROCESS: () => import('@/components/workbenches/ControlValveHysteresisWorkbench.vue'),
}
const workbenchCache = new Map<string, Component>()

const route = useRoute()
const router = useRouter()

const tree = ref<AssetFactoryNode[]>([])
const catalogs = ref<ModalityCatalog[]>([])
const selectedPoint = ref<AssetPointNode | null>(null)
const treeError = ref('')
const dataError = ref('')
const busy = ref(false)

const baseline = ref<ChannelBaseline | null>(null)
const baselineLoad = ref<'LOADING' | 'READY' | 'FAILED'>('LOADING')
const rows = ref<ChannelSeriesRow[]>([])
const seriesFeatures = ref<string[]>([])
/** 最近动作快照：null = 确认没有快照（404）；undefined = 未取或取失败（由子工作台自行拉取）。 */
const snapshot = ref<ChannelSnapshot | null | undefined>(undefined)
const channelStatus = ref<'LOADING' | 'READY'>('LOADING')
const trendRange = ref('24h')
const trendFeature = ref('')
const refreshKey = ref(0)
/** 每 30 秒推进一次，使相对时间 / 过期判断随时间刷新。 */
const nowTick = ref(Date.now())
/** 最新一帧指标帧的评分与样本状态（未评分帧 healthIndex 为 null）。 */
const liveHealth = ref<{ timestamp: number; healthIndex: number | null; status: string | null } | null>(null)
const liveReportedAt = ref<number | null>(null)
const replaySeen = ref(false)

const alertsAll = ref<Alert[]>([])
const workOrdersAll = ref<WorkOrder[]>([])
const realtimePayload = ref<ModalityStreamFrame | null>(null)
let alertTimer: number | undefined
let loadSequence = 0

const trendRanges = [
  { value: '1h', label: '1 小时', ms: 60 * 60 * 1000, step: '1m' },
  { value: '24h', label: '24 小时', ms: 24 * 60 * 60 * 1000, step: '5m' },
  { value: '7d', label: '7 天', ms: 7 * 24 * 60 * 60 * 1000, step: '1h' },
  { value: '30d', label: '30 天', ms: 30 * 24 * 60 * 60 * 1000, step: '6h' },
]
const currentRange = () => trendRanges.find((item) => item.value === trendRange.value) ?? trendRanges[1]

const isVib = computed(() => isVibration(selectedPoint.value?.modalityType))
const selectedCatalog = computed(() => catalogFor(selectedPoint.value?.modalityType))
const context = computed<WorkbenchContext>(() => {
  const located = selectedPoint.value ? machineOf(selectedPoint.value) : null
  return { factory: located?.factory ?? null, machine: located?.machine ?? null, catalog: selectedCatalog.value }
})
/** 下发给子工作台的通道数据（子工作台直接复用，不再各发一遍请求）。 */
const shared = computed<SharedChannelData>(() => ({
  status: channelStatus.value, rows: rows.value, features: seriesFeatures.value, baseline: baseline.value, snapshot: snapshot.value,
}))
/**
 * 当前 HI：取时序里最近一次评分；若最新并入的实时帧尚未评分（基线学习期），HI 保持为空而不是回退到更早的分数。
 * 末位兜底才是资产树节点上的静态值。
 */
const stripHi = computed(() => {
  const last = rows.value[rows.value.length - 1]
  if (liveHealth.value && last && last.timestamp === liveHealth.value.timestamp) return last.health_index ?? null
  return latestHealthIndex(rows.value) ?? selectedPoint.value?.healthIndex ?? null
})
const stripHealth = computed(() => healthLevel(stripHi.value))
/** 测点状态文案取自最新一行时序（或最新实时帧）的样本状态，资产树里的静态快照只作兜底。 */
const currentStatus = computed(() => {
  const last = rows.value[rows.value.length - 1]
  return last?.status || liveHealth.value?.status || selectedPoint.value?.lastStatus || null
})
const baselineMeta = computed(() => baselineState(baseline.value?.status))
const baselineNote = computed(() => {
  const item = baseline.value
  if (!item) return '该通道尚未建立基线'
  const window = item.learningWindowSeconds
    ? item.pooled ? ` · 样本保留 ${formatDuration(item.learningWindowSeconds)}` : ' · 学习窗口 ' + formatDuration(item.learningWindowSeconds)
    : ''
  return `${item.learnedSamples}/${item.minimumSamples} 样本 · ${item.pooled ? '池化学习' : '按点学习'}${window}`
})
const isEvent = computed(() => !!selectedPoint.value && isEventChannel(selectedPoint.value.modalityType, selectedCatalog.value))
const staleAfter = computed(() => (selectedPoint.value ? staleAfterSeconds(selectedPoint.value.cadence, selectedCatalog.value) : null))
/** “最近上报 … / 数据过期”：按模态新鲜度阈值（目录 staleAfterSeconds）判定，取资产树 / 时序 / 实时帧中最近的时间。 */
const freshness = computed(() => {
  const point = selectedPoint.value
  if (!point) return null
  const lastRowAt = rows.value.length ? rows.value[rows.value.length - 1].timestamp : null
  const reportedAt = [point.lastTelemetryAt, liveReportedAt.value, lastRowAt]
    .map((value) => (value == null || value === '' ? NaN : new Date(value).getTime()))
    .filter((value) => Number.isFinite(value)) as number[]
  return reportFreshness(reportedAt.length ? Math.max(...reportedAt) : null, staleAfter.value, { now: nowTick.value, event: isEvent.value })
})
/** 事件型通道“最近一次动作”：优先最近快照时间，没有快照时回落到最近一次上报时间。 */
const lastEventAt = computed(() => snapshot.value?.timestamp ?? freshness.value?.reportedAt ?? null)

const trendFeatureOptions = computed(() => {
  const catalog = selectedCatalog.value
  const options: string[] = []
  const add = (code?: string | null) => { if (code && !options.includes(code)) options.push(code) }
  add(defaultTrendFeature(selectedPoint.value?.modalityType, catalog, seriesFeatures.value))
  add(catalog?.primaryFeatureCode)
  for (const code of seriesFeatures.value) add(code)
  for (const state of baseline.value?.featureStates ?? []) add(state.featureKey)
  for (const code of catalog?.seriesFeatureCodes ?? []) add(code)
  return options
})
const deviceAlerts = computed(() => {
  const deviceId = context.value.machine?.id
  return deviceId == null ? [] : alertsAll.value.filter((alert) => alert.deviceId === deviceId).slice(0, 8)
})
/** 本设备待办工单（待处理 / 处理中），与告警并列在侧栏。 */
const deviceWorkOrders = computed(() => {
  const deviceId = context.value.machine?.id
  return deviceId == null ? [] : workOrdersAll.value.filter((order) => order.deviceId === deviceId && ['OPEN', 'IN_PROGRESS'].includes(order.status))
})

// 未登记模态（含 MCSA）走通用兜底视图，不抛错——避免整页空白；已登记模态分包加载，失败可重试。
const resolvedWorkbench = computed<Component>(() => {
  const type = selectedPoint.value?.modalityType ?? ''
  const loader = workbenchLoaders[type]
  if (!loader) return GenericMetricsWorkbench
  const key = `${type}#${workbenchReload.value}` // 重试计数变化即丢弃加载失败的异步组件并重建
  let component = workbenchCache.get(key)
  if (!component) {
    component = defineAsyncComponent({
      loader,
      delay: 150,
      timeout: 30000,
      loadingComponent: WorkbenchLoading,
      errorComponent: workbenchErrorComponent(() => { workbenchReload.value++ }),
      onError: (_error, retry, fail, attempts) => { if (attempts <= 2) retry(); else fail() },
    })
    workbenchCache.set(key, component)
  }
  return component
})

function catalogFor(type?: string | null) {
  return type ? catalogs.value.find((item) => item.modalityType === type) ?? null : null
}

function machineOf(point: AssetPointNode): { factory: AssetFactoryNode; machine: AssetMachineNode } | null {
  for (const factory of tree.value) {
    for (const machine of factory.children) {
      if (machine.children.some((candidate) => candidate.id === point.id)) return { factory, machine }
    }
  }
  return null
}

function findPoint(id: number): AssetPointNode | null {
  for (const factory of tree.value) for (const machine of factory.children) {
    const match = machine.children.find((point) => point.id === id)
    if (match) return match
  }
  return null
}

/** 资产树标签：色调取自健康分级（而不是基线状态——基线已锁定的危险测点不能显示为绿色）；尚无评分的测点显示基线状态。 */
function pointTag(point: AssetPointNode) {
  const current = point.id === selectedPoint.value?.id
  const hi = current ? stripHi.value : point.healthIndex
  const health = healthLevel(hi)
  if (health) return { label: `HI ${formatNumber(hi, 1)}`, tone: health.tone, title: pointStatusLabel(current ? currentStatus.value : point.lastStatus) }
  const state = baselineState(current ? baseline.value?.status ?? point.baselineStatus : point.baselineStatus)
  return { label: state.label, tone: state.tone, title: '尚无健康评分（基线未锁定）' }
}
function machineTag(machine: AssetMachineNode) {
  const health = healthLevel(machine.healthIndex)
  return health
    ? { label: `HI ${formatNumber(machine.healthIndex, 1)}`, tone: health.tone }
    : { label: pointStatusLabel(machine.status), tone: statusTone(machine.status) }
}

/** 树内按模态分组展示（组序按登记顺序，未登记模态殿后）。 */
function groupedPoints(machine: AssetMachineNode) {
  const groups: { type: string; points: AssetPointNode[] }[] = []
  for (const point of machine.children) {
    const type = point.modalityType ?? ''
    const existing = groups.find((group) => group.type === type)
    if (existing) existing.points.push(point)
    else groups.push({ type, points: [point] })
  }
  return groups.sort((a, b) => modalityRank(a.type) - modalityRank(b.type))
}

async function loadTree() {
  treeError.value = ''
  try {
    tree.value = await getAssetTree()
    if (selectedPoint.value) return
    const wanted = Number(route.query.pointId)
    const wantedPoint = Number.isSafeInteger(wanted) && wanted > 0 ? findPoint(wanted) : null
    // 地址栏 ?pointId 指定的测点优先（刷新后保持选择），否则取树里的第一个测点
    const first = wantedPoint ?? tree.value[0]?.children[0]?.children[0]
    if (first) void selectPoint(first)
  } catch (e) {
    treeError.value = e instanceof Error ? e.message : '资产树加载失败'
  }
}

async function loadCatalogs() {
  // 目录缺失时按通道自带字段与静态兜底降级展示
  catalogs.value = await loadModalityCatalog()
}

async function loadAlerts() {
  try { alertsAll.value = await getAlerts() } catch { /* 侧栏告警加载失败不打断主视图 */ }
}

async function loadWorkOrders() {
  try { workOrdersAll.value = await getWorkOrders() } catch { /* 工单侧栏加载失败不打断主视图 */ }
}

function syncTrendFeature() {
  const current = trendFeature.value
  if (current && trendFeatureOptions.value.includes(current)) return
  trendFeature.value = defaultTrendFeature(selectedPoint.value?.modalityType, selectedCatalog.value, seriesFeatures.value) || trendFeatureOptions.value[0] || ''
}

/** 需要最近动作快照的通道：事件型通道（“最近一次动作”读数；断路器专家视图复用同一份快照）。 */
const needsSnapshot = (point: AssetPointNode) => point.cadence === 'EVENT' || point.modalityType === 'SWITCHGEAR_EVENT'

/**
 * 拉取通道时序 / 基线 / 最近快照。reset=false（切换时间窗、轮询刷新）时保留旧数据直到新数据到达，避免图表闪空。
 * 返回时序是否成功——实时流的重试只在其成功之后进行。
 */
async function loadChannel(point: AssetPointNode, reset: boolean): Promise<boolean> {
  const sequence = ++loadSequence
  const endTs = Date.now()
  const range = currentRange()
  const startTs = endTs - range.ms
  if (reset) {
    dataError.value = ''
    busy.value = true
    baseline.value = null
    rows.value = []
    seriesFeatures.value = []
    snapshot.value = undefined
    trendFeature.value = ''
    liveHealth.value = null
    liveReportedAt.value = null
    baselineLoad.value = 'LOADING'
    channelStatus.value = 'LOADING'
  }
  try {
    // allSettled：时序失败（如时序库报错）不连带清空基线与最近动作时间，各卡片独立降级
    const [base, series, snap] = await Promise.allSettled([
      getChannelBaseline(point.id),
      getChannelSeries(point.id, startTs, endTs, range.step),
      needsSnapshot(point) ? getLatestSnapshot(point.id).catch((e) => (e instanceof ApiError && e.status === 404 ? null : Promise.reject(e))) : Promise.resolve(undefined),
    ])
    if (sequence !== loadSequence || selectedPoint.value?.id !== point.id) return false
    baseline.value = base.status === 'fulfilled' ? base.value : null
    baselineLoad.value = base.status === 'fulfilled' ? 'READY' : 'FAILED'
    if (snap.status === 'fulfilled') snapshot.value = snap.value
    else if (reset) snapshot.value = undefined
    if (series.status === 'fulfilled') {
      rows.value = series.value.rows
      seriesFeatures.value = series.value.features
      dataError.value = ''
    } else dataError.value = errorMessage(series.reason, '通道数据加载失败')
    syncTrendFeature()
    channelStatus.value = 'READY'
    return series.status === 'fulfilled'
  } catch (e) {
    if (sequence === loadSequence) dataError.value = errorMessage(e, '通道数据加载失败')
    return false
  } finally {
    if (sequence === loadSequence && reset) busy.value = false
  }
}

/** 基线卡片单独重试（基线接口失败不影响时序与趋势）。 */
async function retryBaseline() {
  const point = selectedPoint.value
  if (!point) return
  const sequence = loadSequence
  baselineLoad.value = 'LOADING'
  try {
    const item = await getChannelBaseline(point.id)
    if (sequence !== loadSequence || selectedPoint.value?.id !== point.id) return
    baseline.value = item
    baselineLoad.value = 'READY'
  } catch {
    if (sequence === loadSequence && selectedPoint.value?.id === point.id) baselineLoad.value = 'FAILED'
  }
}

async function selectPoint(point: AssetPointNode) {
  if (selectedPoint.value?.id === point.id) return
  selectedPoint.value = point
  realtimePayload.value = null
  replaySeen.value = false
  // 选中测点同步到地址栏（?pointId=），刷新页面后仍停留在该测点
  void router.replace({ query: { ...route.query, pointId: String(point.id) } }).catch(() => { /* 导航被中止时忽略 */ })
  void loadChannel(point, true)
  void loadAlerts()
  void loadWorkOrders()
  stream.start()
}

// 地址栏 ?pointId 变化（其它页面带入、前进/后退）时同步选中测点
watch(() => route.query.pointId, (value) => {
  const id = Number(value)
  const point = Number.isSafeInteger(id) ? findPoint(id) : null
  if (point) void selectPoint(point)
})

// ==== 实时流 + 轮询兜底（状态机见 shared.ts）====
let subscribedAt = 0
let framesSeen = 0
/** 轮询兜底 / 保底刷新：真实拉取时序 + 基线 + 最近快照，并让子工作台刷新各自负责的数据（如调节阀回环）。 */
async function refreshLive(): Promise<boolean> {
  const point = selectedPoint.value
  if (!point) return false
  const ok = await loadChannel(point, false)
  if (ok && selectedPoint.value?.id === point.id) refreshKey.value++
  return ok
}
const stream = useStreamFallback({
  refresh: () => refreshLive(),
  open: (control) => {
    const point = selectedPoint.value
    if (!point || isVibration(point.modalityType)) return null
    return openModalityStream(point.id, {
      onFrame: (frame) => { if (selectedPoint.value?.id === point.id) handleFrame(point, frame) },
      // 每次建立连接都是一次新的订阅：服务端会立即补推一帧“当前快照”，据此重新开始回放判定
      onOpen: () => { subscribedAt = Date.now(); framesSeen = 0; control.onOpen?.() },
      onError: () => control.onError?.(),
    })
  },
})
const { state: streamState, label: streamLabel, tone: streamTone } = stream
const streamTitle = computed(() => (streamState.value === 'WAITING' && replaySeen.value ? '订阅时已回放最近一次快照（历史数据，非实时事件）' : ''))

/** 实时帧：校验归属与时间戳；订阅时回放的历史快照不当作实时事件（REST 已提供同一份数据），也不据此进入 LIVE。 */
function handleFrame(point: AssetPointNode, frame: ModalityStreamFrame) {
  if (!Number.isFinite(frame.timestamp)) return // 坏帧
  const channel = frame.channel_code
  if (channel != null && String(channel) !== point.code && String(channel) !== String(point.id)) return // 串到其它通道的帧
  // 回放：连接后的第一帧、且时间戳早于订阅时刻（容忍浏览器与服务端的时钟偏差）
  const replay = framesSeen === 0 && frame.timestamp < subscribedAt - STREAM.REPLAY_SKEW_MS
  framesSeen++
  if (replay) {
    replaySeen.value = true
    return
  }
  stream.acceptLive()
  realtimePayload.value = frame
  liveReportedAt.value = Math.max(liveReportedAt.value ?? 0, frame.timestamp)
  nowTick.value = Date.now()
  if (frame.frame_type === 'METRICS_FRAME') {
    // 未评分帧（基线学习 / 工况不可评估）health_index 为 null：照常并入特征值，HI 留空
    rows.value = mergeLiveRow(rows.value, frame, currentRange().ms)
    liveHealth.value = { timestamp: frame.timestamp, healthIndex: frame.health_index ?? null, status: frame.status || frame.alarm_level || null }
  }
}

watch(trendRange, () => {
  const point = selectedPoint.value
  if (point) void loadChannel(point, false)
})

onMounted(() => {
  void loadCatalogs()
  void loadTree()
  alertTimer = window.setInterval(() => {
    nowTick.value = Date.now()
    if (selectedPoint.value) {
      void loadAlerts()
      void loadWorkOrders()
    }
  }, 30000)
})
onBeforeUnmount(() => {
  stream.stop()
  if (alertTimer != null) window.clearInterval(alertTimer)
})
</script>

<template>
  <div class="page-stack workbench">
    <div class="page-intro workbench-intro">
      <div class="workbench-intro-copy">
        <span class="workbench-kicker"><i></i>实时诊断工作站</span>
        <h2>{{ selectedPoint?.name || '请选择测点' }}</h2>
        <div v-if="selectedPoint" class="workbench-meta" aria-label="测点信息">
          <span class="workbench-meta-item"><span>设备</span><b>{{ machineOf(selectedPoint)?.machine.name }}</b></span>
          <span class="workbench-meta-item"><span>测点编号</span><code>{{ selectedPoint.code }}</code></span>
          <span v-if="selectedPoint.pluginIdentifier" class="workbench-meta-item"><span>诊断插件</span><code>{{ selectedPoint.pluginIdentifier }}</code></span>
        </div>
        <p v-else>在左侧资产树中选择测点，查看对应的专家诊断视图。</p>
      </div>
      <div class="workbench-actions">
        <span v-if="selectedPoint" :class="['status-tag', modalityTone(selectedPoint.modalityType)]"><i></i>{{ modalityName(selectedPoint.modalityType) }}</span>
        <span v-if="selectedPoint && !isVib" :class="['status-tag', streamTone]" :title="streamTitle"><i></i>{{ streamLabel }}</span>
        <span v-if="freshness" :class="['status-tag', freshness.tone]" :title="freshnessRule(selectedPoint?.cadence, staleAfter)"><i></i>{{ freshness.label }}</span>
      </div>
    </div>
    <div v-if="treeError" class="notice error-notice">{{ treeError }}</div>
    <div v-if="dataError" class="notice error-notice">{{ dataError }}</div>

    <div class="workbench-layout">
      <aside class="panel tree-panel">
        <div class="panel-heading tree-heading">
          <div><h3>资产导航树</h3><p>按层级选择诊断测点</p></div>
          <span class="tree-level-count">3 层</span>
        </div>
        <div class="tree-scroll">
          <details v-for="factory in tree" :key="factory.id" class="tree-factory" open>
            <summary class="tree-node-summary tree-factory-summary">
              <span class="tree-chevron" aria-hidden="true"></span>
              <span class="tree-node-icon factory-icon" aria-hidden="true">厂</span>
              <span class="tree-node-copy"><b>{{ factory.name }}</b><small>{{ factory.code }}</small></span>
            </summary>
            <details v-for="machine in factory.children" :key="machine.id" class="tree-machine" open>
              <summary class="tree-node-summary tree-machine-summary">
                <span class="tree-chevron" aria-hidden="true"></span>
                <span class="tree-node-icon machine-icon" aria-hidden="true">⚙</span>
                <span class="tree-node-copy"><b>{{ machine.name }}</b></span>
                <span :class="['status-tag', 'tree-machine-health', machineTag(machine).tone]"><i></i>{{ machineTag(machine).label }}</span>
              </summary>
              <template v-for="group in groupedPoints(machine)" :key="group.type || 'unknown'">
                <div class="tree-modality"><span class="tree-branch" aria-hidden="true"></span><span :class="['status-tag', modalityTone(group.type)]"><i></i>{{ modalityName(group.type) }}</span></div>
                <button v-for="point in group.points" :key="point.id"
                  :class="['tree-point', selectedPoint?.id === point.id ? 'tree-point-active' : '']"
                  @click="selectPoint(point)">
                  <span class="tree-branch" aria-hidden="true"></span>
                  <span class="tree-point-icon" aria-hidden="true">⌖</span>
                  <span class="tree-point-copy"><b>{{ point.name }}</b><small>{{ point.code }}</small></span>
                  <span class="tree-point-tags">
                    <span :class="['status-tag', modalityTone(point.modalityType)]">{{ modalityShortName(point.modalityType) }}</span>
                    <span :class="['status-tag', pointTag(point).tone]" :title="pointTag(point).title">{{ pointTag(point).label }}</span>
                  </span>
                </button>
              </template>
            </details>
          </details>
          <p v-if="!tree.length && !treeError" class="empty-cell">暂无资产</p>
        </div>
      </aside>

      <section class="workbench-main">
        <section v-if="selectedPoint" class="metric-grid compact-metrics">
          <article class="metric-card">
            <div class="metric-top"><span>当前健康指数（HI）</span><span v-if="stripHealth" :class="['status-tag', stripHealth.tone]"><i></i>{{ stripHealth.label }}</span></div>
            <strong>{{ formatNumber(stripHi, 1) }}</strong>
            <small :class="['tone-text', statusTone(currentStatus)]">{{ pointStatusLabel(currentStatus) }}{{ realtimePayload ? ' · 已并入实时帧' : '' }}</small>
          </article>
          <article class="metric-card">
            <div class="metric-top"><span>通道基线</span>
              <span v-if="baseline" :class="['status-tag', baselineMeta.tone]"><i></i>{{ baselineMeta.label }}</span>
              <b v-else class="metric-icon teal">⌁</b>
            </div>
            <strong v-if="baselineLoad === 'LOADING'">加载中…</strong>
            <strong v-else-if="baselineLoad === 'FAILED'" class="tone-text red">基线加载失败</strong>
            <strong v-else>{{ baselineMeta.label }}</strong>
            <small v-if="baselineLoad === 'FAILED'" class="baseline-failed">暂时无法获取通道基线。<button class="small-button" @click="retryBaseline">重试</button></small>
            <small v-else-if="baselineLoad === 'LOADING'">正在加载通道基线…</small>
            <small v-else>{{ baselineNote }}</small>
          </article>
          <article class="metric-card">
            <div class="metric-top"><span>采集方式</span><b class="metric-icon blue">◷</b></div>
            <strong>{{ cadenceLabel(selectedPoint.cadence) }}</strong>
            <small v-if="isEvent">事件型通道：新鲜度阈值 {{ formatDuration(staleAfter) }} · 最近一次动作 {{ timeAgo(lastEventAt, nowTick) }}</small>
            <small v-else>数据过期阈值 {{ formatDuration(staleAfter) }}</small>
          </article>
          <article class="metric-card">
            <div class="metric-top"><span>通道信息</span><b class="metric-icon violet">▦</b></div>
            <strong>{{ modalityName(selectedPoint.modalityType) }}</strong>
            <!-- 振动通道本就不配置采样率 / 工程单位（随波形快照给出），缺省显示 — 而不是“未配置” -->
            <small>{{ selectedPoint.samplingRate ? `${selectedPoint.samplingRate} Hz` : isVib ? '—' : '采样率未配置' }} · {{ selectedPoint.engineeringUnit || (isVib ? '—' : '工程单位未配置') }}</small>
          </article>
        </section>
        <div v-else class="panel empty-state"><div>⌁</div><b>尚未选择测点</b><p>选择左侧测点后，这里会显示通道健康摘要、劣化趋势与模态专家视图。</p></div>

        <!-- 统一劣化趋势：占满整个主列宽度，不再与侧栏拼列（避免短图旁留大块空白） -->
        <article v-if="selectedPoint" class="panel chart-panel">
          <div class="panel-heading">
            <div><h3>劣化趋势</h3><p>特征量（左轴）与健康指数（右轴 0–100）联动 · {{ currentRange().label }}</p></div>
            <div class="chart-filters">
              <select v-if="trendFeatureOptions.length > 1" v-model="trendFeature" class="compact-select" aria-label="趋势特征">
                <option v-for="code in trendFeatureOptions" :key="code" :value="code">{{ featureTitle(code) }}</option>
              </select>
              <select v-model="trendRange" class="compact-select" aria-label="趋势时间范围">
                <option v-for="range in trendRanges" :key="range.value" :value="range.value">{{ range.label }}</option>
              </select>
            </div>
          </div>
          <div v-if="busy && !rows.length" class="empty-cell">正在加载通道时序…</div>
          <ChannelTrendChart v-else-if="rows.length" :rows="rows" :feature="trendFeature" :height="300" />
          <p v-else class="empty-cell">{{ channelStatus === 'LOADING' ? '正在加载通道时序…' : dataError ? '通道时序暂不可用' : '暂无通道时序数据' }}</p>
        </article>

        <!-- 专家工作台 + 右侧信息栏：告警 / 待办工单与专家视图并排，列表限高滚动，两侧高度互相跟齐 -->
        <div v-if="selectedPoint" class="workbench-zone">
          <div class="workbench-slot">
            <component :is="resolvedWorkbench" :key="selectedPoint.id"
              :point="selectedPoint" :context="context" :realtime-payload="realtimePayload" :shared="shared" :refresh-key="refreshKey" />
          </div>
          <aside class="workbench-rail">
            <article class="panel rail-panel">
              <div class="panel-heading">
                <div><h3>本设备告警</h3><p>{{ context.machine?.name || '' }}</p></div>
                <RouterLink to="/alerts" class="text-link">告警中心 →</RouterLink>
              </div>
              <div class="alert-list">
                <div v-for="alert in deviceAlerts" :key="alert.id" class="alert-item">
                  <span :class="['status-tag', alert.status >= 2 ? 'gray' : alertLevelState(alert.level).tone]"><i></i>{{ alertLevelState(alert.level).label }}</span>
                  <div class="alert-item-main">
                    <b>{{ alert.dominantFactor }}</b>
                    <small>HI {{ alert.healthIndex.toFixed(1) }} · {{ timeAgo(alert.createdAt, nowTick) }}<template v-if="alert.status === 1"> · 已确认</template><template v-else-if="alert.status >= 2"> · 已闭环</template></small>
                  </div>
                </div>
                <p v-if="!deviceAlerts.length" class="empty-cell">该设备暂无告警记录</p>
              </div>
            </article>
            <article class="panel rail-panel">
              <div class="panel-heading">
                <div><h3>本设备待办工单</h3><p>待处理 / 处理中</p></div>
                <RouterLink to="/work-orders" class="text-link">维修工单 →</RouterLink>
              </div>
              <div class="workorder-list">
                <div v-for="order in deviceWorkOrders" :key="order.id" class="alert-item">
                  <span :class="['status-tag', workOrderStatusState(order.status).tone]"><i></i>{{ workOrderStatusState(order.status).label }}</span>
                  <div class="alert-item-main">
                    <b>工单 #{{ order.id }} · {{ order.dominantFactor }}</b>
                    <small>{{ order.assignee ? `处理人 ${order.assignee}` : '未分派' }} · {{ timeAgo(order.createdAt, nowTick) }}</small>
                  </div>
                </div>
                <p v-if="!deviceWorkOrders.length" class="empty-cell">{{ workOrdersAll.length || deviceAlerts.length ? '该设备暂无待办工单' : '暂无待办工单' }}</p>
              </div>
            </article>
          </aside>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.workbench-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.workbench-layout { display: grid; grid-template-columns: 250px minmax(0, 1fr); gap: 14px; align-items: start; }
.tree-panel { position: sticky; top: 68px; max-height: calc(100vh - 96px); display: flex; flex-direction: column; overflow: hidden; padding-bottom: 8px; }
.tree-heading { align-items: center; margin: 0 12px; padding: 14px 2px 12px; border-bottom: 1px solid var(--line); }
.tree-heading h3 { font-size: 14px; }
.tree-heading p { margin: 4px 0 0; color: var(--muted); font-size: 10px; }
.tree-level-count { padding: 4px 7px; border: 1px solid var(--line); border-radius: 6px; color: var(--muted); background: var(--panel-2); font-size: 9px; white-space: nowrap; }
.tree-scroll { overflow: auto; display: grid; align-content: start; gap: 7px; padding: 12px; font-size: 12px; }
.tree-factory,.tree-machine { min-width: 0; }
.tree-factory>summary,.tree-machine>summary { list-style: none; }
.tree-factory>summary::-webkit-details-marker,.tree-machine>summary::-webkit-details-marker { display: none; }
.tree-node-summary { display: flex; align-items: center; gap: 7px; min-width: 0; cursor: pointer; }
.tree-factory-summary { min-height: 46px; padding: 7px 9px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel-2); }
.tree-node-summary:hover .tree-node-copy b { color: var(--teal); }
.tree-chevron { width: 6px; height: 6px; flex: 0 0 6px; border-top: 1.5px solid var(--muted); border-right: 1.5px solid var(--muted); transform: rotate(45deg); transition: transform .16s ease; }
details[open]>.tree-node-summary .tree-chevron { transform: rotate(135deg); }
.tree-node-icon { display: grid; place-items: center; width: 25px; height: 25px; flex: 0 0 25px; border-radius: 7px; font-size: 11px; font-weight: 700; }
.factory-icon { color: var(--blue); background: color-mix(in srgb,var(--blue) 12%,transparent); }
.machine-icon { color: var(--teal); background: color-mix(in srgb,var(--teal) 12%,transparent); }
.tree-node-copy { display: flex; flex: 1; flex-direction: column; gap: 3px; min-width: 0; }
.tree-node-copy b { overflow: hidden; color: var(--text); font-size: 11px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.tree-node-copy small { color: var(--muted); font-family: Consolas,monospace; font-size: 9px; line-height: 1; }
.tree-machine { position: relative; margin: 7px 0 0 12px; padding-left: 10px; border-left: 1px solid var(--line); }
.tree-machine-summary { min-height: 38px; padding: 5px 2px; }
.tree-machine-health { flex: 0 0 auto; padding: 4px 6px; font-size: 8px; }
.tree-modality { display: flex; align-items: center; gap: 6px; margin: 3px 0 4px 5px; }
.tree-modality .status-tag { padding: 4px 7px; font-size: 8px; }
.tree-branch { width: 10px; height: 12px; flex: 0 0 10px; margin-top: -7px; border-bottom: 1px solid var(--line); border-left: 1px solid var(--line); }
.tree-point { display: grid; grid-template-columns: 10px 18px minmax(0,1fr) auto; align-items: center; gap: 5px; width: 100%; min-height: 51px; margin: 3px 0; padding: 6px 7px 6px 3px; border: 1px solid var(--line); border-radius: 9px; background: var(--panel); color: inherit; text-align: left; cursor: pointer; font-size: 11px; transition: border-color .16s ease,background .16s ease,box-shadow .16s ease; }
.tree-point:hover { border-color: color-mix(in srgb,var(--teal) 55%,var(--line)); background: var(--panel-2); }
.tree-point-active { border-color: var(--teal); background: color-mix(in srgb,var(--teal) 10%,var(--panel)); box-shadow: inset 2px 0 var(--teal),0 3px 10px #0a142310; }
.tree-point-icon { display: grid; place-items: center; width: 18px; height: 18px; border-radius: 6px; color: var(--teal); background: color-mix(in srgb,var(--teal) 12%,transparent); font-size: 14px; }
.tree-point-copy { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.tree-point-copy b { overflow: hidden; color: var(--text); font-size: 10px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.tree-point small { overflow: hidden; color: var(--muted); font-family: Consolas,monospace; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.tree-point-tags { display: grid; gap: 3px; justify-items: end; }
.tree-point-tags .status-tag { padding: 4px 5px; font-size: 8px; white-space: nowrap; }
.workbench-main { display: grid; gap: 14px; container-type: inline-size; }
/* 专家工作台 + 右侧信息栏：按主列实际宽度（容器查询）切换，主列不够宽时侧栏自然落到下方，避免挤压或大片空白 */
.workbench-zone { display: grid; grid-template-columns: minmax(0, 1fr); gap: 14px; align-items: start; }
.workbench-rail { display: grid; gap: 14px; align-content: start; }
.rail-panel { padding: 18px; display: flex; flex-direction: column; }
.alert-list { display: grid; gap: 8px; margin-top: 10px; max-height: 320px; overflow: auto; }
.alert-item { display: flex; align-items: flex-start; gap: 8px; padding: 8px 9px; border: 1px solid var(--line); border-radius: 8px; }
.alert-item-main { min-width: 0; }
.alert-item-main b { display: block; font-size: 11px; line-height: 1.5; overflow-wrap: anywhere; }
.alert-item-main small { display: block; margin-top: 3px; color: var(--muted); font-size: 9px; }
.baseline-failed { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; line-height: 1.5; }
.workorder-list { display: grid; gap: 8px; margin-top: 10px; max-height: 240px; overflow: auto; }
.workorder-list .empty-cell { padding: 10px !important; }
@container (min-width: 880px) {
  .workbench-zone { grid-template-columns: minmax(0, 1fr) 296px; }
  .workbench-rail { position: sticky; top: 68px; }
}
@media (max-width: 1000px) { .workbench-layout { grid-template-columns: 1fr; } .tree-panel { position: static; max-height: 300px; } }
</style>
