<template>
  <!-- 右侧录入表格：内容可直接编辑，支持状态标记、插入/删除行 -->
  <el-table
    ref="tableRef"
    :data="records"
    border
    height="100%"
    size="small"
    :row-class-name="rowClass"
    @current-change="onCurrent"
  >
    <el-table-column label="#" width="44" align="center">
      <template #default="{ $index }">{{ $index + 1 }}</template>
    </el-table-column>

    <el-table-column label="来宾姓名" min-width="100">
      <template #default="{ row }">
        <el-input v-model="row.guest_name" size="small" placeholder="姓名" />
      </template>
    </el-table-column>

    <el-table-column label="礼金(元)" min-width="90">
      <template #default="{ row }">
        <el-input-number
          v-model="row.amount"
          :min="0"
          :controls="false"
          size="small"
          class="amount-input"
        />
      </template>
    </el-table-column>
    <el-table-column label="礼品金额(元)" min-width="110">
      <template #default="{ row }">
        <el-input-number
          v-model="row.gift_amount"
          :min="0"
          :controls="false"
          size="small"
          class="amount-input"
        />
      </template>
    </el-table-column>
    <el-table-column label="礼品" min-width="80">
      <template #default="{ row }">
        <el-input v-model="row.gift" size="small" placeholder="礼品" />
      </template>
    </el-table-column>

    <el-table-column label="备注" min-width="110">
      <template #default="{ row }">
        <el-input v-model="row.remark" size="small" placeholder="备注" />
      </template>
    </el-table-column>

    <el-table-column label="状态" width="78" align="center">
      <template #default="{ row, $index }">
        <el-tag
          :type="row.status === 'confirmed' ? 'success' : 'warning'"
          class="status-tag"
          @click.stop="emit('toggle-status', $index)"
        >
          {{ row.status === 'confirmed' ? '已确认' : '待核对' }}
        </el-tag>
      </template>
    </el-table-column>

    <el-table-column label="操作" width="110" align="center">
      <template #default="{ $index }">
        <div class="op-btns">
          <el-button link type="primary" @click.stop="emit('insert', $index)">插入</el-button>
          <el-button link type="danger" @click.stop="emit('remove', $index)">删除</el-button>
        </div>
      </template>
    </el-table-column>
  </el-table>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue'
import type { TableInstance } from 'element-plus'
import type { GiftRecord } from '@/types'

const props = defineProps<{
  records: GiftRecord[]
  currentRow: number
}>()
const emit = defineEmits<{
  select: [number]
  'toggle-status': [number]
  insert: [number]
  remove: [number]
}>()

const tableRef = ref<TableInstance>()

/// 当前行高亮
function rowClass({ rowIndex }: { row: GiftRecord; rowIndex: number }) {
  return rowIndex === props.currentRow ? 'current-proof-row' : ''
}

/// 当前行变化（点击行）
function onCurrent(row: GiftRecord | null) {
  if (!row) return
  const idx = props.records.indexOf(row)
  if (idx >= 0) emit('select', idx)
}

/// 滚动到指定行（溯源/对照定位时调用）
function scrollToRow(index: number) {
  nextTick(() => {
    tableRef.value?.setScrollTop(index * 38)
  })
}
defineExpose({ scrollToRow })
</script>

<style scoped>
.amount-input {
  width: 100%;
}
.status-tag {
  cursor: pointer;
}
/* 操作列按钮保持一行显示，不换行 */
.op-btns {
  display: flex;
  flex-wrap: nowrap;
  justify-content: center;
  white-space: nowrap;
}
.op-btns .el-button + .el-button {
  margin-left: 6px;
}
:deep(.current-proof-row) td {
  background-color: #fdf6ec !important;
}
</style>
