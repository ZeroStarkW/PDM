<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { getChannelBaseline, getChannelSeries } from '@/api/pdm'
import type { ChannelBaseline, ChannelSeriesRow } from '@/api/types'
import { baselineState } from '@/telemetry-status'
import { compareWithBaseline, defaultTrendFeature, featureTitle, formatNumber, formatRatio, formatTime, healthLevel, latestHealthIndex, latestRow, mergeLiveRow } from '@/modality'
import { errorMessage, ratioHint, type WorkbenchProps } from './shared'
import ChannelTrendChart from './ChannelTrendChart.vue'

/** 通用特征工作台：最新特征 vs 基线矩阵 + HI 趋势；用于 MCSA 与未登记模态的兜底视图。 */
const props = defineProps<WorkbenchProps>()
// 外框已取到时序 / 基线时直接复用（实时帧也由外框统一并入），避免同一段时序请求两次；独立挂载时才自行拉取
const ownRows = ref<ChannelSeriesRow[]>([])
const ownFeatures = ref<string[]>([])
const ownBaseline = ref<ChannelBaseline | null>(null)
const rows = computed(() => (props.shared ? props.shared.rows : ownRows.value))
const features = computed(() => (props.shared ? props.shared.features : ownFeatures.value))
const baseline = computed(() => (props.shared ? props.shared.baseline : ownBaseline.value))
const error = ref('')
const busy = ref(false)
const liveNote = ref('')
const trendFeature = ref('')

const loading = computed(() => busy.value || props.shared?.status === 'LOADING')
const baselineMeta = computed(() => baselineState(baseline.value?.status))
const currentRow = computed(() => latestRow(rows.value))
const currentHealth = computed(() => latestHealthIndex(rows.value))
const health = computed(() => healthLevel(currentHealth.value))
const comparisonRows = computed(() => compareWithBaseline(currentRow.value?.metrics ?? {}, baseline.value,
  features.value.length ? features.value : props.context?.catalog?.seriesFeatureCodes ?? [],
  props.context?.catalog?.lowerIsWorseFeatures ?? []))

/** 趋势特征：共享默认特征（目录 trendFeatureCode → 模态兜底 → 主特征 …）；已选特征仍可用时保留用户选择。 */
const defaultFeature = computed(() => defaultTrendFeature(props.point.modalityType, props.context?.catalog, features.value))
watch([defaultFeature, features], () => {
  if (!trendFeature.value || !features.value.includes(trendFeature.value)) trendFeature.value = defaultFeature.value
}, { immediate: true })
const featureOptions = computed(() => [defaultFeature.value, ...features.value].filter((code, index, list) => code !== '' && list.indexOf(code) === index))

async function load() {
  busy.value = true
  error.value = ''
  const endTs = Date.now()
  const startTs = endTs - 24 * 60 * 60 * 1000
  try {
    const [series, base] = await Promise.all([
      getChannelSeries(props.point.id, startTs, endTs, '5m'),
      getChannelBaseline(props.point.id).catch(() => null),
    ])
    ownRows.value = series.rows
    ownFeatures.value = series.features
    ownBaseline.value = base
    liveNote.value = ''
  } catch (e) {
    error.value = errorMessage(e, '通道时序加载失败')
  } finally {
    busy.value = false
  }
}

watch(() => props.realtimePayload, (frame) => {
  if (!frame || frame.frame_type !== 'METRICS_FRAME') return
  // 外框提供 shared 时实时帧由外框统一并入时序；独立挂载时在此并入（未评分帧 health_index 为 null，仅并入特征值）
  if (!props.shared) ownRows.value = mergeLiveRow(ownRows.value, frame, 24 * 60 * 60 * 1000)
  liveNote.value = '已并入实时指标帧'
})

onMounted(() => { if (!props.shared) void load() })
// 组件随外框按测点 remount，无长连接与定时器需要清理
</script>

<template>
  <div class="generic-workbench">
    <section class="panel chart-panel">
      <div class="panel-heading">
        <div><h3>通用特征视图</h3><p>该模态暂无专用专家视图，展示特征趋势与基线对照（趋势特征 {{ trendFeature ? featureTitle(trendFeature) : '尚未上报' }}）</p></div>
        <div class="generic-tags">
          <span v-if="health" :class="['status-tag', health.tone]"><i></i>HI {{ formatNumber(currentHealth, 1) }} · {{ health.label }}</span>
          <span v-if="liveNote" class="status-tag green"><i></i>{{ liveNote }}</span>
          <span v-else-if="loading" class="status-tag amber"><i></i>加载中…</span>
          <select v-if="featureOptions.length > 1" v-model="trendFeature" class="compact-select" aria-label="趋势特征">
            <option v-for="code in featureOptions" :key="code" :value="code">{{ featureTitle(code) }}</option>
          </select>
        </div>
      </div>
      <div v-if="error" class="notice error-notice">{{ error }}</div>
      <div v-else-if="loading" class="empty-cell">正在加载通道时序…</div>
      <div v-else-if="!rows.length" class="empty-cell">暂无通道时序数据</div>
      <ChannelTrendChart v-else :rows="rows" :feature="trendFeature" :height="280" />
    </section>

    <section class="panel table-panel">
      <div class="panel-heading">
        <div><h3>最新特征 vs 基线对照</h3><p>最近一个含特征量的采样与通道自学习 P50 基线的相对偏离（k = 当前 / 基线）</p></div>
        <div class="generic-tags">
          <span :class="['status-tag', baselineMeta.tone]"><i></i>基线 {{ baselineMeta.label }}</span>
          <span v-if="baseline" class="muted">{{ baseline.learnedSamples }}/{{ baseline.minimumSamples }} 样本 · {{ formatTime(baseline.lockedAt) }}</span>
        </div>
      </div>
      <p v-if="baseline?.warning" class="warning-note">{{ baseline.warning }}</p>
      <div class="table-wrap">
        <table>
          <thead><tr><th>特征</th><th>当前值</th><th>基线 P50</th><th>比值 k</th><th>变异系数 CV</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="row in comparisonRows" :key="row.feature">
              <td><b>{{ row.label }}</b><small class="subtle-code">{{ row.feature }}</small></td>
              <td>{{ formatNumber(row.current, 3) }} {{ row.unit }}</td>
              <td>{{ formatNumber(row.baseline, 3) }}</td>
              <td :class="['k-cell', 'tone-text', row.tone]" :title="ratioHint(row)">{{ formatRatio(row.ratio) }}</td>
              <td>{{ formatNumber(row.cv, 3) }}</td>
              <td><span :class="['status-tag', row.status ? baselineState(row.status).tone : 'gray']"><i></i>{{ row.status ? baselineState(row.status).label : '无基线' }}</span></td>
            </tr>
            <tr v-if="!comparisonRows.length"><td colspan="6" class="empty-cell">{{ loading ? '正在加载…' : '暂无可对照的特征' }}</td></tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.generic-workbench { display: grid; gap: 14px; }
.generic-tags { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.k-cell { font-weight: 600; }
</style>
