<script setup lang="ts">
import { computed, onMounted, shallowRef } from 'vue'
import { createDeviceType, deleteDeviceType, getDeviceTypes, updateDeviceType } from '@/api/pdm'
import type { DeviceType } from '@/api/types'
import { useUiDialog } from '@/composables/ui'
import { useAuthStore } from '@/stores/auth'
import DiagnosticRuleEditor from '@/components/DiagnosticRuleEditor.vue'

const auth = useAuthStore()
const dialog = useUiDialog()
const types = shallowRef<DeviceType[]>([])
const selectedId = shallowRef<number | null>(null)
const typeCode = shallowRef('')
const typeName = shallowRef('')
const busy = shallowRef(false)
const loading = shallowRef(false)
const error = shallowRef('')
const editable = computed(() => auth.hasRole('ADMIN'))
const selectedType = computed(() => types.value.find((type) => type.id === selectedId.value))

async function load(preferredId = selectedId.value) {
  loading.value = true
  error.value = ''
  try {
    types.value = await getDeviceTypes()
    selectedId.value = types.value.find(({ id }) => id === preferredId)?.id
      ?? types.value.find(({ typeCode }) => typeCode === 'GENERAL')?.id
      ?? types.value[0]?.id
      ?? null
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '设备类型加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => load())

async function createType() {
  const code = typeCode.value.trim().toUpperCase()
  const name = typeName.value.trim()
  if (!/^[A-Z0-9][A-Z0-9_-]{0,31}$/.test(code) || !name) {
    error.value = '请填写类型名称和有效编码（字母、数字、下划线或连字符，以字母或数字开头）。'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const created = await createDeviceType({ typeCode: code, typeName: name })
    typeCode.value = ''
    typeName.value = ''
    await load(created.id)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '建立设备类型失败'
  } finally {
    busy.value = false
  }
}

async function renameType(type: DeviceType) {
  const result = await dialog.form({
    title: '编辑设备类型',
    description: `设备类型编码为 ${type.typeCode}。`,
    fields: [{ name: 'typeName', label: '类型名称', value: type.typeName, required: true, maxLength: 128 }],
  })
  if (!result) return
  const name = result.typeName.trim()
  if (!name) {
    error.value = '请输入设备类型名称。'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await updateDeviceType(type.id, { typeName: name })
    await load(type.id)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '更新设备类型失败'
  } finally {
    busy.value = false
  }
}

async function removeType(type: DeviceType) {
  const confirmed = await dialog.confirm({
    title: '删除设备类型',
    description: `确定删除“${type.typeName}”及其配置的全部诊断规则？`,
    confirmLabel: '删除类型',
    danger: true,
  })
  if (!confirmed) return
  busy.value = true
  error.value = ''
  try {
    await deleteDeviceType(type.id)
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '删除设备类型失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="page-stack">
    <div class="page-intro">
      <div>
        <h2>设备类型与诊断规则</h2>
        <p>设备按所属类型匹配规则，告警中心显示命中的因素、诊断依据和排查建议。</p>
      </div>
      <RouterLink to="/devices" class="quiet-button">查看设备与测点</RouterLink>
    </div>
    <div v-if="error" class="notice error-notice" role="alert">{{ error }}</div>

    <div class="diagnostics-layout">
      <section class="panel form-panel type-panel">
        <div class="panel-heading">
          <h3>设备类型</h3>
          <button class="small-button" type="button" :disabled="busy || loading" @click="load()">刷新类型</button>
        </div>
        <label v-if="types.length">
          当前设备类型
          <select v-model="selectedId" :disabled="busy || loading">
            <option v-for="type in types" :key="type.id" :value="type.id">{{ type.typeName }} · {{ type.typeCode }}</option>
          </select>
        </label>
        <p v-else class="muted">{{ loading ? '正在加载设备类型…' : '暂无设备类型' }}</p>
        <div v-if="selectedType" class="type-summary">
          <span>{{ selectedType.deviceCount }} 台关联设备</span>
          <span v-if="selectedType.system" class="status-tag blue">系统类型</span>
        </div>
        <div v-if="editable && selectedType" class="action-row">
          <button class="small-button" type="button" :disabled="busy || loading" @click="renameType(selectedType)">修改名称</button>
          <button
            v-if="!selectedType.system && selectedType.deviceCount === 0"
            class="small-button danger-button"
            type="button"
            :disabled="busy || loading"
            @click="removeType(selectedType)"
          >删除类型</button>
        </div>
        <form v-if="editable" class="type-form" @submit.prevent="createType">
          <h3>新增设备类型</h3>
          <label>类型编码<input v-model.trim="typeCode" maxlength="32" pattern="[A-Za-z0-9][A-Za-z0-9_\-]*" placeholder="如 CENTRIFUGAL_PUMP" required /></label>
          <label>类型名称<input v-model.trim="typeName" maxlength="128" placeholder="如 离心泵" required /></label>
          <button class="primary-button" :disabled="busy || loading">创建设备类型</button>
        </form>
        <p v-if="!editable" class="muted">仅管理员可修改类型与规则。</p>
      </section>

      <DiagnosticRuleEditor
        v-if="selectedType"
        :key="selectedType.id"
        :type-id="selectedType.id"
        :type-code="selectedType.typeCode"
        :type-name="selectedType.typeName"
        :editable="editable"
      />
    </div>
  </div>
</template>

<style scoped>
.diagnostics-layout {
  display: grid;
  grid-template-columns: minmax(205px, 255px) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.type-panel { min-width: 0; }
.type-summary { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; font-size: 11px; color: var(--muted); }
.type-form { display: grid; gap: 12px; border-top: 1px solid var(--line); padding-top: 14px; }
.type-form h3 { margin: 0; font-size: 12px; }

@media (max-width: 900px) {
  .diagnostics-layout { grid-template-columns: minmax(0, 1fr); }
}
</style>
