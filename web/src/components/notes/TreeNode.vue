<script setup lang="ts">
import { computed } from 'vue'
import { ChevronDown, ChevronRight, Folder, NotebookPen, Plus, PencilLine, Trash2 } from 'lucide-vue-next'
import type { TreeNodeData } from '../../types/notes'

const props = defineProps<{
  node: TreeNodeData
  depth: number
  expanded: Set<string>
  selectedNoteId: number | null
  renamingId: string | null
  renameValue: string
  targetGroupId: string | null
  maxLevels: number
}>()

const emit = defineEmits<{
  toggle: [id: string]
  open: [noteId: number]
  selectGroup: [id: string]
  create: [type: 'group' | 'note', groupId: string]
  startRename: [id: string]
  confirmRename: []
  renameInput: [value: string]
  deleteGroup: [id: string]
}>()

const isGroup = computed(() => props.node.type === 'group')
const isExpanded = computed(() => isGroup.value && props.expanded.has(props.node.id))
const canNest = computed(() => props.depth < props.maxLevels)
const isTarget = computed(() => props.targetGroupId === props.node.id)
const isSelectedNote = computed(() => props.node.type === 'note' && props.selectedNoteId === props.node.noteId)

function onRowClick() {
  if (props.node.type === 'group') {
    emit('selectGroup', props.node.id)
    emit('toggle', props.node.id)
  } else if (props.node.noteId) {
    emit('open', props.node.noteId)
  }
}
</script>

<template>
  <div>
    <div
      class="group flex items-center gap-1 rounded-xl py-1.5 pr-1.5"
      :class="[
        isTarget && node.type === 'group' ? 'bg-primary-soft/70' : '',
        isSelectedNote ? 'bg-line/60' : 'hover:bg-line/50',
      ]"
      :style="{ paddingLeft: (depth - 1) * 14 + 8 + 'px' }"
      @click="onRowClick"
    >
      <span v-if="node.type === 'group'" class="shrink-0 text-ink-2">
        <ChevronDown v-if="isExpanded" :size="14" />
        <ChevronRight v-else :size="14" />
      </span>
      <span v-else class="w-[14px] shrink-0"></span>
      <Folder v-if="node.type === 'group'" :size="15" class="shrink-0 text-ink-2" />
      <NotebookPen v-else :size="15" class="shrink-0 text-ink-2" />

      <input
        v-if="renamingId === node.id"
        :value="renameValue"
        class="min-w-0 flex-1 rounded-md border border-primary px-1.5 text-[13px] outline-none"
        @click.stop
        @input="emit('renameInput', ($event.target as HTMLInputElement).value)"
        @keydown.enter="emit('confirmRename')"
        @keydown.esc="emit('confirmRename')"
      />
      <span v-else class="min-w-0 flex-1 truncate text-ink">{{ node.name }}</span>

      <span class="hidden shrink-0 items-center gap-0.5 group-hover:flex">
        <button
          v-if="isGroup && canNest"
          class="p-1 text-ink-2 hover:text-primary"
          title="新建子分组"
          @click.stop="emit('create', 'group', node.id)"
        >
          <Plus :size="13" />
        </button>
        <button class="p-1 text-ink-2 hover:text-ink" title="重命名" @click.stop="emit('startRename', node.id)">
          <PencilLine :size="13" />
        </button>
        <button
          v-if="isGroup"
          class="p-1 text-ink-2 hover:text-red-500"
          title="删除空分组"
          @click.stop="emit('deleteGroup', node.id)"
        >
          <Trash2 :size="13" />
        </button>
      </span>
    </div>

    <div v-if="isGroup && isExpanded && node.children">
      <TreeNode
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :depth="depth + 1"
        :expanded="expanded"
        :selected-note-id="selectedNoteId"
        :renaming-id="renamingId"
        :rename-value="renameValue"
        :target-group-id="targetGroupId"
        :max-levels="maxLevels"
        @toggle="emit('toggle', $event)"
        @open="emit('open', $event)"
        @select-group="emit('selectGroup', $event)"
        @create="(type, groupId) => emit('create', type, groupId)"
        @start-rename="emit('startRename', $event)"
        @confirm-rename="emit('confirmRename')"
        @rename-input="emit('renameInput', $event)"
        @delete-group="emit('deleteGroup', $event)"
      />
    </div>
  </div>
</template>
