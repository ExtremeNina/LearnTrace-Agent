<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Check, ChevronDown, Cpu, Pencil } from 'lucide-vue-next'
import * as modelApi from '../../api/model'
import type { AiModelConfigInfo } from '../../types/api'
import ModelManageModal from '../ModelManageModal.vue'
import { useAgentStore } from '../../stores/agent'

/**
 * 当前模型指示器（公共组件，B26 反馈抽取）：
 * 显示当前对话模型（Cpu + 名称），点击下拉切换 / 进入管理模型弹窗。
 * tone = ink（对话页语义令牌）/ blue（首页 AI 面板设计稿蓝系）。
 */
const props = withDefaults(defineProps<{ tone?: 'ink' | 'blue' }>(), {
  tone: 'ink',
})

const agent = useAgentStore()
const models = ref<AiModelConfigInfo[]>([])
const chatModelId = ref<number | null>(null)
const showModelMenu = ref(false)
const showModelManage = ref(false)

const currentModelName = computed(() => {
  if (chatModelId.value == null) {
    return '系统默认'
  }
  return models.value.find((m) => m.id === chatModelId.value)?.name ?? '系统默认'
})

const triggerClass = computed(() =>
  props.tone === 'blue'
    ? 'border-blue-200 text-gray-500 hover:border-blue-300 hover:text-blue-500'
    : 'border-line text-ink-2 hover:border-ink-2/50 hover:text-ink',
)

async function loadModels() {
  try {
    models.value = await modelApi.listModels()
    chatModelId.value = (await modelApi.getModulePrefs()).chat ?? null
  } catch {
    // 模型清单加载失败静默（下拉可重试）
  }
}

async function pickModel(id: number | null) {
  try {
    await modelApi.setModulePref('chat', id)
    chatModelId.value = id
    showModelMenu.value = false
  } catch (e) {
    agent.error = e instanceof Error ? e.message : '切换模型失败'
  }
}

function openModelManage() {
  showModelMenu.value = false
  showModelManage.value = true
}

onMounted(loadModels)
</script>

<template>
  <div class="relative">
    <button
      class="flex items-center gap-1 rounded-full border px-2.5 py-1.5 text-[13px] transition-colors"
      :class="triggerClass"
      title="切换模型"
      @click="showModelMenu = !showModelMenu"
    >
      <Cpu :size="13" />
      {{ currentModelName }}
      <ChevronDown :size="13" />
    </button>
    <div
      v-if="showModelMenu"
      class="absolute bottom-[calc(100%+8px)] left-0 z-50 w-60 rounded-2xl border border-line bg-surface p-2 shadow-lg"
    >
      <button
        class="flex w-full items-center justify-between rounded-xl px-3 py-2 text-[14px] text-ink hover:bg-panel"
        @click="pickModel(null)"
      >
        系统默认
        <Check v-if="chatModelId === null" :size="14" class="text-primary" />
      </button>
      <button
        v-for="m in models"
        :key="m.id"
        class="flex w-full items-center justify-between rounded-xl px-3 py-2 text-[14px] text-ink hover:bg-panel"
        @click="pickModel(m.id)"
      >
        {{ m.name }}
        <Check v-if="chatModelId === m.id" :size="14" class="text-primary" />
      </button>
      <div class="my-1.5 h-px bg-line"></div>
      <button
        class="flex w-full items-center gap-2 rounded-xl px-3 py-2 text-[14px] text-ink hover:bg-panel"
        @click="openModelManage"
      >
        <Pencil :size="14" class="text-ink-2" />
        管理模型
      </button>
    </div>
    <div v-if="showModelMenu" class="fixed inset-0 z-40" @click="showModelMenu = false"></div>

    <ModelManageModal v-model:open="showModelManage" @changed="loadModels" />
  </div>
</template>
