<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError } from '@/api/http'
import { getFailurePrediction, getOverview, loadModalityCatalog } from '@/api/pdm'
import type { DeviceHealth, FailurePrediction, ModalityCatalog, Overview } from '@/api/types'
import { freshnessRule, isEventChannel, modalityName, staleAfterSeconds } from '@/modality'
import { freshnessState, pointStatusLabel, qualityLabel, statusTone, telemetryTime } from '@/telemetry-status'

const route = useRoute()
const overview = ref<Overview | null>(null)
/** 模态目录：仅用于事件型通道的新鲜度阈值说明；加载失败时按事件型默认口径兜底。 */
const catalogs = ref<ModalityCatalog[]>([])
const error = ref('')
const deviceId = computed(() => Number(route.params.id))
const hasValidDeviceId = computed(() => Number.isSafeInteger(deviceId.value) && deviceId.value > 0)
const device = computed<DeviceHealth | null>(() => overview.value?.devices.find((item) => item.deviceId === deviceId.value) ?? null)
const prediction = shallowRef<FailurePrediction | null>(null)
const predictionError = shallowRef('')
const refreshVersion = shallowRef(0)

const predictionStates = {
  INSUFFICIENT_DATA: { label: '数据不足', tone: 'gray' },
  NO_DECLINE: { label: '未见下降趋势', tone: 'green' },
  THRESHOLD_REACHED: { label: '已达到阈值', tone: 'orange' },
  ESTIMATE_AVAILABLE: { label: '可估算', tone: 'blue' },
} satisfies Record<FailurePrediction['status'], { label: string; tone: string }>
const confidenceLabels: Record<FailurePrediction['confidence'], string> = { LOW: '低', MEDIUM: '中', HIGH: '高' }
const predictionState = computed(() => prediction.value ? predictionStates[prediction.value.status] : null)
const modalityOf = (pointId: number) => overview.value?.points.find((point) => point.pointId === pointId)?.modalityType
/**
 * 事件型通道（如断路器，动作间隔可达数天）“在线”按长时限判定，需要把阈值说清楚，否则看起来像长期没有新数据却显示在线。
 * 非事件型通道没有额外说明。
 */
function eventRule(pointId: number) {
  const modalityType = modalityOf(pointId)
  const catalog = catalogs.value.find((item) => item.modalityType === modalityType) ?? null
  if (!isEventChannel(modalityType, catalog)) return ''
  const cadence = catalog?.cadence ?? 'EVENT'
  return freshnessRule(cadence, staleAfterSeconds(cadence, catalog))
}
const dayFormatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 })
const projectedDuration = computed(() => {
  const days = prediction.value?.projectedDays
  if (days == null) return null
  return days < 0.01 ? '不到 0.01 天' : `约 ${dayFormatter.format(days)} 天`
})

async function loadOverview(signal: AbortSignal) {
  try {
    const result = await getOverview(signal)
    if (!signal.aborted) overview.value = result
  } catch (e) {
    if (!signal.aborted) error.value = e instanceof Error ? e.message : '设备健康数据加载失败'
  }
}

async function loadPrediction(id: number, signal: AbortSignal) {
  try {
    const result = await getFailurePrediction(id, signal)
    if (!signal.aborted) prediction.value = result
  } catch (e) {
    if (signal.aborted) return
    if (e instanceof ApiError && e.status === 404) {
      predictionError.value = '设备不存在。'
    } else {
      predictionError.value = e instanceof Error ? `趋势估算加载失败 · ${e.message}` : '趋势估算加载失败'
    }
  }
}

watch([deviceId, refreshVersion], ([id], _previous, onCleanup) => {
  const controller = new AbortController()
  onCleanup(() => controller.abort())
  prediction.value = null
  predictionError.value = ''
  error.value = ''
  if (!hasValidDeviceId.value) return
  void loadOverview(controller.signal)
  void loadPrediction(id, controller.signal)
}, { immediate: true })

void loadModalityCatalog().then((items) => { catalogs.value = items })

function formatSlope(value: number | null) {
  if (value == null) return '—'
  const formatted = Math.abs(value) > 0 && Math.abs(value) < 0.01 ? value.toExponential(1) : value.toFixed(2)
  return `${formatted} 分/天`
}
</script>

<template>
  <div class="page-stack">
    <div class="page-intro">
      <div><RouterLink to="/devices" class="back-link">← 返回设备清单</RouterLink><h2>{{ device?.deviceName || '设备健康详情' }}</h2><p v-if="device">{{ device.factoryCode }} / {{ device.deviceCode }} · {{ device.deviceTypeName }}</p></div>
      <button class="quiet-button" @click="refreshVersion++">↻ 刷新设备健康</button>
    </div>
    <div v-if="error" class="notice error-notice">{{ error }}</div>
    <div v-if="device" class="notice info-notice"><b>设备健康口径</b><span>设备 HI 取在线、有效测点中最低的分数，作为保守的代表分；每个测点仍按自己的安装位置和基线独立分析。测点覆盖不足会单独显示，不会被当成设备故障。</span></div>
    <section v-if="device" class="metric-grid compact-metrics">
      <article class="metric-card"><div class="metric-top"><span>设备健康指数</span><b class="metric-icon teal">HI</b></div><strong>{{ device.healthIndex?.toFixed(1) ?? '—' }}</strong><small>{{ device.worstPointCode ? `由 ${device.worstPointCode} 代表` : '暂无有效测点评分' }}</small></article>
      <article class="metric-card"><div class="metric-top"><span>设备状态</span><b class="metric-icon blue">▦</b></div><strong>{{ pointStatusLabel(device.status) }}</strong><small>设备级健康结论</small></article>
      <article class="metric-card"><div class="metric-top"><span>有效测点覆盖</span><b class="metric-icon violet">⌁</b></div><strong>{{ device.scoredPointCount }} / {{ device.pointCount }}</strong><small>{{ device.coverage === 'COMPLETE' ? '所有测点均有有效评分' : device.coverage === 'PARTIAL' ? '部分测点没有有效评分' : '暂无有效评分' }}</small></article>
      <article class="metric-card"><div class="metric-top"><span>当前数据状态</span><b class="metric-icon amber">◉</b></div><strong>{{ device.onlinePointCount }} 在线</strong><small>{{ device.stalePointCount }} 过期 · {{ device.sensorCheckPointCount }} 传感器异常 · {{ device.stoppedPointCount }} 停机</small></article>
    </section>
    <section v-if="hasValidDeviceId" class="panel status-panel" aria-labelledby="hi-trend-heading">
      <div class="panel-heading prediction-heading">
        <div>
          <h3 id="hi-trend-heading">HI 阈值趋势估算</h3>
          <p>基于最近最多 30 天的小时 HI，通过线性拟合启发式估算何时达到 HI 50。</p>
        </div>
        <span v-if="predictionState" :class="['status-tag', predictionState.tone]">
          <i></i>{{ predictionState.label }}
        </span>
      </div>
      <p v-if="predictionError" class="notice error-notice" role="status">{{ predictionError }}</p>
      <p v-else-if="!prediction" class="muted" role="status">正在加载趋势估算…</p>
      <template v-else>
        <p v-if="projectedDuration" class="prediction-summary">
          若当前趋势持续，<strong>{{ projectedDuration }}</strong>后达到 HI 50。
        </p>
        <p v-else-if="prediction.status === 'THRESHOLD_REACHED'" class="prediction-summary">当前 HI 已达到或低于 50。</p>
        <p v-else-if="prediction.status === 'NO_DECLINE'" class="prediction-summary">观测窗口内的拟合斜率为零或为正。</p>
        <p v-else class="prediction-summary">估算需要近期有效评分，以及至少 24 个跨越 24 小时的有效小时观测。</p>
        <dl class="prediction-evidence">
          <div>
            <dt>来源测点</dt>
            <dd v-if="prediction.sourcePointId != null">
              <RouterLink :to="`/points/${prediction.sourcePointId}`" class="text-link">
                {{ prediction.sourcePointName }}（{{ prediction.sourcePointCode }}）
              </RouterLink>
            </dd>
            <dd v-else>—</dd>
          </div>
          <div><dt>当前 HI</dt><dd>{{ prediction.currentHi?.toFixed(1) ?? '—' }}</dd></div>
          <div><dt>变化斜率</dt><dd>{{ formatSlope(prediction.slopePerDay) }}</dd></div>
          <div><dt>小时观测数</dt><dd>{{ prediction.observationCount }}</dd></div>
          <div><dt>拟合优度 R²</dt><dd>{{ prediction.fitScore?.toFixed(2) ?? '—' }}</dd></div>
          <div><dt>趋势置信度</dt><dd>{{ confidenceLabels[prediction.confidence] }}</dd></div>
          <div class="prediction-window">
            <dt>观测窗口</dt>
            <dd v-if="prediction.windowStart && prediction.windowEnd">
              {{ telemetryTime(prediction.windowStart) }} 至 {{ telemetryTime(prediction.windowEnd) }}
            </dd>
            <dd v-else>—</dd>
          </div>
        </dl>
      </template>
    </section>
    <section v-if="device" class="panel table-panel">
      <div class="panel-heading"><div><h3>设备测点证据</h3><p>每个测点提供局部健康、数据质量和在线状态证据</p></div><span class="count-chip">{{ device.points.length }} 个测点</span></div>
      <div class="table-wrap"><table><thead><tr><th>测点 / 安装位置</th><th>测点 HI</th><th>局部状态</th><th>数据状态</th><th>质量</th><th>最近采样</th><th>趋势</th></tr></thead><tbody>
        <tr v-for="point in device.points" :key="point.pointId">
          <td><b>{{ point.pointName }}</b><small>{{ point.pointCode }}<template v-if="modalityOf(point.pointId)"> · {{ modalityName(modalityOf(point.pointId)) }}</template><template v-if="point.pointId === device.worstPointId"> · 当前最差有效点</template></small></td>
          <td><b :class="`hi-value ${statusTone(point.status)}`">{{ point.healthIndex?.toFixed(1) ?? '—' }}</b></td>
          <td><span :class="['status-tag', statusTone(point.status)]"><i></i>{{ pointStatusLabel(point.status) }}</span></td>
          <td><span :class="['status-tag', freshnessState(point.freshness).tone]"><i></i>{{ freshnessState(point.freshness).label }}</span><small v-if="eventRule(point.pointId)" class="freshness-rule">{{ eventRule(point.pointId) }}</small></td>
          <td>{{ qualityLabel(point.qualityCode) }}</td>
          <td>{{ telemetryTime(point.lastTelemetryAt) }}</td>
          <td><RouterLink :to="`/points/${point.pointId}`" class="text-link">查看趋势 →</RouterLink></td>
        </tr>
        <tr v-if="!device.points.length"><td colspan="7" class="empty-cell">设备还没有配置测点。</td></tr>
      </tbody></table></div>
    </section>
    <div v-else-if="!hasValidDeviceId" class="empty-state"><div>▦</div><b>设备编号无效</b></div>
  </div>
</template>

<style scoped>
.prediction-heading { flex-wrap: wrap; }
.prediction-summary { margin: 14px 0 0; font-size: 12px; }
.prediction-evidence { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 120px), 1fr)); gap: 12px; margin: 14px 0 0; }
.prediction-evidence dt { color: var(--muted); font-size: 10px; }
.prediction-evidence dd { margin: 5px 0 0; font-size: 12px; overflow-wrap: anywhere; }
.prediction-window { grid-column: 1 / -1; }
.freshness-rule { white-space: normal; max-width: 220px; line-height: 1.5; }
</style>
