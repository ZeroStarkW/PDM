import { computed, ref } from 'vue'

export type DialogFieldOption = { value: string; label: string }
export type DialogField = {
  name: string
  label: string
  value?: string
  type?: 'text' | 'number' | 'textarea' | 'select'
  /** 下拉选项；传函数可在搜索后动态刷新（如轴承型号模糊检索）。 */
  options?: DialogFieldOption[] | (() => DialogFieldOption[])
  /** 下拉项搜索框：输入即触发 onSearch（配合函数形式的 options 响应式刷新）。 */
  search?: { placeholder?: string; onSearch: (keyword: string) => void }
  placeholder?: string
  required?: boolean
  min?: number
  maxLength?: number
  step?: number | 'any'
}

type DialogState =
  | { kind: 'form'; title: string; description?: string; confirmLabel: string; fields: DialogField[] }
  | { kind: 'confirm'; title: string; description: string; confirmLabel: string; danger?: boolean }

const dialog = ref<DialogState | null>(null)
const fieldValues = ref<Record<string, string>>({})
let resolveDialog: ((value: Record<string, string> | boolean | null) => void) | undefined

function finish(value: Record<string, string> | boolean | null) {
  dialog.value = null
  resolveDialog?.(value)
  resolveDialog = undefined
}

export function useUiDialog() {
  const isOpen = computed(() => dialog.value !== null)

  function form(options: { title: string; description?: string; confirmLabel?: string; fields: DialogField[] }) {
    dialog.value = { kind: 'form', title: options.title, description: options.description, confirmLabel: options.confirmLabel ?? '保存', fields: options.fields }
    fieldValues.value = Object.fromEntries(options.fields.map((field) => [field.name, field.value ?? '']))
    return new Promise<Record<string, string> | null>((resolve) => { resolveDialog = (value) => resolve(value && typeof value === 'object' ? value : null) })
  }

  function confirm(options: { title: string; description: string; confirmLabel?: string; danger?: boolean }) {
    dialog.value = { kind: 'confirm', title: options.title, description: options.description, confirmLabel: options.confirmLabel ?? '确认', danger: options.danger }
    return new Promise<boolean>((resolve) => { resolveDialog = (value) => resolve(value === true) })
  }

  function submit() {
    if (dialog.value?.kind === 'form') finish({ ...fieldValues.value })
    else finish(true)
  }

  return { dialog, fieldValues, isOpen, form, confirm, submit, cancel: () => finish(null) }
}

export type Theme = 'light' | 'dark'
const theme = ref<Theme>(typeof localStorage !== 'undefined' && localStorage.getItem('pdm-theme') === 'dark' ? 'dark' : 'light')

function applyTheme(value: Theme) {
  theme.value = value
  if (typeof document !== 'undefined') document.documentElement.dataset.theme = value
  if (typeof document !== 'undefined') document.querySelector('meta[name="theme-color"]')?.setAttribute('content', value === 'light' ? '#f4f7fa' : '#0b1525')
  if (typeof localStorage !== 'undefined') localStorage.setItem('pdm-theme', value)
}

if (typeof document !== 'undefined') document.documentElement.dataset.theme = theme.value

export function useTheme() {
  return {
    theme,
    toggleTheme: () => applyTheme(theme.value === 'light' ? 'dark' : 'light'),
  }
}
