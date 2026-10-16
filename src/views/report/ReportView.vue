<template>
  <div class="page-wrap report-page">
    <el-tabs v-model="activeTab">
      <!-- ============ 单场礼簿统计 ============ -->
      <el-tab-pane label="单场礼簿统计" name="archive">
        <div class="toolbar">
          <el-select
            v-model="archiveId"
            placeholder="选择礼簿档案"
            style="width: 260px"
            @change="loadArchiveStat"
          >
            <el-option v-for="a in archives" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
          <el-button :disabled="!archiveStatData" @click="exportArchiveExcel">导出明细Excel</el-button>
        </div>

        <template v-if="archiveStatData">
          <!-- 指标卡 -->
          <el-row :gutter="12" class="stat-cards">
            <el-col :span="6">
              <el-card shadow="never">
                <div class="card-label">总笔数</div>
                <div class="stat-number">
                  <el-button link type="primary" class="stat-link" @click="openRecords('总笔数明细', {})">
                    {{ archiveStatData.total_count }}
                  </el-button>
                </div>
                <div class="card-sub">点击查看人员明细</div>
              </el-card>
            </el-col>
            <el-col :span="6">
              <el-card shadow="never">
                <div class="card-label">总金额（含礼品折算）</div>
                <div class="stat-number in-color">¥ {{ archiveStatData.total_amount }}</div>
                <div class="card-sub">其中礼品折算 ¥ {{ archiveStatData.gift_amount }}</div>
              </el-card>
            </el-col>
            <el-col :span="6">
              <el-card shadow="never">
                <div class="card-label">已确认 / 待核对</div>
                <div class="stat-number">
                  <el-button link type="success" class="stat-link"
                    @click="openRecords('已确认明细', { status: 'confirmed' })">
                    {{ archiveStatData.confirmed_count }}
                  </el-button>
                  <span class="stat-sep">/</span>
                  <el-button link type="warning" class="stat-link"
                    @click="openRecords('待核对明细', { status: 'pending' })">
                    {{ archiveStatData.pending_count }}
                  </el-button>
                </div>
                <div class="card-sub">点击数字查看人员明细</div>
              </el-card>
            </el-col>
          </el-row>

          <el-row :gutter="12">
            <!-- 金额档位 -->
            <el-col :span="12">
              <div class="table-title">礼金档位分布</div>
              <el-table :data="archiveStatData.amount_brackets" border size="small">
                <el-table-column prop="label" label="金额档位" />
                <el-table-column label="笔数" width="100" align="right">
                  <template #default="{ row }">
                    <el-button link type="primary" :disabled="row.count === 0"
                      @click="openBracket(row)">
                      {{ row.count }}
                    </el-button>
                  </template>
                </el-table-column>
                <el-table-column label="金额(元)" width="140" align="right">
                  <template #default="{ row }">{{ row.amount }}</template>
                </el-table-column>
              </el-table>
            </el-col>
          </el-row>
        </template>
        <el-empty v-else description="请选择礼簿档案" />
      </el-tab-pane>

      <!-- ============ 全局人情统计 ============ -->
      <el-tab-pane label="全局人情统计" name="global">
        <el-row :gutter="12" class="stat-cards">
          <el-col :span="8">
            <el-card shadow="never">
              <div class="card-label">全部收礼总额（含礼品折算）</div>
              <div class="stat-number in-color">¥ {{ globalStatData.total_in }}</div>
              <div class="card-sub">{{ globalStatData.in_count }} 笔 · 其中礼品 ¥ {{ globalStatData.gift_in }}</div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card shadow="never">
              <div class="card-label">全部回礼总额（含礼品折算）</div>
              <div class="stat-number out-color">¥ {{ globalStatData.total_out }}</div>
              <div class="card-sub">{{ globalStatData.out_count }} 笔 · 其中礼品 ¥ {{ globalStatData.gift_out }}</div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card shadow="never">
              <div class="card-label">整体人情净差额（收-出）</div>
              <div class="stat-number"
                :class="globalStatData.net_diff >= 0 ? 'in-color' : 'out-color'">
                ¥ {{ globalStatData.net_diff }}
              </div>
              <div class="card-sub">
                收 {{ globalStatData.in_count }} 笔 − 出 {{ globalStatData.out_count }} 笔
              </div>
            </el-card>
          </el-col>
        </el-row>

        <div class="table-title">待回礼人员清单</div>
        <el-table :data="globalStatData.pending_persons" border size="small">
          <el-table-column prop="name" label="姓名" width="160" />
          <el-table-column label="对方赠我合计" width="180" align="right">
            <template #default="{ row }">¥ {{ row.total_in }}</template>
          </el-table-column>
          <el-table-column prop="last_date" label="最近收礼时间" min-width="180" />
        </el-table>
      </el-tab-pane>

      <!-- ============ 人情对照表 ============ -->
      <el-tab-pane label="人情对照表" name="compare" class="flex-pane">
        <div class="toolbar">
          <el-input
            v-model="compareKeyword"
            placeholder="按姓名过滤"
            clearable
            style="width: 240px"
            @keyup.enter="loadCompare"
          />
          <el-button type="primary" @click="loadCompare">查询</el-button>
          <el-button @click="exportCompareExcel">导出对照表Excel</el-button>
        </div>

        <!-- 表格撑满页面剩余高度，行数多时表内滚动 -->
        <div class="fill-table">
          <el-table :data="compareRows" border size="small" height="100%">
            <el-table-column prop="name" label="姓名" width="130" />
            <el-table-column label="对方赠我合计" width="150" align="right">
              <template #default="{ row }">¥ {{ row.total_in }}</template>
            </el-table-column>
            <el-table-column label="我赠对方合计" width="150" align="right">
              <template #default="{ row }">¥ {{ row.total_out }}</template>
            </el-table-column>
            <el-table-column label="其中礼品(收/回)" width="140" align="right">
              <template #default="{ row }">¥ {{ row.gift_in }} / ¥ {{ row.gift_out }}</template>
            </el-table-column>
            <el-table-column label="差额(收-出)" width="150" align="right">
              <template #default="{ row }">
                <span :class="row.diff >= 0 ? 'in-color' : 'out-color'">¥ {{ row.diff }}</span>
              </template>
            </el-table-column>
            <el-table-column label="收/回笔数" width="120" align="center">
              <template #default="{ row }">
                <!-- 点击笔数打开该人的往来详情 -->
                <el-button link type="primary" @click="openPersonDetail(row)">
                  {{ row.in_count }} / {{ row.out_count }}
                </el-button>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.return_status === 'returned' ? 'success' : 'warning'">
                  {{ row.return_status === 'returned' ? '已回礼' : '待回礼' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 个人往来详情抽屉（点击对照表笔数打开） -->
    <PersonDetailDrawer v-model="detailVisible" :person-id="detailPersonId" />

    <!-- 笔数钻取：当前礼簿的人员明细对话框 -->
    <el-dialog
      v-model="recordsDialogVisible"
      :title="recordsDialogTitle"
      width="820px"
      append-to-body
    >
      <el-table
        v-loading="recordsLoading"
        :data="dialogRecords"
        border
        size="small"
        max-height="440"
      >
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="guest_name" label="姓名" width="110" show-overflow-tooltip />
        <el-table-column label="关系" width="90">
          <template #default="{ row }">{{ row.relation || '—' }}</template>
        </el-table-column>
        <el-table-column label="礼金(元)" width="100" align="right">
          <template #default="{ row }">{{ row.amount }}</template>
        </el-table-column>
        <el-table-column label="礼品（折算）" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.gift">
              {{ row.gift }}<span v-if="row.gift_amount">（¥{{ row.gift_amount }}）</span>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="合计(元)" width="100" align="right">
          <template #default="{ row }">{{ (row.amount || 0) + (row.gift_amount || 0) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'confirmed' ? 'success' : 'warning'" size="small">
              {{ row.status === 'confirmed' ? '已确认' : '待核对' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '' }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <span class="dialog-total">共 {{ dialogRecords.length }} 笔</span>
        <el-button @click="recordsDialogVisible = false">关 闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type {
  Archive, ArchiveStat, GiftRecord, GlobalStat, GroupStat, PersonSummary, RecordFilter
} from '@/types'
import { listArchives } from '@/api/archive'
import {
  getArchiveStat, getComparisonReport, getGlobalStat
} from '@/api/stats'
import { listRecords } from '@/api/record'
import { exportArrayExcel } from '@/utils/excel'
import PersonDetailDrawer from '@/views/relation/components/PersonDetailDrawer.vue'

const activeTab = ref('archive')
const archives = ref<Archive[]>([])

// ---- 单场统计 ----
const archiveId = ref('')
const archiveStatData = ref<ArchiveStat | null>(null)
async function loadArchiveStat() {
  if (!archiveId.value) return
  archiveStatData.value = await getArchiveStat(archiveId.value)
}

// ---- 笔数钻取：人员明细对话框 ----
const recordsDialogVisible = ref(false)
const recordsLoading = ref(false)
const recordsDialogTitle = ref('')
const dialogRecords = ref<GiftRecord[]>([])

/// 金额档位 -> 礼金区间（与后端 archive_stat 的分桶边界严格对应，金额最多两位小数）
const BRACKET_RANGES: Record<string, Pick<RecordFilter, 'min_amount' | 'max_amount'>> = {
  '200以下': { max_amount: 199.99 },
  '200-499': { min_amount: 200, max_amount: 499.99 },
  '500-999': { min_amount: 500, max_amount: 999.99 },
  '1000-1999': { min_amount: 1000, max_amount: 1999.99 },
  '2000以上': { min_amount: 2000 }
}

/// 打开人员明细对话框；filter 在当前礼簿范围内追加筛选条件
async function openRecords(title: string, filter: RecordFilter) {
  if (!archiveId.value) return
  recordsDialogTitle.value = title
  dialogRecords.value = []
  recordsDialogVisible.value = true
  recordsLoading.value = true
  try {
    dialogRecords.value = await listRecords({ archive_id: archiveId.value, ...filter })
  } finally {
    recordsLoading.value = false
  }
}

/// 点击礼金档位的笔数
function openBracket(row: GroupStat) {
  openRecords(`礼金档位「${row.label}」人员明细`, BRACKET_RANGES[row.label] ?? {})
}

// ---- 全局统计 ----
const globalStatData = ref<GlobalStat>({
  total_in: 0, total_out: 0, gift_in: 0, gift_out: 0, net_diff: 0,
  in_count: 0, out_count: 0, pending_persons: []
})

// ---- 对照表 ----
const compareKeyword = ref('')
const compareRows = ref<PersonSummary[]>([])
async function loadCompare() {
  compareRows.value = await getComparisonReport(compareKeyword.value || undefined)
}

// ---- 个人往来详情抽屉 ----
const detailVisible = ref(false)
const detailPersonId = ref('')
function openPersonDetail(row: PersonSummary) {
  detailPersonId.value = row.id
  detailVisible.value = true
}

// ---- 导出 ----
/// 导出当前档案明细
async function exportArchiveExcel() {
  const rows = await listRecords({ archive_id: archiveId.value })
  const data = rows.map((r, i) => ({
    序号: i + 1,
    来宾姓名: r.guest_name,
    礼金金额: r.amount,
    礼品金额: r.gift_amount ?? 0,
    合计: (r.amount || 0) + (r.gift_amount || 0),
    礼品: r.gift ?? '',
    备注: r.remark ?? '',
    核对状态: r.status === 'confirmed' ? '已确认' : '待核对'
  }))
  const name = archives.value.find((a) => a.id === archiveId.value)?.name ?? '礼簿'
  exportArrayExcel(`${name}-明细.xlsx`, '礼簿明细', data)
}

/// 导出对照表
function exportCompareExcel() {
  const rows = compareRows.value.map((r) => ({
    姓名: r.name,
    对方赠我合计: r.total_in,
    其中礼品折算_收: r.gift_in,
    我赠对方合计: r.total_out,
    其中礼品折算_回: r.gift_out,
    差额: r.diff,
    收礼笔数: r.in_count,
    回礼笔数: r.out_count,
    状态: r.return_status === 'returned' ? '已回礼' : '待回礼'
  }))
  exportArrayExcel('人情往来对照表.xlsx', '人情对照', rows)
}

onMounted(async () => {
  archives.value = await listArchives(undefined, true)
  globalStatData.value = await getGlobalStat()
  await loadCompare()
})
</script>

<style scoped>
.report-page {
  height: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
/* el-tabs 纵向撑满整个页面高度 */
.report-page :deep(.el-tabs) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.report-page :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
/* 普通面板：内容超高时面板自身滚动 */
.report-page :deep(.el-tab-pane) {
  height: 100%;
  overflow-y: auto;
}
/* 需要表格撑满的面板：纵向弹性布局 */
.report-page :deep(.flex-pane) {
  height: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
/* 表格填充容器：占满剩余高度 */
.fill-table {
  flex: 1;
  min-height: 0;
}
.toolbar {
  display: flex;
  gap: 10px;
  margin: 6px 0 14px;
  flex: none;
}
.stat-cards {
  margin-bottom: 16px;
}
/* 同一行指标卡等高（内容上下居中） */
.stat-cards :deep(.el-col) {
  display: flex;
}
.stat-cards :deep(.el-card) {
  width: 100%;
}
.stat-cards :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  justify-content: center;
}
/* 卡片内可点击的数字链接 */
.stat-link {
  font-size: 22px;
  font-weight: 600;
  height: auto;
  padding: 0;
}
.stat-sep {
  margin: 0 6px;
  color: #909399;
}
.dialog-total {
  float: left;
  font-size: 13px;
  color: #909399;
  line-height: 28px;
}
.card-label {
  font-size: 13px;
  color: #909399;
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
.table-title {
  font-weight: 600;
  margin: 4px 0 8px;
}
</style>
