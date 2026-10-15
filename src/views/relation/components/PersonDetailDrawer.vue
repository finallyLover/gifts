<template>
  <!-- 人员人情详情抽屉：收支卡片 + 收礼/回礼双列表 -->
  <el-drawer
    :model-value="modelValue"
    :size="680"
    :title="detail?.name ?? '人员详情'"
    @update:model-value="(v: boolean) => !v && emit('update:modelValue', v)"
  >
    <template v-if="detail">
      <!-- 收支卡片 -->
      <el-row :gutter="10" class="card-row">
        <el-col :span="8">
          <el-card shadow="never" class="money-card in-card">
            <div class="card-label">对方赠我合计</div>
            <div class="card-money">¥ {{ detail.total_in }}</div>
            <div class="card-sub">{{ detail.in_count }} 笔 · 含礼品 ¥{{ detail.gift_in }}</div>
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card shadow="never" class="money-card out-card">
            <div class="card-label">我赠对方合计</div>
            <div class="card-money">¥ {{ detail.total_out }}</div>
            <div class="card-sub">{{ detail.out_count }} 笔 · 含礼品 ¥{{ detail.gift_out }}</div>
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card shadow="never" class="money-card diff-card">
            <div class="card-label">人情差额(收-出)</div>
            <div class="card-money" :class="detail.diff >= 0 ? 'pos' : 'neg'">
              ¥ {{ detail.diff }}
            </div>
            <div class="card-sub">
              <el-button link :type="detail.return_status === 'pending' ? 'warning' : 'success'"
                @click="toggleStatus">
                {{ detail.return_status === 'pending' ? '待回礼' : '已回礼' }}
              </el-button>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 往来记录 -->
      <el-tabs v-model="activeTab" class="record-tabs">
        <el-tab-pane :label="`收礼记录(${detail.incoming.length})`" name="in">
          <el-table :data="detail.incoming" size="small" border max-height="420">
            <el-table-column label="日期" width="110">
              <template #default="{ row }">{{ fmt(row.created_at) }}</template>
            </el-table-column>
            <el-table-column prop="archive_name" label="所属礼簿" min-width="130" show-overflow-tooltip />
            <el-table-column label="礼金" width="80" align="right">
              <template #default="{ row }">¥{{ row.amount }}</template>
            </el-table-column>
            <el-table-column label="礼品金额" width="80" align="right">
              <template #default="{ row }">{{ row.gift_amount ? `¥${row.gift_amount}` : '' }}</template>
            </el-table-column>
            <el-table-column prop="gift" label="礼品" min-width="80" />
            <el-table-column label="操作" width="70" align="center">
              <template #default="{ row }">
                <el-button link type="primary"
                  :disabled="!row.page_id"
                  @click="goOriginal(row)">看原图</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`回礼记录(${detail.outgoing.length})`" name="out">
          <el-table :data="detail.outgoing" size="small" border max-height="420">
            <el-table-column prop="event_date" label="日期" width="100" />
            <el-table-column prop="event_name" label="宴席" min-width="140" show-overflow-tooltip />
            <el-table-column label="金额" width="80" align="right">
              <template #default="{ row }">¥{{ row.amount }}</template>
            </el-table-column>
            <el-table-column label="礼品金额" width="80" align="right">
              <template #default="{ row }">{{ row.gift_amount ? `¥${row.gift_amount}` : '' }}</template>
            </el-table-column>
            <el-table-column prop="gift" label="礼品" min-width="80" />
            <el-table-column label="操作" width="110" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="editOut(row)">编辑</el-button>
                <el-button link type="danger" @click="removeOut(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </template>

    <!-- 底部操作 -->
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button type="primary" @click="openAddReturn">新增回礼记录</el-button>
    </template>

    <ReturnGiftDialog
      v-model="giftDialog"
      :lock-name="detail?.name"
      :target="editingGift"
      @saved="onGiftSaved"
    />
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { GiftRecord, PersonDetail, ReturnGift } from '@/types'
import {
  deleteReturnGift, getPersonDetail, setPersonStatus
} from '@/api/person'
import ReturnGiftDialog from './ReturnGiftDialog.vue'

const props = defineProps<{
  modelValue: boolean
  personId: string
}>()
const emit = defineEmits<{
  'update:modelValue': [boolean]
  changed: []
}>()

const router = useRouter()
const detail = ref<PersonDetail>()
const activeTab = ref('in')

/// 加载详情
watch(
  () => [props.modelValue, props.personId],
  async ([open]) => {
    if (open && props.personId) {
      detail.value = await getPersonDetail(props.personId)
      activeTab.value = 'in'
    }
  }
)

/// 日期只取 yyyy-mm-dd
function fmt(s: string) {
  return s ? s.slice(0, 10) : ''
}

/// 切换回礼标记
async function toggleStatus() {
  if (!detail.value) return
  const next = detail.value.return_status === 'pending' ? 'returned' : 'pending'
  await setPersonStatus(detail.value.id, next)
  detail.value.return_status = next
  emit('changed')
}

/// 跳转原图校对页（溯源）
function goOriginal(row: GiftRecord) {
  if (!row.page_id) return
  router.push({
    name: 'entry',
    params: { archiveId: row.archive_id, pageId: row.page_id },
    query: { recordId: row.id }
  })
}

// ---- 回礼新增/编辑 ----
const giftDialog = ref(false)
const editingGift = ref<ReturnGift | null>(null)

function openAddReturn() {
  editingGift.value = null
  giftDialog.value = true
}
function editOut(row: ReturnGift) {
  editingGift.value = row
  giftDialog.value = true
}
async function onGiftSaved() {
  detail.value = await getPersonDetail(props.personId)
  activeTab.value = 'out'
  emit('changed')
}
async function removeOut(row: ReturnGift) {
  await ElMessageBox.confirm('确定删除该回礼记录吗？', '提示', { type: 'warning' })
  await deleteReturnGift(row.id)
  ElMessage.success('已删除')
  detail.value = await getPersonDetail(props.personId)
  emit('changed')
}
</script>

<style scoped>
.card-row {
  margin-bottom: 10px;
}
.money-card {
  text-align: center;
}
.card-label {
  font-size: 12px;
  color: #909399;
}
.card-money {
  font-size: 20px;
  font-weight: 600;
  margin: 4px 0;
}
.card-money.pos {
  color: #e64340;
}
.card-money.neg {
  color: #2b7d2b;
}
.card-sub {
  font-size: 12px;
  color: #909399;
}
.record-tabs :deep(.el-tabs__header) {
  margin-bottom: 8px;
}
</style>
