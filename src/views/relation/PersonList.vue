<template>
  <div class="page-wrap person-page">
    <!-- 顶部全局人情收支卡片 -->
    <el-row :gutter="12" class="summary-cards">
      <el-col :span="6">
        <el-card shadow="never">
          <div class="card-label">全部收礼总额（对方赠我，含礼品折算）</div>
          <div class="card-money in-color">¥ {{ global.total_in }}</div>
          <div class="card-sub">{{ global.in_count }} 笔 · 其中礼品 ¥ {{ global.gift_in }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="card-label">全部回礼总额（我赠对方，含礼品折算）</div>
          <div class="card-money out-color">¥ {{ global.total_out }}</div>
          <div class="card-sub">{{ global.out_count }} 笔 · 其中礼品 ¥ {{ global.gift_out }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="card-label">整体人情净差额（收-出）</div>
          <div class="card-money" :class="global.net_diff >= 0 ? 'in-color' : 'out-color'">
            ¥ {{ global.net_diff }}
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="card-label">待回礼人员</div>
          <div class="card-money pending-color">{{ global.pending_persons.length }} 人</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 工具栏 -->
    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="openQuickAdd">快速新增回礼</el-button>
      <el-input
        v-model="keyword"
        placeholder="输入姓名搜索"
        clearable
        :prefix-icon="Search"
        style="width: 220px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-select v-model="statusFilter" style="width: 140px" @change="load">
        <el-option label="全部状态" value="" />
        <el-option label="待回礼" value="pending" />
        <el-option label="已回礼" value="returned" />
      </el-select>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <div class="flex-spacer" />
      <el-button @click="exportCompare">导出人情对照表</el-button>
    </div>

    <!-- 人员汇总表 -->
    <el-table :data="list" v-loading="loading" border stripe height="calc(100% - 226px)">
      <el-table-column label="来宾姓名" min-width="120">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">{{ row.name }}</el-button>
        </template>
      </el-table-column>
      <el-table-column label="回礼标记" width="100" align="center">
        <template #default="{ row }">
          <el-tag
            :type="row.return_status === 'returned' ? 'success' : 'warning'"
            class="status-tag"
            @click="quickToggle(row)"
          >
            {{ row.return_status === 'returned' ? '已回礼' : '待回礼' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="对方赠我合计" width="140" align="right">
        <template #default="{ row }">
          <span class="in-color">¥ {{ row.total_in }}</span>
        </template>
      </el-table-column>
      <el-table-column label="我赠对方合计" width="140" align="right">
        <template #default="{ row }">
          <span class="out-color">¥ {{ row.total_out }}</span>
        </template>
      </el-table-column>
      <el-table-column label="其中礼品(收/回)" width="130" align="right">
        <template #default="{ row }">¥ {{ row.gift_in }} / ¥ {{ row.gift_out }}</template>
      </el-table-column>
      <el-table-column label="人情差额(收-出)" width="140" align="right">
        <template #default="{ row }">
          <span :class="row.diff >= 0 ? 'in-color' : 'out-color'">¥ {{ row.diff }}</span>
        </template>
      </el-table-column>
      <el-table-column label="收/回笔数" width="100" align="center">
        <template #default="{ row }">{{ row.in_count }} / {{ row.out_count }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" align="center">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">往来详情</el-button>
          <el-button link type="primary" @click="addFor(row)">回礼</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 人员详情抽屉 -->
    <PersonDetailDrawer
      v-model="detailVisible"
      :person-id="currentPersonId"
      @changed="load"
    />

    <!-- 快速新增回礼对话框 -->
    <ReturnGiftDialog
      v-model="quickAddVisible"
      :lock-name="quickAddName"
      @saved="onQuickSaved"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Plus, Search, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { GlobalStat, PersonSummary } from '@/types'
import { listPersonSummaries, setPersonStatus } from '@/api/person'
import { getGlobalStat } from '@/api/stats'
import { exportArrayExcel } from '@/utils/excel'
import PersonDetailDrawer from './components/PersonDetailDrawer.vue'
import ReturnGiftDialog from './components/ReturnGiftDialog.vue'

const list = ref<PersonSummary[]>([])
const global = ref<GlobalStat>({
  total_in: 0, total_out: 0, gift_in: 0, gift_out: 0, net_diff: 0,
  in_count: 0, out_count: 0, pending_persons: []
})
const loading = ref(false)
const keyword = ref('')
const statusFilter = ref('')

/// 加载人员列表与全局统计
async function load() {
  loading.value = true
  try {
    ;[list.value, global.value] = await Promise.all([
      listPersonSummaries(keyword.value || undefined, statusFilter.value || undefined),
      getGlobalStat()
    ])
  } finally {
    loading.value = false
  }
}

// ---- 详情抽屉 ----
const detailVisible = ref(false)
const currentPersonId = ref('')
function openDetail(row: PersonSummary) {
  currentPersonId.value = row.id
  detailVisible.value = true
}

// ---- 标记快速切换 ----
async function quickToggle(row: PersonSummary) {
  const next = row.return_status === 'pending' ? 'returned' : 'pending'
  await setPersonStatus(row.id, next)
  ElMessage.success(next === 'returned' ? '已标记为已回礼' : '已标记为待回礼')
  load()
}

// ---- 回礼快速新增 ----
const quickAddVisible = ref(false)
const quickAddName = ref('')
function openQuickAdd() {
  quickAddName.value = ''
  quickAddVisible.value = true
}
function addFor(row: PersonSummary) {
  quickAddName.value = row.name
  quickAddVisible.value = true
}
async function onQuickSaved() {
  await load()
}

// ---- 导出人情对照表 ----
function exportCompare() {
  const rows = list.value.map((r) => ({
    姓名: r.name,
    回礼状态: r.return_status === 'returned' ? '已回礼' : '待回礼',
    对方赠我合计: r.total_in,
    其中礼品折算_收: r.gift_in,
    我赠对方合计: r.total_out,
    其中礼品折算_回: r.gift_out,
    人情差额: r.diff,
    收礼笔数: r.in_count,
    回礼笔数: r.out_count
  }))
  exportArrayExcel('人情往来对照表.xlsx', '人情对照', rows)
}

onMounted(load)
</script>

<style scoped>
.person-page {
  height: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.summary-cards {
  margin-bottom: 12px;
}
.card-label {
  font-size: 13px;
  color: #909399;
}
.card-money {
  font-size: 24px;
  font-weight: 600;
  margin: 6px 0 2px;
}
.card-sub {
  font-size: 12px;
  color: #909399;
}
.in-color {
  color: #e64340;
}
.out-color {
  color: #2b7d2b;
}
.pending-color {
  color: #e6a23c;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.flex-spacer {
  flex: 1;
}
.status-tag {
  cursor: pointer;
}
</style>
