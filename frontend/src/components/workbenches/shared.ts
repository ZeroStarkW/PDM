import { computed, ref } from 'vue'
import { initChart, type EChartsType } from '@/charts'
import type { AssetFactoryNode, AssetMachineNode, AssetPointNode, ChannelBaseline, ChannelSeriesRow, ChannelSnapshot, ModalityCatalog, ModalityStreamFrame } from '@/api/types'
import type { FeatureComparison } from '@/modality'
import { useTheme } from '@/composables/ui'

/** 对照表比值 k 的悬浮说明（偏离口径与 modality.ts compareWithBaseline 的 tone 一致）。 */
export function ratioHint(row: FeatureComparison) {
  if (row.ratio == null) return '缺少基线或当前值，无法计算比值'
  const hint = row.tone === 'red' ? '偏离基线 30% 及以上' : row.tone === 'amber' ? '偏离基线 15%–30%' : '接近基线（偏离不足 15%）'
  return `k = 当前值 / 基线 P50 · ${hint}`
}

/** 统一外框（WorkbenchView）注入专家工作台的上下文：资产归属 + 该模态的插件目录项。 */
export interface WorkbenchContext {
  factory: AssetFactoryNode | null
  machine: AssetMachineNode | null
  catalog: ModalityCatalog | null
}

/** 外框已取到的通道数据：提供时子工作台直接复用，避免同一请求发两次（快照 / 时序 / 基线）。 */
export interface SharedChannelData {
  /** LOADING：外框仍在取数，子工作台应等待其结果而不是自行请求。 */
  status: 'LOADING' | 'READY'
  rows: ChannelSeriesRow[]
  features: string[]
  baseline: ChannelBaseline | null
  /** 最近动作快照：null = 确认没有快照（404）；undefined = 外框未取或取失败，由子工作台自行拉取。 */
  snapshot?: ChannelSnapshot | null
}

/** 专家工作台统一入参：外框按测点 remount（:key = point.id），组件只需在挂载时按 point.id 取数。 */
export interface WorkbenchProps {
  point: AssetPointNode
  context: WorkbenchContext
  realtimePayload: ModalityStreamFrame | null
  /** 外框已取的通道数据；缺省时子工作台自行拉取。 */
  shared?: SharedChannelData
  /** 轮询兜底刷新计数：外框每次需要子工作台重新拉取自己负责的数据（如回环）时递增。 */
  refreshKey?: number
}

/** 图表亮/暗主题公共色板（canvas 需要具体色值，沿用现有视图的取色）。 */
export function chartTheme() {
  const light = useTheme().theme.value === 'light'
  return {
    light,
    axis: light ? '#718096' : '#71839a',
    split: light ? '#e8edf1' : '#1e2d40',
    label: light ? '#53657a' : '#9aacc4',
    tooltip: {
      renderMode: 'richText' as const,
      backgroundColor: light ? '#ffffff' : '#162538',
      borderColor: light ? '#dce4eb' : '#26384d',
      textStyle: { color: light ? '#233247' : '#dce7f4' },
    },
    feature: light ? '#138b7c' : '#4dd6be',
    hi: light ? '#bd7a16' : '#f2b35d',
    blue: light ? '#2b6cb0' : '#38bdf8',
    violet: light ? '#6b46c1' : '#a78bfa',
    marker: light ? '#d69e2e' : '#f2b35d',
    orange: light ? '#c05621' : '#f28c5d',
    red: light ? '#c53030' : '#f87171',
    muted: light ? '#a0aec0' : '#64748b',
    band: light ? 'rgba(192, 86, 33, 0.08)' : 'rgba(242, 140, 93, 0.12)',
  }
}

/**
 * 取绑定到容器 el 的图表实例。
 * 容器被 v-if 重建后（元素变了）不能沿用旧实例——旧实例仍挂在已移除的 DOM 上，设 option 也不会显示，此时销毁并重新初始化。
 * 重载保证：传入确定的容器元素时一定返回图表实例；容器可能不存在时返回 undefined（调用方需判空）。
 */
export function ensureChart(chart: EChartsType | undefined, el: HTMLElement): EChartsType
export function ensureChart(chart: EChartsType | undefined, el: HTMLElement | undefined): EChartsType | undefined
export function ensureChart(chart: EChartsType | undefined, el: HTMLElement | undefined): EChartsType | undefined {
  if (!el) return undefined
  if (chart && chart.getDom() !== el) {
    chart.dispose()
    chart = undefined
  }
  return chart ?? initChart(el)
}

/** 统一外框的错误文案（中止的请求不提示）。 */
export function errorMessage(e: unknown, fallback: string) { return e instanceof Error ? e.message : fallback }

/** 从任意对象中挑出有限数值字段（用于实时帧 annotations 等弱类型载荷）；
 * 键名同时尝试 camelCase 契约形态与其 snake_case 形态（设计文档 §6.2 载荷为 snake_case）。 */
export function numericFields(raw: Record<string, unknown> | null | undefined, keys: readonly string[]): Record<string, number | null> {
  const picked: Record<string, number | null> = {}
  for (const key of keys) {
    const snake = key.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase()
    let pickedValue: number | null = null
    for (const candidate of key === snake ? [key] : [key, snake]) {
      const value = raw?.[candidate]
      if (typeof value === 'number' && Number.isFinite(value)) { pickedValue = value; break }
    }
    picked[key] = pickedValue
  }
  return picked
}

// ==== 实时流状态机与轮询兜底（统一外框的 v2 流与振动工作台的 v1 流共用） ====

/**
 * - OFF：该测点不走实时流（振动通道由专家视图自持 v1 流）。
 * - CONNECTING：首次连接中；WAITING：连接已建立但尚无上报帧（如基线学习期）；LIVE：已收到实时帧。
 * - RECONNECTING：连接中断，浏览器正在自动重连；POLLING：确定性失败（HTTP 4xx/5xx 等，浏览器不会重试），改由 REST 轮询兜底。
 */
export type LiveState = 'OFF' | 'CONNECTING' | 'WAITING' | 'LIVE' | 'RECONNECTING' | 'POLLING'

export const STREAM = {
  CONNECTING: 0, OPEN: 1, CLOSED: 2, // EventSource.readyState
  /** POLLING 时的 REST 轮询间隔。 */
  POLL_MS: 30_000,
  /** 已连接但尚无帧 / 重连期间的保底刷新间隔。 */
  PRE_LIVE_POLL_MS: 60_000,
  /** 实时流失败后的最小重试间隔。 */
  RETRY_MS: 60_000,
  /** 判定“订阅回放”时容忍的浏览器/服务端时钟偏差（回放帧的时间戳早于订阅时刻）。 */
  REPLAY_SKEW_MS: 10_000,
} as const

const liveLabels: Record<LiveState, string> = {
  OFF: '',
  CONNECTING: '实时流连接中…',
  WAITING: '已连接 · 等待上报',
  LIVE: '实时流已连接',
  RECONNECTING: '连接中断 · 重连中…',
  POLLING: '实时流不可用 · 轮询兜底',
}

/**
 * 实时流 + 轮询兜底：
 * - readyState=CONNECTING 的错误交给浏览器自动重连，期间按 60 秒保底刷新；
 * - readyState=CLOSED（确定性 HTTP 错误）关闭连接、清除 LIVE 并按 30 秒真实刷新，避免浏览器重试风暴；
 * - 轮询期间至多每 60 秒、且仅在上一次 REST 刷新成功后重试一次实时流。
 */
export function useStreamFallback(options: {
  /** REST 兜底刷新（轮询期间定时调用）；返回是否成功——实时流重试只在其成功之后进行。 */
  refresh: () => Promise<boolean>
  /** 建立实时流；返回 null 表示该测点不适用实时流。 */
  open: (control: { onOpen?: () => void; onError?: () => void }) => EventSource | null
  labels?: Partial<Record<LiveState, string>>
}) {
  const state = ref<LiveState>('OFF')
  const labels = { ...liveLabels, ...options.labels }
  let source: EventSource | undefined
  let pollTimer: number | undefined
  let pollEveryMs = 0
  let lastOpenAt = 0
  let everOpened = false
  let session = 0

  const label = computed(() => labels[state.value])
  const tone = computed(() => (state.value === 'LIVE' ? 'green' : state.value === 'WAITING' ? 'blue' : 'amber'))

  function stopPolling() {
    if (pollTimer != null) window.clearInterval(pollTimer)
    pollTimer = undefined
    pollEveryMs = 0
  }
  function startPolling(everyMs: number) {
    if (pollTimer != null && pollEveryMs === everyMs) return
    stopPolling()
    pollEveryMs = everyMs
    pollTimer = window.setInterval(() => void tick(), everyMs)
  }
  function close() {
    source?.close()
    source = undefined
  }
  /** 限频重试实时流：仅在轮询兜底下、没有进行中的连接、且距上次尝试已满 RETRY_MS。 */
  function retryNow() {
    if (state.value !== 'POLLING' || source || Date.now() - lastOpenAt < STREAM.RETRY_MS) return
    start(true)
  }
  async function tick() {
    let ok = false
    try {
      ok = await options.refresh()
    } catch {
      ok = false // 兜底刷新自身出错等同于失败：不重试实时流，也不让定时器回调产生未处理的拒绝
    }
    if (ok) retryNow()
  }

  /** 确定性失败：关闭连接、清除 LIVE、转入 30 秒轮询兜底。 */
  function giveUp() {
    close()
    state.value = 'POLLING'
    startPolling(STREAM.POLL_MS)
  }

  /** retry=true：后台重试——保持 POLLING 状态与其轮询节奏，连接建立后再切到“已连接”。 */
  function start(retry = false) {
    const current = ++session
    close()
    lastOpenAt = Date.now()
    if (!retry) {
      state.value = 'CONNECTING'
      startPolling(STREAM.PRE_LIVE_POLL_MS)
    }
    const created = options.open({
      onOpen: () => {
        if (current !== session) return
        everOpened = true
        if (state.value !== 'LIVE') state.value = 'WAITING'
        startPolling(STREAM.PRE_LIVE_POLL_MS)
      },
      onError: () => {
        if (current !== session || !source) return
        const readyState = source.readyState
        if (readyState === STREAM.CONNECTING) {
          // 网络抖动 / 服务重启：浏览器会自动重连；先清除 LIVE，并开启保底刷新
          state.value = everOpened ? 'RECONNECTING' : 'CONNECTING'
          startPolling(STREAM.PRE_LIVE_POLL_MS)
          return
        }
        if (readyState === STREAM.OPEN) return // 瞬时事件，连接仍在
        giveUp()
      },
    })
    if (current !== session) { created?.close(); return } // 期间已被 stop()/start() 取代
    source = created ?? undefined
    if (!source) {
      state.value = 'OFF'
      stopPolling()
      return
    }
    // 连接一建立就已关闭（真实浏览器都是异步派发 error，这里只是兜底）：按确定性失败同样处理，不能停在“连接中”
    if (source.readyState === STREAM.CLOSED) giveUp()
  }

  /** 仅标记“连接中”（如建立连接前先做一次 REST 预热的期间）。 */
  function connecting() {
    state.value = 'CONNECTING'
  }
  /** 收到实时帧：进入 LIVE 并停止兜底轮询。 */
  function acceptLive() {
    everOpened = true
    state.value = 'LIVE'
    stopPolling()
  }
  /** 不做连接尝试、直接进入轮询兜底（如尚无波形快照时 v1 流必然 404/409）。 */
  function enterPolling() {
    session++
    close()
    lastOpenAt = Date.now()
    state.value = 'POLLING'
    startPolling(STREAM.POLL_MS)
  }
  /** 关闭连接并停止轮询（切换测点 / 卸载时调用）。 */
  function stop() {
    session++
    close()
    stopPolling()
    state.value = 'OFF'
  }

  return { state, label, tone, start, stop, connecting, acceptLive, enterPolling, retryNow }
}
