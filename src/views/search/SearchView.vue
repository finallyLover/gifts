<template>
  <div class="page-wrap search-page">
    <el-tabs v-model="activeTab">
      <!-- ================= 全局人名检索 ================= -->
      <el-tab-pane label="全局人名检索" name="global">
        <div class="search-box">
          <el-input
            v-model="name"
            placeholder="输入姓名，跨所有礼簿查询收礼+回礼全部记录"
            size="large"
            clearable
            :prefix-icon="Search"
            style="max-width: 560px"
            @keyup.enter="doGlobalSearch"
          />
          <el-button type="primary" size="large" @click="doGlobalSearch">检索</el-button>
          <el-button size="large" :disabled="!hasResult" @click="exportGlobal">导出结果</el-button>
        </div>

        <template v-if="result">
          <!-- 匹配人员 -->
          <div v-if="result.persons.length" class="match-persons">
            <span class="section-label">匹配人员：</span>
            <el-tag
              v-for="p in result.persons"
              :key="p.id"
              class="person-tag"
              @click="name = p.name; doGlobalSearch()"
            >{{ p.name }}</el-tag>
          </div>

          <!-- 收礼记录 -->
          <div class="result-section">
            <div class="section-title">
              收礼记录（对方随我的礼） · {{ result.incoming.length }} 笔
            </div>
            <el-table :data="result.incoming" border size="small">
              <el-table-column prop="guest_name" label="姓名" width="110" />
              <el-table-column prop="archive_name" label="所属礼簿" min-width="150" show-overflow-tooltip />
              <el-table-column label="日期" width="110">
                <template #default="{ row }">{{ fmt(row.created_at) }}</template>
              </el-table-column>
              <el-table-column label="礼金" width="90" align="right">
                <template #default="{ row }">¥{{ row.amount }}</template>
              </el-table-column>
              <el-table-column prop="gift" label="礼品" min-width="90" />
              <el-table-column label="操作" width="170" align="center">
                <template #default="{ row }">
                  <el-button link type="primary" :disabled="!row.page_id"
                    @click="goOriginal(row)">原图校对</el-button>
                  <el-button link type="primary" :disabled="!row.page_id"
                    @click="viewOriginal(row)">查看原图</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <!-- 回礼记录 -->
          <div class="result-section">
            <div class="section-title">
              回礼记录（我随对方的礼） · {{ result.outgoing.length }} 笔
            </div>
            <el-table :data="result.outgoing" border size="small">
              <el-table-column prop="person_name" label="姓名" width="110" />
              <el-table-column prop="event_name" label="对方宴席" min-width="150" show-overflow-tooltip />
              <el-table-column prop="event_date" label="日期" width="110" />
              <el-table-column label="随礼金额" width="100" align="right">
                <template #default="{ row }">¥{{ row.amount }}</template>
              </el-table-column>
              <el-table-column prop="gift" label="礼品" min-width="90" />
              <el-table-column prop="remark" label="备注" min-width="120" />
            </el-table>
          </div>
        </template>
        <el-empty v-else description="输入姓名开始检索" />
      </el-tab-pane>

      <!-- ================= 条件筛选（单礼簿内） ================= -->
      <el-tab-pane label="条件筛选" name="filter" class="flex-pane">
        <el-form :inline="true" class="filter-form">
          <el-form-item label="礼簿档案">
            <el-select v-model="filter.archive_id" placeholder="全部档案" clearable style="width: 200px">
              <el-option v-for="a in archives" :key="a.id" :label="a.name" :value="a.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="金额区间">
            <el-input-number v-model="filter.min_amount" :min="0" placeholder="最低" controls-position="right" style="width: 110px" />
            <span style="margin: 0 6px">-</span>
            <el-input-number v-model="filter.max_amount" :min="0" placeholder="最高" controls-position="right" style="width: 110px" />
          </el-form-item>
          <el-form-item label="有无礼品">
            <el-select v-model="filter.has_gift" placeholder="不限" clearable style="width: 100px">
              <el-option label="有礼品" :value="true" />
              <el-option label="无礼品" :value="false" />
            </el-select>
          </el-form-item>
          <el-form-item label="核对状态">
            <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 120px">
              <el-option label="待核对" value="pending" />
              <el-option label="已确认" value="confirmed" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="doFilter">查询</el-button>
            <el-button :disabled="!filterRows.length" @click="exportFilter">导出子集</el-button>
          </el-form-item>
        </el-form>

        <!-- 表格撑满页面剩余高度，行数多时表内滚动 -->
        <div class="fill-table">
          <el-table :data="filterRows" border size="small" height="100%">
            <el-table-column prop="guest_name" label="姓名" width="110" />
            <el-table-column prop="archive_name" label="所属礼簿" min-width="150" show-overflow-tooltip />
            <el-table-column label="日期" width="110">
              <template #default="{ row }">{{ fmt(row.created_at) }}</template>
            </el-table-column>
            <el-table-column label="礼金" width="90" align="right">
              <template #default="{ row }">¥{{ row.amount }}</template>
            </el-table-column>
            <el-table-column prop="gift" label="礼品" min-width="90" />
            <el-table-column label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="row.status === 'confirmed' ? 'success' : 'warning'">
                  {{ row.status === 'confirmed' ? '已确认' : '待核对' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" align="center">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="!row.page_id"
                  @click="goOriginal(row)">原图校对</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 只读查看原图弹窗（不可编辑，仅缩放/平移查看） -->
    <el-dialog
      v-model="viewerVisible"
      :title="viewerTitle"
      width="80%"
      top="3vh"
      destroy-on-close
    >
      <div class="viewer-box">
        <ImageCanvas v-if="viewerSrc" :src="viewerSrc" readonly />
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { convertFileSrc } from '@tauri-apps/api/core'
import type {
  Archive, GiftRecord, RecordFilter, SearchResult
} from '@/types'
import { searchAll } from '@/api/search'
import { listRecords } from '@/api/record'
import { listArchives } from '@/api/archive'
import { listPages } from '@/api/page'
import { exportArrayExcel } from '@/utils/excel'
import ImageCanvas from '@/views/entry/components/ImageCanvas.vue'

const router = useRouter()
const activeTab = ref('global')

// ---------- 全局检索 ----------
const name = ref('')
const result = ref<SearchResult | null>(null)
const hasResult = ref(false)

async function doGlobalSearch() {
  if (!name.value.trim()) return
  result.value = await searchAll(name.value.trim())
  hasResult.value = true
}

// ---------- 条件筛选 ----------
const archives = ref<Archive[]>([])
const filter = reactive<RecordFilter>({
  archive_id: undefined,
  min_amount: undefined,
  max_amount: undefined,
  has_gift: undefined,
  relation: undefined,
  status: undefined
})
const filterRows = ref<GiftRecord[]>([])

async function doFilter() {
  filterRows.value = await listRecords({
    archive_id: filter.archive_id,
    min_amount: filter.min_amount,
    max_amount: filter.max_amount,
    has_gift: filter.has_gift,
    status: filter.status
  })
}

/// 跳转原图校对页
function goOriginal(row: GiftRecord) {
  router.push({
    name: 'entry',
    params: { archiveId: row.archive_id, pageId: row.page_id ?? '' },
    query: { recordId: row.id }
  })
}

// ---------- 只读查看原图 ----------
const viewerVisible = ref(false)
const viewerSrc = ref('')
const viewerTitle = ref('')

/// 查看该记录所在页的原图（只读弹窗，不可编辑）
async function viewOriginal(row: GiftRecord) {
  if (!row.page_id) return
  const pages = await listPages(row.archive_id)
  const page = pages.find((p) => p.id === row.page_id)
  if (!page?.abs_path) {
    ElMessage.warning('原图不存在或已被删除')
    return
  }
  viewerSrc.value = convertFileSrc(page.abs_path)
  viewerTitle.value = `${row.archive_name ?? '礼簿'} · ${row.guest_name}（只读查看）`
  viewerVisible.value = true
}

/// 日期截取
function fmt(s: string) {
  return s ? s.slice(0, 10) : ''
}

/// 导出全局检索结果（收礼+回礼合并）
function exportGlobal() {
  if (!result.value) return
  const rows = [
    ...result.value.incoming.map((r) => ({
      类型: '收礼',
      姓名: r.guest_name,
      宴席_礼簿: r.archive_name ?? '',
      日期: fmt(r.created_at),
      金额: r.amount,
      礼品: r.gift ?? '',
      备注: r.remark ?? ''
    })),
    ...result.value.outgoing.map((r) => ({
      类型: '回礼',
      姓名: r.person_name,
      宴席_礼簿: r.event_name ?? '',
      日期: r.event_date ?? '',
      金额: r.amount,
      礼品: r.gift ?? '',
      备注: r.remark ?? ''
    }))
  ]
  exportArrayExcel(`人情检索-${name.value}.xlsx`, '人情记录', rows)
}

/// 导出筛选子集
function exportFilter() {
  const rows = filterRows.value.map((r) => ({
    姓名: r.guest_name,
    所属礼簿: r.archive_name ?? '',
    日期: fmt(r.created_at),
    礼金金额: r.amount,
    礼品: r.gift ?? '',
    备注: r.remark ?? '',
    核对状态: r.status === 'confirmed' ? '已确认' : '待核对'
  }))
  exportArrayExcel('筛选礼簿明细.xlsx', '明细', rows)
}

onMounted(async () => {
  archives.value = await listArchives(undefined, true)
})
</script>

<style scoped>
.search-page {
  height: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
/* el-tabs 纵向撑满整个页面高度 */
.search-page :deep(.el-tabs) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.search-page :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
/* 普通面板：内容超高时面板自身滚动 */
.search-page :deep(.el-tab-pane) {
  height: 100%;
  overflow-y: auto;
}
/* 需要表格撑满的面板：纵向弹性布局 */
.search-page :deep(.flex-pane) {
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
.search-box {
  display: flex;
  gap: 10px;
  margin: 8px 0 18px;
}
.match-persons {
  margin-bottom: 14px;
}
.person-tag {
  cursor: pointer;
  margin-right: 8px;
}
.section-label {
  color: #606266;
  font-weight: 600;
  margin-right: 8px;
}
.result-section {
  margin-bottom: 18px;
}
.section-title {
  font-weight: 600;
  margin-bottom: 8px;
  color: #303133;
}
.filter-form {
  margin-top: 8px;
  flex: none;
}
.viewer-box {
  height: 75vh;
}
</style>
