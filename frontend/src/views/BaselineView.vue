<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { confirmDeviceBaselineReset, getBaseline, getChannelBaseline, getOverview, requestDeviceBaselineReset } from '@/api/pdm'
import type { Baseline, ChannelBaseline, DeviceHealth, PointHealth } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useUiDialog } from '@/composables/ui'
import { featureTitle, formatDuration, formatNumber, isVibration, modalityName } from '@/modality'
import { baselineState } from '@/telemetry-status'

const auth = useAuthStore()
const dialog = useUiDialog()
const devices = ref<DeviceHealth[]>([])
const points = ref<PointHealth[]>([])
/** 旋转振动测点：v1 基线（1X / 2X、包络、温度等振动专属量）。 */
const baselines = ref<Record<number, Baseline>>({})
/** 多模态通道：v2 通道基线（逐特征 P50 / 变异系数 / 状态）。 */
const channelBaselines = ref<Record<number, ChannelBaseline>>({})
/** 单个测点基线加载失败的原因（只标在该测点卡片上，不让整页报错）。 */
const loadErrors = ref<Record<number, string>>({})
const loaded = ref(false)
const selectedPointIds = ref<Record<number, number[]>>({})
const error = ref('')
const busyDeviceId = ref<number | null>(null)
const learningConfig = computed(() => Object.values(baselines.value)[0])
const formatLearningDuration = (seconds: number | undefined) => {
  const value = seconds ?? 360
  if (value >= 3600 && Number.isInteger(value / 3600)) return `${value / 3600} 小时`
  if (value >= 60) return `${Number((value / 60).toFixed(1))} 分钟`
  return `${Number(value.toFixed(value < 1 ? 2 : 1))} 秒`
}
/** 振动测点（未标注模态的旧数据同样按振动处理）走 v1 基线；其余模态走 v2 通道基线。 */
const isVibPoint = (point: PointHealth) => !point.modalityType || isVibration(point.modalityType)
const hasVibPoints = computed(() => points.value.some(isVibPoint))
const hasChannelPoints = computed(() => points.value.some((point) => !isVibPoint(point)))
const pointsFor = (deviceId: number) => points.value.filter((point) => point.deviceId === deviceId)
/** 测点基线状态：振动取 v1 基线、其余取 v2 通道基线——重置申请 / 确认的判定对各模态一致。 */
function pointStatus(point: PointHealth) {
  return isVibPoint(point) ? baselines.value[point.pointId]?.status : channelBaselines.value[point.pointId]?.status
}
function pointState(point: PointHealth) {
  return loaded.value ? baselineState(pointStatus(point)) : { label: '…', tone: 'blue' }
}
function progress(point: PointHealth) {
  const item = isVibPoint(point) ? baselines.value[point.pointId] : channelBaselines.value[point.pointId]
  return { learned: item?.learnedSamples ?? point.learnedSamples, minimum: item?.minimumSamples ?? 200 }
}
/** 多模态通道学习口径（随模态插件而异）：池化学习按样本数门槛，窗口学习需覆盖时间窗口。 */
function channelRule(point: PointHealth) {
  const item = channelBaselines.value[point.pointId]
  if (!item) return '通道基线学习中。'
  const window = item.learningWindowSeconds ? formatDuration(item.learningWindowSeconds) : '—'
  return item.pooled
    ? `池化学习：至少 ${item.minimumSamples} 次动作，样本保留 ${window}，不要求覆盖时间窗口。`
    : `学习窗口 ${window}：窗口内至少 ${item.minimumSamples} 个有效样本后锁定。`
}
/** 多工况分箱视图：仅当测点存在非 0 工况箱（或多于 1 个工况箱）时呈现分箱列表。 */
function vibRegimes(point: PointHealth) {
  const regimes = baselines.value[point.pointId]?.regimes ?? []
  return regimes.length > 1 || regimes.some((bin) => bin.binIndex !== 0) ? regimes : []
}
/** 通道基线矩阵是否存在非默认工况箱（决定是否给特征行展示工况徽章）。 */
function channelHasRegimeBins(point: PointHealth) {
  return (channelBaselines.value[point.pointId]?.featureStates ?? []).some((state) => (state.binIndex ?? 0) > 0)
}
const eligiblePoints = (deviceId: number, status: 'ACTIVE' | 'PENDING_RESET') =>
  pointsFor(deviceId).filter((point) => pointStatus(point) === status)
const selected = (point: PointHealth) => (selectedPointIds.value[point.deviceId] || []).includes(point.pointId)
function operationPointCount(deviceId: number, status: 'ACTIVE' | 'PENDING_RESET') {
  const eligible = eligiblePoints(deviceId, status)
  const selectedIds = selectedPointIds.value[deviceId] || []
  return selectedIds.length ? eligible.filter((point) => selectedIds.includes(point.pointId)).length : eligible.length
}

function togglePoint(deviceId: number, pointId: number, event: Event) {
  const checked = (event.target as HTMLInputElement).checked
  const current = selectedPointIds.value[deviceId] || []
  selectedPointIds.value = {
    ...selectedPointIds.value,
    [deviceId]: checked ? [...current, pointId] : current.filter((id) => id !== pointId),
  }
}

async function load() {
  try {
    const overview = await getOverview()
    devices.value = overview.devices
    points.value = overview.points
    // 按模态各取各的基线；单个测点的基线接口失败只标记在该卡片上，不再让整页只剩一句错误
    const settled = await Promise.all(points.value.map(async (point) => {
      try {
        const vib = isVibPoint(point)
        const v1 = vib ? await getBaseline(point.pointId) : null
        const v2 = vib ? null : await getChannelBaseline(point.pointId)
        return [point.pointId, v1, v2, ''] as const
      } catch (e) {
        return [point.pointId, null, null, e instanceof Error ? e.message : '基线加载失败'] as const
      }
    }))
    baselines.value = Object.fromEntries(settled.flatMap(([id, v1]) => (v1 ? [[id, v1] as const] : [])))
    channelBaselines.value = Object.fromEntries(settled.flatMap(([id, , v2]) => (v2 ? [[id, v2] as const] : [])))
    loadErrors.value = Object.fromEntries(settled.flatMap(([id, , , message]) => (message ? [[id, message] as const] : [])))
    loaded.value = true
    const existingPointIds = new Set(points.value.map((point) => point.pointId))
    selectedPointIds.value = Object.fromEntries(Object.entries(selectedPointIds.value).map(([deviceId, ids]) => [
      deviceId, ids.filter((id) => existingPointIds.has(id)),
    ]))
    error.value = ''
  } catch (e) { error.value = e instanceof Error ? e.message : '基线数据加载失败' }
}

onMounted(load)

async function resetDevice(device: DeviceHealth, action: 'request' | 'confirm') {
  const status = action === 'request' ? 'ACTIVE' : 'PENDING_RESET'
  const eligible = eligiblePoints(device.deviceId, status)
  const selectedIds = selectedPointIds.value[device.deviceId] || []
  const targetPoints = selectedIds.length ? eligible.filter((point) => selectedIds.includes(point.pointId)) : eligible
  if (!targetPoints.length) {
    error.value = action === 'request' ? '请至少选择一个已生效基线的测点。' : '请至少选择一个待确认重置的测点。'
    return
  }
  const ids = targetPoints.map((point) => point.pointId)
  const accepted = await dialog.confirm({
    title: action === 'confirm' ? '确认设备测点重新学习' : '申请设备测点基线重置',
    description: action === 'confirm'
      ? `确认 ${device.deviceCode} 的 ${targetPoints.length} 个测点？这些测点会清除各自的历史学习样本并独立重新建立基线。`
      : `为 ${device.deviceCode} 的 ${targetPoints.length} 个测点发起基线重置申请。确认后再开始重新学习。`,
    confirmLabel: action === 'confirm' ? '确认并重新学习' : '提交申请',
    danger: action === 'confirm',
  })
  if (!accepted) return
  busyDeviceId.value = device.deviceId
  error.value = ''
  try {
    if (action === 'confirm') await confirmDeviceBaselineReset(device.deviceId, ids)
    else await requestDeviceBaselineReset(device.deviceId, ids)
    await load()
  } catch (e) { error.value = e instanceof Error ? e.message : '设备基线操作失败' }
  finally { busyDeviceId.value = null }
}
</script>

<template>
  <div class="page-stack">
    <div class="page-intro"><div><h2>设备基线生命周期</h2><p>从设备发起检修后重置；测点仍按各自安装位置与测量模态独立学习。</p></div><button class="quiet-button" @click="load">↻ 刷新</button></div>
    <!-- 旋转振动学习口径（1X/2X、额定转速 ±5% 等只适用于振动测点），仅在存在振动测点时显示 -->
    <div v-if="hasVibPoints" class="notice info-notice"><b>旋转振动学习规则</b><span>采集并覆盖 {{ formatLearningDuration(learningConfig?.learningDurationSeconds) }} 学习窗口，且至少 {{ learningConfig?.minimumSamples ?? 200 }} 个有效样本；仅纳入质量正常、运行中的样本。额定转速 ±5% 的样本进入主基线箱（Bin 0，双写设备旧基线列）；变频泵/风机偏离额定的稳态转速样本不再丢弃，而是按转速段自动分箱，各工况箱独立积累、独立 MAD 清洗与 CV ≤ 0.15 锁定，评分时严格使用当前转速所属工况箱的基线对口比较（该箱未学满时如实输出“基线学习”，不会误用其他转速箱基线）。各特征经 MAD 清洗后的最大 CV 不超过 0.15 才锁定，超出时继续学习并提示发散。</span></div>
    <!-- 多模态通道学习口径随模态插件而异，单独说明，不再套用振动规则 -->
    <div v-if="hasChannelPoints" class="notice info-notice"><b>多模态通道学习规则</b><span>按机理插件登记的特征逐项学习基线中位数（P50）与变异系数。连续型通道（如调节阀、电机电流）需覆盖各自的学习窗口；事件型通道（如高压断路器）采用池化学习——累计到样本数门槛即可锁定，样本按保留期滚动清理，不要求覆盖时间窗口。</span></div>
    <div class="notice info-notice"><b>设备级操作</b><span>设备操作默认作用于该设备所有可重置测点；也可勾选受影响测点进行局部重学。</span></div>
    <div v-if="error" class="notice error-notice">{{ error }}</div>
    <section class="baseline-grid device-baseline-grid">
      <article v-for="device in devices" :key="device.deviceId" class="panel device-baseline-group">
        <div class="device-baseline-head"><div class="device-symbol">▦</div><div><RouterLink :to="`/devices/${device.deviceId}`" class="table-link"><b>{{ device.deviceName }}</b></RouterLink><small>{{ device.deviceCode }} · {{ device.deviceTypeName }} · {{ pointsFor(device.deviceId).length }} 个测点</small></div></div>
        <div class="device-baseline-actions" v-if="auth.hasRole('ADMIN')">
          <button v-if="operationPointCount(device.deviceId, 'ACTIVE')" class="small-button" :disabled="busyDeviceId === device.deviceId" @click="resetDevice(device, 'request')">申请设备基线重置 · {{ operationPointCount(device.deviceId, 'ACTIVE') }} 个已生效</button>
          <button v-if="operationPointCount(device.deviceId, 'PENDING_RESET')" class="small-button danger-button" :disabled="busyDeviceId === device.deviceId" @click="resetDevice(device, 'confirm')">确认并重新学习 · {{ operationPointCount(device.deviceId, 'PENDING_RESET') }} 个待确认</button>
        </div>
        <div class="device-point-baselines">
          <article v-for="point in pointsFor(device.deviceId)" :key="point.pointId" class="baseline-card">
            <div class="baseline-card-head"><div class="device-symbol">⌁</div><div><b>{{ point.pointName }}</b><small>{{ point.pointCode }}<template v-if="point.modalityType"> · {{ modalityName(point.modalityType) }}</template></small></div><span :class="['status-tag', pointState(point).tone]"><i></i>{{ pointState(point).label }}</span></div>
            <div class="baseline-progress"><div><span>{{ isVibPoint(point) ? '窗口内有效样本（最低门槛）' : '已学习样本（最低门槛）' }}</span><b>{{ progress(point).learned }} / {{ progress(point).minimum }}</b></div><div class="progress-track"><i :style="{ width: `${Math.min(100, progress(point).learned / progress(point).minimum * 100)}%` }"></i></div></div>
            <!-- 旋转振动：v1 基线（1X / 2X、包络、温度） -->
            <div v-if="isVibPoint(point)" class="baseline-values"><div><small>1X / 2X 基线</small><b>{{ baselines[point.pointId]?.freq1xBase?.toFixed(3) ?? '—' }} / {{ baselines[point.pointId]?.freq2xBase?.toFixed(3) ?? '—' }} <em>mm/s</em></b></div><div><small>BPFO 包络 / 总 RMS</small><b>{{ baselines[point.pointId]?.envelopeBpfoBase?.toFixed(3) ?? '—' }} / {{ baselines[point.pointId]?.vBaseRms?.toFixed(3) ?? '—' }} <em>mm/s</em></b></div><div><small>温度 / 1X CV</small><b>{{ baselines[point.pointId]?.tempBase?.toFixed(1) ?? '—' }} ℃ / {{ baselines[point.pointId]?.cv1x?.toFixed(3) ?? '—' }}</b></div></div>
            <!-- 多工况分箱基线：变频测点在额定箱之外存在独立学习的转速段箱时逐箱展示 -->
            <div v-if="isVibPoint(point) && vibRegimes(point).length" class="regime-bins">
              <small class="regime-bins-title">多工况分箱基线</small>
              <div v-for="bin in vibRegimes(point)" :key="bin.binIndex" class="regime-bin-row">
                <b>{{ bin.regimeLabel }}</b>
                <span :class="['status-tag', baselineState(bin.status).tone]"><i></i>{{ baselineState(bin.status).label }}</span>
                <small>{{ bin.sampleCount }} 样本</small>
                <small>1X {{ formatNumber(bin.featureP50?.freq_1x_amp, 3) }} · RMS {{ formatNumber(bin.featureP50?.v_rms, 3) }} mm/s</small>
              </div>
            </div>
            <!-- 其它模态：v2 通道基线（逐特征 P50 / CV / 样本数 / 状态）+ 该模态的学习口径 -->
            <template v-else>
              <p class="channel-rule">{{ channelRule(point) }}</p>
              <div class="table-wrap">
                <table>
                  <thead><tr><th>特征</th><th>基线 P50</th><th>变异系数 CV</th><th>样本数</th><th>状态</th></tr></thead>
                  <tbody>
                    <tr v-for="state in channelBaselines[point.pointId]?.featureStates ?? []" :key="`${state.binIndex ?? 0}:${state.featureKey}`">
                      <td><b>{{ featureTitle(state.featureKey) }}</b><span v-if="channelHasRegimeBins(point) && (state.binIndex ?? 0) > 0 && state.regimeLabel" class="regime-badge">{{ state.regimeLabel }}</span></td>
                      <td>{{ formatNumber(state.baselineP50, 3) }}</td>
                      <td>{{ formatNumber(state.cvValue, 3) }}</td>
                      <td>{{ state.sampleCount }}</td>
                      <td><span :class="['status-tag', baselineState(state.status).tone]"><i></i>{{ baselineState(state.status).label }}</span></td>
                    </tr>
                    <tr v-if="!channelBaselines[point.pointId]?.featureStates?.length"><td colspan="5" class="empty-cell">{{ loadErrors[point.pointId] ? '通道基线加载失败，请点击“刷新”重试' : loaded ? '通道基线尚未建立，样本学习中' : '正在加载…' }}</td></tr>
                  </tbody>
                </table>
              </div>
            </template>
            <div v-if="isVibPoint(point) && baselines[point.pointId]?.warning" class="warning-note">{{ baselines[point.pointId]?.warning }} · 学习周期将延长</div>
            <div v-else-if="channelBaselines[point.pointId]?.warning" class="warning-note">{{ channelBaselines[point.pointId]?.warning }}</div>
            <label v-if="auth.hasRole('ADMIN')" class="baseline-select"><input type="checkbox" :checked="selected(point)" :disabled="!['ACTIVE', 'PENDING_RESET'].includes(pointStatus(point) || '')" @change="togglePoint(device.deviceId, point.pointId, $event)" />选择此测点参与设备基线操作</label>
          </article>
          <div v-if="!pointsFor(device.deviceId).length" class="empty-state"><b>暂无测点</b><p>为设备添加测点后即可学习基线。</p></div>
        </div>
      </article>
      <div v-if="!devices.length" class="empty-state"><b>暂无设备</b><p>创建设备和测点后会自动开始基线学习。</p></div>
    </section>
  </div>
</template>

<style scoped>
.device-baseline-grid { grid-template-columns: 1fr; }
.device-baseline-group { padding: 16px; display: grid; gap: 14px; align-content: start; }
.device-baseline-head { display: flex; gap: 10px; align-items: center; }
.device-baseline-head>div:nth-child(2) { flex: 1; }
.device-baseline-head b { font-size: 12px; }
.device-baseline-head small { display: block; margin-top: 4px; color: var(--muted); font-size: 9px; }
.device-baseline-actions { display: flex; flex-wrap: wrap; gap: 7px; }
.device-point-baselines { display: grid; gap: 10px; }
.device-point-baselines .baseline-card { padding: 12px; border: 1px solid var(--line); border-radius: 9px; }
.device-point-baselines .table-wrap { margin-top: 0; }
.device-point-baselines .empty-cell { padding: 12px !important; }
.channel-rule { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.6; }
.baseline-select { display: flex; align-items: center; gap: 7px; color: var(--muted); font-size: 9px; }
.baseline-select input { accent-color: var(--teal); }
.regime-bins { display: grid; gap: 5px; margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--line); }
.regime-bins-title { color: var(--muted); font-size: 9px; }
.regime-bin-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 10px; }
.regime-bin-row b { font-size: 10px; }
.regime-bin-row small { color: var(--muted); font-size: 9px; }
.regime-badge { margin-left: 6px; padding: 1px 5px; border-radius: 6px; border: 1px solid var(--line); color: var(--teal); font-size: 8px; }
</style>
