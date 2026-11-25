<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { createDevice, createFactory, createPoint, getBearingModels, getDevices, getDeviceTypes, getFactories, getModalities, getOverview, getPointsByFactory, updateDevice, updateFactory, updatePoint } from '@/api/pdm'
import type { BearingModel, ModalityCatalog, PointChannelUpdate } from '@/api/types'
import type { Device, DeviceHealth, DeviceType, Factory, Point } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useUiDialog, type DialogField } from '@/composables/ui'
import { featureTitle, modalityName, modalityShortName, modalityTone } from '@/modality'
import { pointStatusLabel, statusTone } from '@/telemetry-status'

const auth = useAuthStore()
const dialog = useUiDialog()
const factories = ref<Factory[]>([])
const devices = ref<Device[]>([])
const deviceTypes = ref<DeviceType[]>([])
const pointsByDevice = ref<Record<number, Point[]>>({})
const healthByDevice = ref<Record<number, DeviceHealth>>({})
const selectedFactory = ref('')
const busy = ref(false)
const loading = ref(false)
const error = ref('')
const activeCreateForm = ref<'factory' | 'device' | 'point' | null>(null)
const createDialogElement = ref<HTMLElement | null>(null)
const factoryForm = ref({ factoryCode: '', factoryName: '' })
const deviceForm = ref({ deviceCode: '', deviceName: '', rpmNominal: 1500 as number | string, powerKw: undefined as number | undefined, deviceTypeId: undefined as number | undefined, isoClass: 'CLASS_II' })
const pointForm = ref({ deviceId: 0, pointCode: '', pointName: '', sensorMac: '', modalityType: 'ROTATING_VIB', bearingKeyword: '', bearingModelId: null as number | null, samplingRate: '', engineeringUnit: '' })
const bearingModels = ref<BearingModel[]>([])
const matchedBearing = computed(() => bearingModels.value.find((model) => model.id === pointForm.value.bearingModelId) ?? null)
const catalogs = ref<ModalityCatalog[]>([])
const metadataValues = ref<Record<string, string>>({})
const selectedCatalog = computed(() => catalogs.value.find((item) => item.modalityType === pointForm.value.modalityType) ?? null)
const isVibForm = computed(() => pointForm.value.modalityType === 'ROTATING_VIB')
/** 新增测点「遥测数据要求」清单：必需上报取目录 requiredFeatures；可选上报为基线 ∪ 时序特征去掉必需项，仅表达目录登记的数据用途，不代表对 HI 的影响权重。 */
interface TelemetryFeatureRow { code: string; title: string; inBaseline: boolean; inSeries: boolean }
const telemetryRequiredFeatures = computed<TelemetryFeatureRow[]>(() =>
  (selectedCatalog.value?.requiredFeatures ?? []).map((code) => ({ code, title: featureTitle(code), inBaseline: false, inSeries: false })))
const telemetryOptionalFeatures = computed<TelemetryFeatureRow[]>(() => {
  const catalog = selectedCatalog.value
  if (!catalog) return []
  const required = new Set(catalog.requiredFeatures ?? [])
  const baseline = new Set(catalog.baselineFeatureCodes ?? [])
  const series = new Set(catalog.seriesFeatureCodes ?? [])
  return [...new Set([...baseline, ...series])]
    .filter((code) => !required.has(code))
    .map((code) => ({ code, title: featureTitle(code), inBaseline: baseline.has(code), inSeries: series.has(code) }))
})
watch(activeCreateForm, async (form) => {
  if (!form) return
  await nextTick()
  createDialogElement.value?.querySelector<HTMLElement>('input:not([disabled]), select:not([disabled])')?.focus()
})

function handleCreateDialogKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    activeCreateForm.value = null
    return
  }
  if (event.key !== 'Tab' || !createDialogElement.value) return
  const focusable = [...createDialogElement.value.querySelectorAll<HTMLElement>(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  )]
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
/** 模态下拉：优先插件目录；目录未返回时退回既有两项，保证表单始终可用。 */
const modalityOptions = computed(() => catalogs.value.length
  ? catalogs.value.map((item) => ({ value: item.modalityType, label: `${modalityName(item.modalityType)} · ${item.modalityType}` }))
  : [{ value: 'ROTATING_VIB', label: '旋转振动' }, { value: 'MCSA', label: '电机电流 (MCSA)' }])

/** 切换模态时按目录元数据字段预填数值默认值。 */
function prefillMetadata() {
  const fields = selectedCatalog.value?.metadataFields ?? []
  metadataValues.value = Object.fromEntries(fields.map((field) => [field.key, field.defaultValue != null ? String(field.defaultValue) : '']))
}
watch(() => [pointForm.value.modalityType, catalogs.value], prefillMetadata)

/** 轴承型号模糊搜索（文档 §8.2）：keyword 输入触发后端字典检索；新建表单与编辑对话框共用，250ms 去抖。 */
let bearingSearchTimer: number | undefined
function fetchBearings(keyword?: string) {
  window.clearTimeout(bearingSearchTimer)
  bearingSearchTimer = window.setTimeout(async () => {
    try { bearingModels.value = await getBearingModels(keyword) } catch { /* 检索失败保留上一次结果 */ }
  }, 250)
}
function searchBearings() { fetchBearings(pointForm.value.bearingKeyword) }

/** 编辑对话框里的轴承型号选项：保持不变 / 解除绑定（已绑定时）/ 检索结果；检索后经函数形式响应式刷新。 */
const editBearing = ref({ bound: false, name: '' })
const editBearingOptions = computed(() => [
  { value: 'keep', label: `保持不变${editBearing.value.name ? `（${editBearing.value.name}）` : '（未绑定）'}` },
  ...(editBearing.value.bound ? [{ value: 'unbind', label: '解除绑定' }] : []),
  ...bearingModels.value.map((model) => ({ value: String(model.id), label: `${model.modelName}（${model.manufacturer}）` })),
])

/** 设备下是否有旋转振动测点：ISO 等级（振动烈度分级）只对含振动测点的设备有意义。 */
const hasVibrationPoint = (deviceId: number) =>
  (pointsByDevice.value[deviceId] ?? []).some((point) => !point.modalityType || point.modalityType === 'ROTATING_VIB')

function bearingFormula(model: BearingModel | null): string {
  if (!model) return ''
  return `BPFO=(n/2)(1-(d/D)cosα)=${model.bpfoFactor.toFixed(4)} · BPFI=(n/2)(1+(d/D)cosα)=${model.bpfiFactor.toFixed(4)}`
}
let loadSequence = 0

async function load() {
  const sequence = ++loadSequence
  loading.value = true
  error.value = ''
  try {
    const [fetchedFactories, fetchedTypes, overview, fetchedCatalogs] = await Promise.all([
      getFactories(), getDeviceTypes(), getOverview(), getModalities().catch(() => [] as ModalityCatalog[]),
    ])
    if (sequence !== loadSequence) return
    factories.value = fetchedFactories
    deviceTypes.value = fetchedTypes
    catalogs.value = fetchedCatalogs
    healthByDevice.value = Object.fromEntries(overview.devices.map((health) => [health.deviceId, health]))
    if (!fetchedTypes.some((type) => type.id === deviceForm.value.deviceTypeId)) {
      deviceForm.value.deviceTypeId = fetchedTypes.find((type) => type.typeCode === 'GENERAL')?.id ?? fetchedTypes[0]?.id
    }
    if (!selectedFactory.value && fetchedFactories.length) {
      selectedFactory.value = fetchedFactories[0].factoryCode
      return
    }
    // 车间设备与车间全部测点一次并行拉取，按 deviceId 分组装配，消除按设备循环 getPoints 的 N+1 请求风暴
    const [fetchedDevices, factoryPoints] = selectedFactory.value
      ? await Promise.all([getDevices(selectedFactory.value), getPointsByFactory(selectedFactory.value)])
      : [[] as Device[], [] as Point[]]
    if (sequence !== loadSequence) return
    devices.value = fetchedDevices
    const pointsGrouped = new Map<number, Point[]>()
    for (const point of factoryPoints) {
      const bucket = pointsGrouped.get(point.deviceId)
      if (bucket) bucket.push(point)
      else pointsGrouped.set(point.deviceId, [point])
    }
    pointsByDevice.value = Object.fromEntries(pointsGrouped)
    if (!fetchedDevices.some((device) => device.id === pointForm.value.deviceId)) pointForm.value.deviceId = fetchedDevices[0]?.id ?? 0
  } catch (e) {
    if (sequence === loadSequence) error.value = e instanceof Error ? e.message : '设备数据加载失败'
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}
watch(selectedFactory, () => {
  devices.value = []
  pointsByDevice.value = {}
  pointForm.value.deviceId = 0
  void load()
})
onMounted(load)

async function saveFactory() {
  busy.value = true; error.value = ''
  try { await createFactory(factoryForm.value); factoryForm.value = { factoryCode: '', factoryName: '' }; activeCreateForm.value = null; await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '创建工厂失败' }
  finally { busy.value = false }
}
const ISO_CLASS_OPTIONS = [
  { value: 'CLASS_I', label: 'CLASS_I · 小型电机 (<15 kW, 1.8/2.8 mm/s)' },
  { value: 'CLASS_II', label: 'CLASS_II · 中型机组 (15~75 kW, 2.8/4.5 mm/s)' },
  { value: 'CLASS_III', label: 'CLASS_III · 大型刚性基础 (4.5/7.1 mm/s)' },
  { value: 'CLASS_IV', label: 'CLASS_IV · 大型柔性基础 (7.1/11.2 mm/s)' },
]
const DEVICE_STATUS_OPTIONS = [
  { value: '1', label: '1 · 在运监测' },
  { value: '2', label: '2 · 检修中（静默告警与学习）' },
  { value: '3', label: '3 · 已停用/报废（静默告警与学习）' },
]

async function saveDevice() {
  if (!selectedFactory.value || deviceForm.value.deviceTypeId == null) return
  busy.value = true; error.value = ''
  try {
    const { powerKw, rpmNominal, ...device } = deviceForm.value
    const parsedRpm = rpmNominal === '' || rpmNominal == null ? 0 : Number(rpmNominal)
    if (!Number.isFinite(parsedRpm) || parsedRpm < 0) throw new Error('额定转速不能为负数（非旋转设备可填 0 或留空）。')
    await createDevice({
      ...device,
      rpmNominal: parsedRpm,
      powerKw: typeof powerKw === 'number' && Number.isFinite(powerKw) ? powerKw : undefined,
      factoryCode: selectedFactory.value,
    })
    deviceForm.value.deviceCode = ''
    deviceForm.value.deviceName = ''
    activeCreateForm.value = null
    await load()
  }
  catch (e) { error.value = e instanceof Error ? e.message : '创建设备失败' }
  finally { busy.value = false }
}
async function savePoint() {
  busy.value = true; error.value = ''
  try {
    const form = pointForm.value
    // 通道字段：目录里登记的 metadataFields 数值化后提交；空值不发送（省略 = 不覆盖）
    // 采样率为后端 Integer（> 0），工程单位最长 32 字符——提交前先在前端拦住，给出明确提示
    const rate = Number(form.samplingRate)
    if (form.samplingRate !== '' && !(Number.isInteger(rate) && rate > 0)) throw new Error('采样率必须为正整数（Hz），留空表示不配置。')
    if (form.engineeringUnit.trim().length > 32) throw new Error('工程单位长度不能超过 32 个字符。')
    const metadataConfig: Record<string, number> = {}
    for (const field of selectedCatalog.value?.metadataFields ?? []) {
      const raw = metadataValues.value[field.key]
      if (raw != null && raw !== '') {
        const num = Number(raw)
        if (Number.isFinite(num)) metadataConfig[field.key] = num
      }
    }
    await createPoint({
      deviceId: form.deviceId, pointCode: form.pointCode, pointName: form.pointName,
      sensorMac: form.sensorMac, modalityType: form.modalityType,
      bearingModelId: isVibForm.value ? form.bearingModelId : null,
      pluginIdentifier: selectedCatalog.value?.pluginIdentifier,
      samplingRate: form.samplingRate !== '' && Number.isFinite(rate) && rate > 0 ? rate : undefined,
      engineeringUnit: form.engineeringUnit.trim() || undefined,
      metadataConfig: Object.keys(metadataConfig).length ? metadataConfig : undefined,
    })
    pointForm.value.pointCode = ''; pointForm.value.pointName = ''; pointForm.value.sensorMac = ''
    pointForm.value.modalityType = 'ROTATING_VIB'; pointForm.value.bearingKeyword = ''; pointForm.value.bearingModelId = null
    pointForm.value.samplingRate = ''; pointForm.value.engineeringUnit = ''
    prefillMetadata()
    activeCreateForm.value = null
    await load()
  }
  catch (e) { error.value = e instanceof Error ? e.message : '创建测点失败' }
  finally { busy.value = false }
}
async function editFactory() {
  const factory = factories.value.find((item) => item.factoryCode === selectedFactory.value)
  if (!factory) return
  const values = await dialog.form({ title: '编辑工厂', description: `修改 ${factory.factoryCode} 的显示名称。`, fields: [{ name: 'factoryName', label: '工厂名称', value: factory.factoryName, required: true }] })
  if (!values?.factoryName.trim()) return
  try { await updateFactory(factory.id, { factoryName: values.factoryName.trim() }); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '更新工厂失败' }
}
async function editDevice(device: Device) {
  const values = await dialog.form({
    title: '编辑设备',
    description: `设备 ${device.deviceCode} 将使用所选类型的诊断规则；非旋转设备额定转速可填 0。`,
    fields: [
      { name: 'deviceName', label: '设备名称', value: device.deviceName, required: true },
      { name: 'rpmNominal', label: '额定转速（RPM，非旋转设备填 0）', type: 'number', value: String(device.rpmNominal ?? 0), min: 0, step: 0.01 },
      {
        name: 'deviceTypeId',
        label: '设备类型',
        type: 'select',
        value: String(device.deviceTypeId),
        options: deviceTypes.value.map((type) => ({ value: String(type.id), label: `${type.typeName} · ${type.typeCode}` })),
        required: true,
      },
      {
        name: 'isoClass',
        label: 'ISO 10816 振动分类',
        type: 'select',
        value: device.isoClass || 'CLASS_II',
        options: ISO_CLASS_OPTIONS,
        required: true,
      },
      {
        name: 'status',
        label: '运行状态',
        type: 'select',
        value: String(device.status ?? 1),
        options: DEVICE_STATUS_OPTIONS,
        required: true,
      },
    ],
  })
  if (!values) return
  const deviceTypeId = Number(values.deviceTypeId)
  const rawRpm = values.rpmNominal?.trim() ?? ''
  const rpmNominal = rawRpm === '' ? 0 : Number(rawRpm)
  const status = Number(values.status)
  if (!values.deviceName.trim() || !Number.isFinite(rpmNominal) || rpmNominal < 0 || ![1, 2, 3].includes(status) || !deviceTypes.value.some((type) => type.id === deviceTypeId)) {
    error.value = '请填写合法的设备名称、非负额定转速、运行状态和设备类型。'
    return
  }
  try {
    await updateDevice(device.id, { deviceName: values.deviceName.trim(), rpmNominal, powerKw: device.powerKw, status, deviceTypeId, isoClass: values.isoClass || device.isoClass })
    await load()
  }
  catch (e) { error.value = e instanceof Error ? e.message : '更新设备失败' }
}
/**
 * 把编辑对话框的取值转成 PUT /api/v1/points/{id} 载荷：只提交发生变化的字段（省略 / null = 保持不变）。
 * 采样率留空 = 保持不变（接口无法把采样率清空）；机理参数提交即整表替换，故以现有配置为底只改动用户改过的键。
 */
function pointUpdateFrom(point: Point, catalog: ModalityCatalog | null, values: Record<string, string>):
  { pointName: string; sensorMac: string; bearingModelId?: number } & PointChannelUpdate | { error: string } {
  const rateRaw = values.samplingRate?.trim() ?? ''
  const rate = Number(rateRaw)
  if (rateRaw && (!Number.isInteger(rate) || rate <= 0)) return { error: '采样率必须为正整数（Hz）。' }
  const unit = values.engineeringUnit?.trim() ?? ''
  if (unit.length > 32) return { error: '工程单位长度不能超过 32 个字符。' }
  const metadata: Record<string, unknown> = { ...(point.metadataConfig ?? {}) }
  let metadataChanged = false
  for (const field of catalog?.metadataFields ?? []) {
    const raw = values[`meta:${field.key}`]?.trim() ?? ''
    if (raw === '') {
      // 清空 = 删除该覆盖项（回落插件默认值）
      if (field.key in metadata) {
        delete metadata[field.key]
        metadataChanged = true
      }
      continue
    }
    const number = Number(raw)
    if (!Number.isFinite(number)) return { error: `${field.label}必须为数值。` }
    if (metadata[field.key] !== number) {
      metadata[field.key] = number
      metadataChanged = true
    }
  }
  const payload: { pointName: string; sensorMac: string; bearingModelId?: number } & PointChannelUpdate = {
    pointName: values.pointName.trim(),
    sensorMac: values.sensorMac.trim(),
  }
  if (rateRaw && rate !== point.samplingRate) payload.samplingRate = rate
  if (unit && unit !== (point.engineeringUnit ?? '')) payload.engineeringUnit = unit
  if (metadataChanged) payload.metadataConfig = metadata
  const bearing = values.bearingModelId
  if (bearing === 'unbind') {
    if (point.bearingModelId != null) payload.bearingModelId = 0 // 0 = 解除绑定；省略 = 保持不变
  } else if (bearing && bearing !== 'keep') payload.bearingModelId = Number(bearing)
  return payload
}

/**
 * 编辑测点：名称 / 传感器 + 按模态的通道配置（采样率、工程单位、目录登记的机理参数；旋转振动测点另含轴承型号）。
 * 留空表示保持不变；不提供修改测量模态。
 */
async function editPoint(point: Point) {
  const catalog = catalogs.value.find((item) => item.modalityType === point.modalityType) ?? null
  const vib = point.modalityType === 'ROTATING_VIB'
  editBearing.value = { bound: point.bearingModelId != null, name: point.bearingModelName ?? '' }
  if (vib) fetchBearings(point.bearingModelName ?? '') // 预载当前型号，保证下拉里有它可对照
  const metadata = point.metadataConfig ?? {}
  const fields: DialogField[] = [
    { name: 'pointName', label: '测点名称', value: point.pointName, required: true },
    { name: 'sensorMac', label: '传感器序列号', value: point.sensorMac || '', placeholder: '留空可解除绑定' },
    { name: 'samplingRate', label: '采样率 (Hz)', type: 'number', value: point.samplingRate != null ? String(point.samplingRate) : '',
      min: 1, step: 1, placeholder: point.samplingRate == null ? '未配置；留空保持不变' : '留空保持不变' },
    { name: 'engineeringUnit', label: '工程单位', value: point.engineeringUnit ?? '', maxLength: 32,
      placeholder: point.engineeringUnit ? '留空保持不变' : '未配置，如 A / mm / %' },
    ...(catalog?.metadataFields ?? []).map((field): DialogField => ({
      name: `meta:${field.key}`, label: field.label, type: 'number', step: 'any',
      value: typeof metadata[field.key] === 'number' ? String(metadata[field.key]) : '', placeholder: field.description,
    })),
  ]
  if (vib) fields.push({
    name: 'bearingModelId', label: '轴承型号', type: 'select', value: 'keep',
    options: () => editBearingOptions.value, search: { placeholder: '输入型号模糊搜索，如 6208', onSearch: fetchBearings },
  })
  const values = await dialog.form({
    title: '编辑测点',
    description: `修改 ${point.pointCode} 的名称、传感器绑定与${modalityName(point.modalityType)}通道配置。留空表示保持不变；测量模态创建后不可修改。`,
    fields,
  })
  if (!values?.pointName.trim()) return
  const payload = pointUpdateFrom(point, catalog, values)
  if ('error' in payload) {
    error.value = payload.error
    return
  }
  try {
    await updatePoint(point.id, payload)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '更新测点失败'
  }
}
</script>

<template>
  <div class="page-stack">
    <div class="page-intro"><div><h2>资产拓扑</h2><p>维护工厂、设备与测点的层级关系，配置编码需与边缘遥测一致。</p></div><div class="filter-group"><label>当前工厂</label><select v-model="selectedFactory" class="compact-select"><option value="">选择工厂</option><option v-for="factory in factories" :key="factory.id" :value="factory.factoryCode">{{ factory.factoryName }} · {{ factory.factoryCode }}</option></select><button v-if="auth.hasRole('ADMIN') && selectedFactory" class="small-button" @click="editFactory">编辑工厂</button></div></div>
    <div v-if="error" class="notice error-notice">{{ error }}</div>
    <section v-if="auth.hasRole('ADMIN')" class="asset-create-toolbar" aria-label="新增资产">
      <div class="asset-create-copy"><h3>资产维护</h3><p>选择需要新增的对象</p></div>
      <div class="asset-create-actions">
        <button class="asset-create-button" @click="error = ''; activeCreateForm = 'factory'"><span>＋</span>新增工厂</button>
        <button class="asset-create-button" :disabled="!selectedFactory" @click="error = ''; activeCreateForm = 'device'"><span>＋</span>新增设备</button>
        <button class="asset-create-button asset-create-primary" :disabled="!devices.length" @click="error = ''; activeCreateForm = 'point'"><span>＋</span>新增测点</button>
      </div>
    </section>
    <div v-if="activeCreateForm" class="asset-create-backdrop" @click.self="activeCreateForm = null">
      <section ref="createDialogElement" class="asset-create-dialog" role="dialog" aria-modal="true" :aria-labelledby="`create-${activeCreateForm}-title`" @keydown="handleCreateDialogKeydown">
        <header class="asset-create-heading">
          <div><span class="asset-create-kicker">ASSET SETUP</span><h3 :id="`create-${activeCreateForm}-title`">{{ activeCreateForm === 'factory' ? '新增工厂' : activeCreateForm === 'device' ? '新增设备' : '新增测点' }}</h3></div>
          <button type="button" class="asset-create-close" aria-label="关闭" @click="activeCreateForm = null">×</button>
        </header>
        <div v-if="error" class="notice error-notice asset-create-error">{{ error }}</div>
        <form v-if="activeCreateForm === 'factory'" class="form-panel asset-create-form" @submit.prevent="saveFactory"><div class="panel-heading"><div><h3>工厂信息</h3><p>工厂编码全局唯一</p></div><span class="step-no">01</span></div><label>工厂编码<input v-model="factoryForm.factoryCode" placeholder="如 FACT_01" required /></label><label>工厂名称<input v-model="factoryForm.factoryName" placeholder="如 一号生产厂" required /></label><button class="primary-button" :disabled="busy">创建工厂 <span>→</span></button></form>
        <form v-else-if="activeCreateForm === 'device'" class="form-panel asset-create-form" @submit.prevent="saveDevice">
        <div class="panel-heading"><div><h3>新增设备</h3><p>设备类型决定适用的诊断规则</p></div><span class="step-no">02</span></div>
        <label>设备编码<input v-model="deviceForm.deviceCode" placeholder="如 PUMP_P102A" required /></label>
        <label>设备名称<input v-model="deviceForm.deviceName" placeholder="如 循环水泵 A" required /></label>
        <label>设备类型
          <select v-model="deviceForm.deviceTypeId" required>
            <option v-for="type in deviceTypes" :key="type.id" :value="type.id">{{ type.typeName }} · {{ type.typeCode }}</option>
          </select>
        </label>
        <label>ISO 10816 分类
          <select v-model="deviceForm.isoClass">
            <option v-for="opt in ISO_CLASS_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>
        </label>
        <div class="field-row"><label>额定转速 (RPM，非旋转填 0)<input v-model="deviceForm.rpmNominal" type="number" min="0" step="0.01" /></label><label>功率 (kW)<input v-model.number="deviceForm.powerKw" type="number" min="0" step="0.01" /></label></div>
        <button class="primary-button" :disabled="busy || loading || !selectedFactory || deviceForm.deviceTypeId == null">创建设备 <span>→</span></button>
      </form>
        <form v-else class="form-panel asset-create-form" @submit.prevent="savePoint"><div class="panel-heading"><div><h3>新增测点</h3><p>绑定传感器与测量位置</p></div><span class="step-no">03</span></div><label>所属设备<select v-model.number="pointForm.deviceId" required><option v-for="device in devices" :key="device.id" :value="device.id">{{ device.deviceCode }}</option></select></label><label>测点编码<input v-model="pointForm.pointCode" placeholder="如 DE_RADIAL" required /></label><label>测点名称<input v-model="pointForm.pointName" placeholder="如 驱动端径向" required /></label><label>传感器序列号<input v-model="pointForm.sensorMac" placeholder="可选" /></label><label>测量模态<select v-model="pointForm.modalityType"><option v-for="option in modalityOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><template v-if="isVibForm"><label>轴承型号<select v-model.number="pointForm.bearingModelId"><option :value="null">未绑定</option><option v-for="model in bearingModels" :key="model.id" :value="model.id">{{ model.modelName }}（{{ model.manufacturer }}）</option></select></label><label>型号模糊搜索<input v-model="pointForm.bearingKeyword" placeholder="如 6208" @input="searchBearings" /></label><p v-if="matchedBearing" class="muted bearing-formula">理论特征频率系数（×转频）：{{ bearingFormula(matchedBearing) }}<br />BSF {{ matchedBearing.bsfFactor.toFixed(4) }} · FTF {{ matchedBearing.ftfFactor.toFixed(4) }} · 滚动体 {{ matchedBearing.ballCount }} × ⌀{{ matchedBearing.ballDiameter }}mm / 节圆 ⌀{{ matchedBearing.pitchDiameter }}mm / 接触角 {{ matchedBearing.contactAngleDeg }}°</p></template><template v-else><label>采样率 (Hz)<input v-model="pointForm.samplingRate" type="number" min="1" step="1" placeholder="可选，正整数；事件型通道为触发采样率" /></label><label>工程单位<input v-model="pointForm.engineeringUnit" maxlength="32" placeholder="可选，如 A / mm / %" /></label><label v-for="field in selectedCatalog?.metadataFields ?? []" :key="field.key" :title="field.description">{{ field.label }}<input v-model="metadataValues[field.key]" type="number" step="any" :placeholder="field.defaultValue != null ? `默认 ${field.defaultValue}` : '可选'" /></label><p v-if="selectedCatalog" class="muted bearing-formula">将绑定机理插件 <span class="subtle-code">{{ selectedCatalog.pluginIdentifier }}</span> · {{ selectedCatalog.cadence === 'EVENT' ? '事件触发采集' : '连续采集' }}<br /><span class="muted">{{ selectedCatalog.metadataFields.length ? '通道元数据参数已按目录预填默认值' : '该模态无需额外通道元数据' }}</span></p><p v-else class="muted bearing-formula">该模态的插件目录尚未加载，仅提交基础信息。</p></template>
<div class="telemetry-requirements">
  <div class="telemetry-requirements-heading"><b>遥测数据要求</b><small>{{ modalityName(pointForm.modalityType) }} · 遥测生产端按此对齐上报字段</small></div>
  <template v-if="selectedCatalog">
    <div class="telemetry-feature-group"><span class="telemetry-feature-group-label">必需上报</span><div v-if="telemetryRequiredFeatures.length" class="telemetry-feature-list"><span v-for="feature in telemetryRequiredFeatures" :key="feature.code" class="telemetry-feature-item"><b>{{ feature.title }}</b><span class="subtle-code">{{ feature.code }}</span></span></div><small v-else class="muted">目录未声明该模态的必需字段。</small></div>
    <div class="telemetry-feature-group"><span class="telemetry-feature-group-label">可选上报</span><div v-if="telemetryOptionalFeatures.length" class="telemetry-feature-list"><span v-for="feature in telemetryOptionalFeatures" :key="feature.code" class="telemetry-feature-item"><b>{{ feature.title }}</b><span class="subtle-code">{{ feature.code }}</span><span v-if="feature.inBaseline" class="telemetry-cap-tag">基线学习</span><span v-if="feature.inSeries" class="telemetry-cap-tag">时序可查</span></span></div><small v-else class="muted">目录未声明必需项之外的可选字段。</small></div>
    <small class="telemetry-requirements-note">缺少上述必需字段的遥测将被接入服务拒绝；「基线学习 / 时序可查」仅表示目录已登记该字段可参与的下游能力，不代表其直接影响健康指数（HI）——评分行为由模态机理插件实现。</small>
  </template>
  <small v-else class="muted">未能加载模态目录，字段上报要求（必需 / 可选）无法显示——这不代表没有要求；缺失必需字段的遥测仍会被接入服务拒绝。</small>
</div>
<button class="primary-button" :disabled="busy || loading || !devices.length">创建测点 <span>→</span></button></form>
      </section>
    </div>
    <section class="panel table-panel"><div class="panel-heading"><div><h3>设备清单</h3><p>{{ selectedFactory || '请选择工厂' }} · {{ devices.length }} 台设备</p></div><button class="quiet-button" @click="load">↻ 刷新</button></div>
      <div class="device-list"><article v-for="device in devices" :key="device.id" class="device-row"><div class="device-symbol">▦</div><div class="device-main"><div><RouterLink :to="`/devices/${device.id}`" class="table-link"><b>{{ device.deviceName }}</b></RouterLink><span class="subtle-code">{{ device.deviceCode }}</span></div><small>{{ device.deviceTypeName }}<template v-if="device.rpmNominal && device.rpmNominal > 0"> · {{ device.rpmNominal }} RPM</template> · {{ device.powerKw ?? '—' }} kW<template v-if="hasVibrationPoint(device.id)"> · {{ device.isoClass }}</template></small></div><RouterLink :to="`/devices/${device.id}`" class="device-health-link"><span :class="['status-tag', healthByDevice[device.id] ? statusTone(healthByDevice[device.id].status) : 'gray']"><i></i>{{ pointStatusLabel(healthByDevice[device.id]?.status) }}</span><b>{{ healthByDevice[device.id]?.healthIndex?.toFixed(1) ?? '—' }}</b><small>设备健康 · {{ healthByDevice[device.id]?.scoredPointCount ?? 0 }}/{{ healthByDevice[device.id]?.pointCount ?? pointsByDevice[device.id]?.length ?? 0 }} 点</small></RouterLink><button v-if="auth.hasRole('ADMIN')" class="small-button" :disabled="!deviceTypes.length" @click="editDevice(device)">编辑设备</button><div class="device-points"><template v-for="point in pointsByDevice[device.id] || []" :key="point.id"><RouterLink :to="`/points/${point.id}`" class="point-chip"><span class="point-pulse"></span>{{ point.pointCode }}<span :class="['status-tag', 'chip-modality', modalityTone(point.modalityType)]">{{ modalityShortName(point.modalityType) }}</span><small>{{ pointStatusLabel(point.lastStatus) }}</small></RouterLink><button v-if="auth.hasRole('ADMIN')" class="small-button" @click="editPoint(point)">编辑测点</button></template><span v-if="!pointsByDevice[device.id]?.length" class="muted">暂无测点</span></div><span :class="`status-tag ${device.status === 1 ? 'green' : 'gray'}`"><i></i>{{ device.status === 1 ? '在运' : device.status === 2 ? '检修中' : '已停用' }}</span></article>
      <div v-if="!devices.length" class="empty-state"><div>▦</div><b>{{ loading ? '正在加载设备…' : '还没有设备' }}</b><p v-if="!loading">先创建工厂，再添加设备和测点。</p></div></div>
    </section>
  </div>
</template>

<style scoped>
.device-health-link { flex: 0 0 118px; display: grid; justify-items: start; gap: 3px; color: inherit; text-decoration: none; }
.device-health-link b { font-size: 15px; }
.device-health-link small { color: #8294aa; font-size: 9px; }
.device-health-link:hover b { color: #69d7c3; }
@media (max-width: 700px) { .device-health-link { flex: 1 1 120px; } }
.bearing-formula { margin: -4px 0 0; line-height: 1.7; }
.point-chip .chip-modality { padding: 3px 6px; font-size: 8px; }
.asset-create-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 15px 18px; border: 1px solid var(--line); border-radius: 12px; background: var(--panel); }
.asset-create-copy h3 { margin: 0; color: var(--text); font-size: 12px; font-weight: 650; }
.asset-create-copy p { margin: 5px 0 0; color: var(--muted); font-size: 10px; }
.asset-create-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.asset-create-button { display: inline-flex; align-items: center; gap: 7px; min-height: 34px; padding: 7px 11px; border: 1px solid var(--line); border-radius: 8px; background: var(--panel-2); color: var(--text); font-size: 10px; transition: border-color .16s, background .16s, transform .16s; }
.asset-create-button:hover:not(:disabled) { border-color: color-mix(in srgb, var(--teal) 52%, var(--line)); background: color-mix(in srgb, var(--teal) 9%, var(--panel-2)); transform: translateY(-1px); }
.asset-create-button:disabled { cursor: not-allowed; opacity: .48; }
.asset-create-button>span { color: var(--teal); font-size: 15px; line-height: 10px; }
.asset-create-primary { border-color: color-mix(in srgb, var(--teal) 42%, var(--line)); background: color-mix(in srgb, var(--teal) 12%, var(--panel)); }
.asset-create-backdrop { position: fixed; inset: 0; z-index: 30; display: grid; place-items: center; padding: 20px; background: rgba(3, 10, 19, .62); backdrop-filter: blur(5px); }
.asset-create-dialog { width: min(720px, 100%); max-height: min(88vh, 900px); overflow: auto; padding: 20px 24px 16px; border: 1px solid var(--line); border-radius: 16px; background: var(--panel); color: var(--text); box-shadow: 0 28px 90px #0008; }
.asset-create-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding-bottom: 14px; border-bottom: 1px solid var(--line); }
.asset-create-kicker { color: var(--teal); font-size: 9px; font-weight: 650; letter-spacing: 1.2px; }
.asset-create-heading h3 { margin: 5px 0 0; font-size: 17px; font-weight: 650; }
.asset-create-error { margin-top: 14px; }
.asset-create-close { width: 30px; height: 30px; border: 1px solid var(--line); border-radius: 8px; background: var(--panel-2); color: var(--muted); font-size: 20px; line-height: 1; }
.asset-create-close:hover { color: var(--text); }
.asset-create-form { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 16px 2px 4px; background: transparent; border: 0; box-shadow: none; }
.asset-create-form .panel-heading, .asset-create-form .primary-button, .asset-create-form>.bearing-formula { grid-column: 1 / -1; }
.telemetry-requirements { grid-column: 1 / -1; display: grid; gap: 9px; padding: 11px 13px; border: 1px solid var(--line); border-radius: 11px; background: var(--panel-2); }
.telemetry-requirements-heading { display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: 4px 10px; }
.telemetry-requirements-heading b { color: var(--text); font-size: 11px; font-weight: 650; }
.telemetry-requirements-heading small { color: var(--muted); font-size: 9px; }
.telemetry-feature-group { display: grid; gap: 6px; }
.telemetry-feature-group-label { color: var(--muted); font-size: 9px; font-weight: 650; letter-spacing: 1px; }
.telemetry-feature-list { display: flex; flex-wrap: wrap; gap: 6px; }
.telemetry-feature-item { display: inline-flex; align-items: center; flex-wrap: wrap; gap: 5px 6px; padding: 4px 8px; border: 1px solid var(--line); border-radius: 8px; background: var(--panel); font-size: 10px; }
.telemetry-feature-item b { color: var(--text); font-weight: 600; }
.telemetry-cap-tag { padding: 2px 5px; border-radius: 6px; background: color-mix(in srgb, var(--teal) 12%, var(--panel-2)); color: var(--teal); font-size: 8px; }
.telemetry-requirements-note { color: var(--muted); font-size: 9px; line-height: 1.7; }
@media(max-width:700px) { .asset-create-toolbar { align-items: flex-start; flex-direction: column; } .asset-create-actions { justify-content: flex-start; } }
@media(max-width:560px) { .asset-create-backdrop { padding: 10px; } .asset-create-dialog { max-height: 92vh; padding: 17px 16px 12px; border-radius: 13px; } .asset-create-form { grid-template-columns: 1fr; } .asset-create-form .panel-heading, .asset-create-form .primary-button, .asset-create-form>.bearing-formula { grid-column: auto; } }
</style>
