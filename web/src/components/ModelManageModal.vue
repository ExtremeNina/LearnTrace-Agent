<script setup lang="ts">
import { ref, watch } from 'vue'
import { Check, Eye, EyeOff, LoaderCircle, Pencil, Plus, Trash2, X } from 'lucide-vue-next'
import * as modelApi from '../api/model'
import type { AiModelConfigInfo } from '../types/api'

/**
 * 管理模型弹窗：用户自建 OpenAI 兼容模型配置的增删改查与连接测试
 * （显示名 / Base URL / API 格式 / API Key / 模型名；编辑时 Key 留空 = 保持原值）
 */
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'update:open', value: boolean): void; (e: 'changed'): void }>()

const list = ref<AiModelConfigInfo[]>([])
const loading = ref(false)
const error = ref('')

// 表单（editingId = null 表示新增）
const formOpen = ref(false)
const editingId = ref<number | null>(null)
const form = ref({ name: '', baseUrl: '', apiKey: '', model: '' })
const showKey = ref(false)
const saving = ref(false)
const formError = ref('')
const testing = ref(false)
const testResult = ref('')

watch(
  () => props.open,
  (open) => {
    if (open) {
      closeForm()
      load()
    }
  }
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    list.value = await modelApi.listModels()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function openAdd() {
  editingId.value = null
  form.value = { name: '', baseUrl: '', apiKey: '', model: '' }
  testResult.value = ''
  formError.value = ''
  formOpen.value = true
}

function openEdit(config: AiModelConfigInfo) {
  editingId.value = config.id
  form.value = { name: config.name, baseUrl: config.baseUrl, apiKey: '', model: config.model }
  testResult.value = ''
  formError.value = ''
  formOpen.value = true
}

function closeForm() {
  formOpen.value = false
  showKey.value = false
}

async function runTest() {
  if (!form.value.baseUrl || !form.value.model || (!editingId.value && !form.value.apiKey)) {
    testResult.value = '请先填写 Base URL / API Key / 模型名'
    return
  }
  testing.value = true
  testResult.value = ''
  try {
    if (editingId.value && !form.value.apiKey) {
      await modelApi.testModel(editingId.value)
    } else {
      await modelApi.testModelDraft({ baseUrl: form.value.baseUrl, apiKey: form.value.apiKey, model: form.value.model })
    }
    testResult.value = '连接成功'
  } catch (e) {
    testResult.value = e instanceof Error ? e.message : '连接失败'
  } finally {
    testing.value = false
  }
}

async function save() {
  if (!form.value.name.trim() || !form.value.baseUrl.trim() || !form.value.model.trim()
      || (!editingId.value && !form.value.apiKey.trim())) {
    formError.value = '显示名 / Base URL / API Key / 模型名均不能为空'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    if (editingId.value) {
      await modelApi.updateModel(editingId.value, {
        name: form.value.name,
        baseUrl: form.value.baseUrl,
        // Key 留空 = 保持原值
        apiKey: form.value.apiKey,
        model: form.value.model,
      })
    } else {
      await modelApi.addModel({ ...form.value })
    }
    emit('changed')
    closeForm()
    await load()
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}

async function remove(config: AiModelConfigInfo) {
  if (!window.confirm(`确定删除配置「${config.name}」吗？引用它的模块会自动回退系统默认模型。`)) {
    return
  }
  try {
    await modelApi.deleteModel(config.id)
    emit('changed')
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '删除失败，请稍后重试'
  }
}

function hostOf(baseUrl: string): string {
  try {
    return new URL(baseUrl).host
  } catch {
    return baseUrl
  }
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-[75] flex items-center justify-center bg-ink/25 px-4 backdrop-blur-sm"
    @click.self="emit('update:open', false)"
  >
    <div class="max-h-[85vh] w-full max-w-xl overflow-y-auto rounded-3xl border border-line bg-surface p-6 shadow-xl">
      <div class="flex items-center justify-between">
        <h2 class="text-[16px] font-semibold text-ink">管理模型</h2>
        <button
          class="flex h-8 w-8 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
          title="关闭"
          @click="emit('update:open', false)"
        >
          <X :size="16" />
        </button>
      </div>
      <p class="mt-1 text-[12px] leading-5 text-ink-2">
        添加任意 OpenAI 兼容的模型供应商（DeepSeek / 通义千问 / 智谱 / Kimi 等），配置仅自己可见。
      </p>

      <button
        class="mt-4 flex w-full items-center justify-center gap-1.5 rounded-xl border border-dashed border-line py-2.5 text-[14px] text-ink hover:border-primary hover:text-primary"
        :class="formOpen ? 'opacity-40' : ''"
        :disabled="formOpen"
        @click="openAdd"
      >
        <Plus :size="16" />
        添加模型
      </button>

      <!-- 添加 / 编辑表单 -->
      <div v-if="formOpen" class="mt-3 rounded-2xl border border-primary/40 bg-panel p-4">
        <label class="block">
          <span class="mb-1 block text-[12px] text-ink-2">显示名</span>
          <input
            v-model="form.name"
            type="text"
            class="w-full rounded-xl border border-line px-3 py-2 text-[14px] outline-none focus:border-primary"
            placeholder="例如：智谱 GLM"
          />
        </label>
        <label class="mt-3 block">
          <span class="mb-1 block text-[12px] text-ink-2">Base URL</span>
          <input
            v-model="form.baseUrl"
            type="text"
            class="w-full rounded-xl border border-line px-3 py-2 text-[14px] outline-none focus:border-primary"
            placeholder="https://open.bigmodel.cn/api/paas/v4"
          />
        </label>
        <div class="mt-3 block">
          <span class="mb-1 block text-[12px] text-ink-2">API 格式</span>
          <div class="w-full rounded-xl border border-line bg-panel px-3 py-2 text-[14px] text-ink-2">
            Chat Completions (/chat/completions)
          </div>
        </div>
        <label class="mt-3 block">
          <span class="mb-1 block text-[12px] text-ink-2">
            API Key
            <span v-if="editingId" class="text-ink-2">（留空 = 保持原值）</span>
          </span>
          <span class="relative block">
            <input
              v-model="form.apiKey"
              :type="showKey ? 'text' : 'password'"
              class="w-full rounded-xl border border-line px-3 py-2 pr-9 text-[14px] outline-none focus:border-primary"
              placeholder="输入 API Key"
            />
            <button
              class="absolute right-2 top-1/2 -translate-y-1/2 text-ink-2 hover:text-ink"
              title="显示 / 隐藏"
              @click="showKey = !showKey"
            >
              <Eye v-if="!showKey" :size="15" />
              <EyeOff v-else :size="15" />
            </button>
          </span>
        </label>
        <label class="mt-3 block">
          <span class="mb-1 block text-[12px] text-ink-2">模型名</span>
          <input
            v-model="form.model"
            type="text"
            class="w-full rounded-xl border border-line px-3 py-2 text-[14px] outline-none focus:border-primary"
            placeholder="例如：glm-4-flash"
          />
        </label>

        <div class="mt-3 flex items-center gap-3">
          <button
            class="flex items-center gap-1.5 rounded-xl border border-line px-3 py-2 text-[13px] text-ink hover:bg-surface disabled:opacity-40"
            :disabled="testing"
            @click="runTest"
          >
            <LoaderCircle v-if="testing" :size="13" class="animate-spin" />
            测试连接
          </button>
          <span v-if="testResult === '连接成功'" class="flex items-center gap-1 text-[12px] text-green-600">
            <Check :size="13" />
            连接成功
          </span>
          <span v-else-if="testResult" class="truncate text-[12px] text-red-600">{{ testResult }}</span>
        </div>

        <p v-if="formError" class="mt-2 text-[12px] text-red-600">{{ formError }}</p>

        <div class="mt-3 flex justify-end gap-2">
          <button
            class="rounded-xl border border-line px-3.5 py-2 text-[13px] text-ink hover:bg-surface"
            @click="closeForm"
          >
            取消
          </button>
          <button
            class="rounded-xl bg-primary px-4 py-2 text-[13px] text-white hover:opacity-90 disabled:opacity-40"
            :disabled="saving"
            @click="save"
          >
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>

      <!-- 配置列表 -->
      <p v-if="loading && !formOpen" class="mt-4 text-[13px] text-ink-2">加载中…</p>
      <p v-else-if="error" class="mt-4 text-[13px] text-red-600">{{ error }}</p>
      <div v-else-if="list.length === 0 && !formOpen" class="mt-4 rounded-2xl border border-dashed border-line py-10 text-center">
        <p class="text-[13px] text-ink-2">还没有添加模型配置</p>
        <p class="mt-1 text-[12px] text-ink-2">点击上方「添加模型」，填入供应商的 Base URL 与 API Key</p>
      </div>
      <div v-else class="mt-4 flex flex-col gap-2">
        <div
          v-for="config in list"
          :key="config.id"
          class="flex items-center gap-3 rounded-2xl border border-line px-4 py-3"
        >
          <div class="min-w-0 flex-1">
            <p class="truncate text-[14px] text-ink">{{ config.name }}</p>
            <p class="truncate text-[12px] text-ink-2">
              {{ config.model }} · {{ hostOf(config.baseUrl) }} · {{ config.apiKeyMasked }}
            </p>
          </div>
          <button
            class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
            title="编辑"
            @click="openEdit(config)"
          >
            <Pencil :size="14" />
          </button>
          <button
            class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-ink-2 hover:bg-red-50 hover:text-red-600"
            title="删除"
            @click="remove(config)"
          >
            <Trash2 :size="14" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
