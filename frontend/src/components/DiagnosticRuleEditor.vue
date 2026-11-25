<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { ApiError } from '@/api/http'
import { createDiagnosticRule, getDiagnosticRules, updateDiagnosticRule } from '@/api/pdm'
import type { DiagnosticRule, DiagnosticRuleInput } from '@/api/types'
import { diagnosticMetrics, diagnosticMetricsForDeviceType, diagnosticOperators, formatDiagnosticCondition } from '@/diagnostics'

const props = defineProps<{ typeId: number; typeCode: string; typeName: string; editable: boolean }>()
type RuleDraft = Omit<DiagnosticRuleInput, 'threshold' | 'frequencyOrder' | 'priority'> & {
  threshold: number | ''
  frequencyOrder: number | ''
  priority: number | ''
}

const rules = shallowRef<DiagnosticRule[]>([])
const sortedRules = computed(() => [...rules.value].sort((a, b) => b.priority - a.priority || a.id - b.id))
const allowedMetrics = computed(() => diagnosticMetricsForDeviceType(props.typeCode))
const hasOrderRule = computed(() => rules.value.some((rule) => diagnosticMetrics[rule.metric].usesOrder))
const draft = ref<RuleDraft | null>(null)
const editingRule = shallowRef<Pick<DiagnosticRule, 'id' | 'version'> | null>(null)
const versionConflict = shallowRef(false)
const metricInput = shallowRef<HTMLSelectElement>()
const currentMetric = computed(() => draft.value ? diagnosticMetrics[draft.value.metric] : undefined)
const loading = shallowRef(false)
const busy = shallowRef(false)
const error = shallowRef('')
let pendingRequest: AbortController | undefined

async function loadRules() {
  pendingRequest?.abort()
  const request = new AbortController()
  pendingRequest = request
  loading.value = true
  error.value = ''
  try {
    rules.value = await getDiagnosticRules(props.typeId, request.signal)
  } catch (cause) {
    if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '诊断规则加载失败'
  } finally {
    if (pendingRequest === request) {
      loading.value = false
      pendingRequest = undefined
    }
  }
}

onMounted(loadRules)
onBeforeUnmount(() => pendingRequest?.abort())

function ruleInput(rule: DiagnosticRule): DiagnosticRuleInput {
  const { metric, frequencyOrder, operator, threshold, priority, factor, evidence, advice, enabled } = rule
  return { metric, frequencyOrder, operator, threshold, priority, factor, evidence, advice, enabled }
}

async function editRule(rule?: DiagnosticRule) {
  editingRule.value = rule ? { id: rule.id, version: rule.version } : null
  versionConflict.value = false
  draft.value = rule ? { ...ruleInput(rule), frequencyOrder: rule.frequencyOrder ?? '' } : {
    metric: allowedMetrics.value[0],
    frequencyOrder: '',
    operator: 'GTE',
    threshold: '',
    priority: 100,
    factor: '',
    evidence: '',
    advice: '',
    enabled: true,
  }
  error.value = ''
  await nextTick()
  metricInput.value?.focus()
}

function cancelEdit() {
  editingRule.value = null
  versionConflict.value = false
  draft.value = null
}

function saveRule() {
  const values = draft.value
  if (!values || versionConflict.value) return
  const metric = diagnosticMetrics[values.metric]
  const { threshold, priority } = values
  const frequencyOrder = metric.usesOrder ? values.frequencyOrder : null
  const factor = values.factor.trim()
  const evidence = values.evidence.trim()
  const advice = values.advice.trim()
  if (typeof threshold !== 'number' || !Number.isFinite(threshold) || (!metric.allowsNegative && threshold < 0)
    || typeof priority !== 'number' || !Number.isInteger(priority) || priority < 0 || priority > 10000
    || (frequencyOrder !== null && (typeof frequencyOrder !== 'number' || !Number.isFinite(frequencyOrder) || frequencyOrder < 0.25 || frequencyOrder > 20))
    || !factor || !evidence || !advice) {
    error.value = '请检查阈值、阶次和优先级，并填写主导因素、诊断依据及排查建议。'
    return
  }
  return persistRule({ ...values, threshold, priority, frequencyOrder, factor, evidence, advice }, editingRule.value, true)
}

async function persistRule(data: DiagnosticRuleInput, original: Pick<DiagnosticRule, 'id' | 'version'> | null, finishEdit = false) {
  busy.value = true
  error.value = ''
  try {
    const saved = original == null
      ? await createDiagnosticRule(props.typeId, data)
      : await updateDiagnosticRule(props.typeId, original.id, data, original.version)
    rules.value = [...rules.value.filter((rule) => rule.id !== saved.id), saved]
    if (finishEdit) cancelEdit()
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 409 && original) {
      versionConflict.value = finishEdit
      await loadRules()
      const conflict = finishEdit
        ? '这条规则已被其他用户更新。当前草稿可复制，请取消编辑后重新打开规则。'
        : '这条规则已被其他用户更新。请查看规则列表后再操作。'
      error.value = [conflict, error.value].filter(Boolean).join(' ')
    } else {
      error.value = cause instanceof Error ? cause.message : '保存诊断规则失败'
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="panel table-panel rules-panel">
    <div class="panel-heading">
      <div>
        <h3>{{ typeName }}的诊断规则</h3>
        <p>按优先级从高到低匹配启用的规则。相同优先级时，较早创建的规则先匹配。</p>
        <p v-if="hasOrderRule && !currentMetric?.usesOrder">阶次谱峰按额定转速估算，匹配范围为目标频率的 ±10%。</p>
      </div>
      <div class="action-row">
        <button class="small-button" type="button" :disabled="loading || busy" @click="loadRules">刷新规则</button>
        <button v-if="editable" class="small-button primary-small" type="button" :disabled="loading || busy || !!draft" @click="editRule()">新增规则</button>
      </div>
    </div>

    <div v-if="error" class="notice error-notice rule-error" role="alert">{{ error }}</div>

    <form v-if="editable && draft" class="form-panel rule-form" @submit.prevent="saveRule">
      <h4>{{ editingRule == null ? '新增诊断规则' : '编辑诊断规则' }}</h4>
      <fieldset :disabled="busy || loading" class="rule-fields">
        <div class="condition-fields">
          <label>诊断指标
            <select ref="metricInput" v-model="draft.metric">
            <option v-for="metric in allowedMetrics" :key="metric" :value="metric">{{ diagnosticMetrics[metric].label }}</option>
            </select>
          </label>
          <label>比较关系
            <select v-model="draft.operator">
              <option v-for="(symbol, operator) in diagnosticOperators" :key="operator" :value="operator">{{ symbol }}</option>
            </select>
          </label>
          <label>阈值（{{ currentMetric?.unit }}）
            <input v-model.number="draft.threshold" type="number" :min="currentMetric?.allowsNegative ? undefined : 0" step="any" required />
          </label>
          <label>优先级
            <input v-model.number="draft.priority" type="number" min="0" max="10000" step="1" required />
          </label>
          <label v-if="currentMetric?.usesOrder">频率阶次（X）
            <input v-model.number="draft.frequencyOrder" type="number" min="0.25" max="20" step="any" placeholder="例如 3" required />
          </label>
        </div>
        <p v-if="currentMetric?.usesOrder" class="muted order-note">
          系统按额定转速估算阶次频率，在 ±10% 的范围内寻找最接近的上报谱峰。变速设备的匹配可能不准确。
        </p>
        <label>主导因素<input v-model.trim="draft.factor" maxlength="128" placeholder="如 疑似转子不平衡" required /></label>
        <label>诊断依据<textarea v-model.trim="draft.evidence" maxlength="512" rows="2" placeholder="描述该振动特征与疑似因素的关系" required /></label>
        <label>排查建议<textarea v-model.trim="draft.advice" maxlength="512" rows="2" placeholder="如 复测径向振动并检查转子积垢" required /></label>
        <label class="enable-rule"><input v-model="draft.enabled" type="checkbox" /> 启用规则</label>
        <div class="action-row">
          <button class="quiet-button" type="button" @click="cancelEdit">取消</button>
          <button class="primary-button" type="submit" :disabled="versionConflict">{{ busy ? '保存中…' : '保存规则' }}</button>
        </div>
      </fieldset>
    </form>

    <div class="table-wrap">
      <table>
        <thead>
          <tr><th>优先级</th><th>因素与依据</th><th>匹配条件</th><th>排查建议</th><th>状态</th><th v-if="editable">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="rule in sortedRules" :key="rule.id">
            <td>{{ rule.priority }}</td>
            <td class="rule-copy"><b>{{ rule.factor }}</b><small>{{ rule.evidence }}</small></td>
            <td>{{ formatDiagnosticCondition(rule.metric, rule.operator, rule.threshold, rule.frequencyOrder) }}</td>
            <td class="rule-copy">{{ rule.advice }}</td>
            <td><span :class="['status-tag', rule.enabled ? 'green' : 'gray']"><i></i>{{ rule.enabled ? '已启用' : '已停用' }}</span></td>
            <td v-if="editable">
              <div class="action-row">
                <button class="small-button" type="button" :disabled="loading || busy || !!draft" @click="editRule(rule)">编辑</button>
                <button
                  class="small-button"
                  type="button"
                  :disabled="loading || busy || !!draft"
                  @click="persistRule({ ...ruleInput(rule), enabled: !rule.enabled }, rule)"
                >{{ rule.enabled ? '停用' : '启用' }}</button>
              </div>
            </td>
          </tr>
          <tr v-if="!sortedRules.length">
            <td :colspan="editable ? 6 : 5" class="empty-cell">{{ loading ? '正在加载诊断规则…' : error ? '暂时无法显示诊断规则' : '此设备类型暂无诊断规则。' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.rules-panel { min-width: 0; }
.rule-error { margin-top: 14px; }
.rule-form { margin-top: 14px; padding: 14px; border: 1px solid var(--line); border-radius: 8px; }
.rule-form h4 { margin: 0; font-size: 13px; }
.rule-fields { display: grid; gap: 12px; margin: 0; border: 0; padding: 0; min-width: 0; }
.condition-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.rule-form textarea { width: 100%; resize: vertical; border: 1px solid var(--line); border-radius: 7px; background: var(--bg); color: var(--text); padding: 9px 10px; font-size: 11px; }
.rule-form textarea:focus-visible { outline: 2px solid var(--teal); outline-offset: 1px; }
.rule-form .enable-rule { display: flex; align-items: center; }
.rule-form .enable-rule input { width: auto; accent-color: var(--teal); }
.order-note { margin: 0; }
.rule-copy { white-space: normal; min-width: 130px; max-width: 240px; overflow-wrap: anywhere; }

@media (max-width: 560px) {
  .rules-panel > .panel-heading { flex-direction: column; }
}
</style>
