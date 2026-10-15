<template>
  <div class="entry-root">
    <!-- 顶部：返回 + 档案信息 + 页面标签 + 页面操作 -->
    <div class="entry-header">
      <el-button link @click="back" title="返回档案列表">
        <el-icon size="18"><ArrowLeft /></el-icon>
      </el-button>
      <span class="archive-name">{{ archive?.name ?? '加载中…' }}</span>
      <el-divider direction="vertical" />

      <!-- 录入方式切换：图片录入（对照原图） / 直接录入（无图片） -->
      <el-radio-group v-model="mode" size="small" @change="onModeChange">
        <el-radio-button value="photo">图片录入</el-radio-button>
        <el-radio-button value="direct">直接录入</el-radio-button>
      </el-radio-group>
      <el-divider v-if="mode === 'photo'" direction="vertical" />

      <!-- 页面标签（对应纸质账本多页，仅图片录入模式显示） -->
      <template v-if="mode === 'photo'">
        <el-tabs
          v-if="pages.length"
          :model-value="currentPageId"
          type="card"
          class="page-tabs"
          @update:model-value="switchPage"
        >
          <el-tab-pane
            v-for="(p, i) in pages"
            :key="p.id"
            :name="p.id"
          >
            <template #label>第{{ i + 1 }}页</template>
          </el-tab-pane>
        </el-tabs>
        <span v-else class="no-page-tip">尚未上传照片</span>
      </template>
      <span v-else class="no-page-tip">无需照片，直接逐行录入</span>

      <div class="flex-spacer" />
      <template v-if="mode === 'photo'">
        <el-button :icon="Upload" size="small" @click="uploadImages">上传照片</el-button>
        <el-button size="small" type="danger" plain :disabled="!currentPage" @click="removeCurrentPage">
          删除本页
        </el-button>
      </template>
    </div>

    <!-- 操作条：左侧录入操作，右侧导出打印 -->
    <div class="action-bar">
      <el-button type="primary" :icon="Plus" @click="addRow">新增行</el-button>
      <el-button :icon="CircleCheck" @click="confirmAll">全部标记已确认</el-button>
      <el-button type="success" plain :icon="Check" :loading="saving" @click="saveAll">保存修改</el-button>
      <div class="flex-spacer" />
      <el-button :icon="Download" @click="exportExcel">导出Excel</el-button>
      <el-button :icon="Printer" @click="printPdf">打印PDF</el-button>
    </div>

    <!-- 左右分栏：图片录入=左原图/右表格；直接录入=全宽表格 -->
    <div class="entry-body">
      <div v-if="mode === 'photo'" class="pane pane-left">
        <ImageCanvas
          v-if="currentPage?.abs_path"
          :key="canvasKey"
          :src="imageSrc"
          @rotate="onRotate"
        />
        <el-empty v-else description="请上传纸质礼簿照片，或切换到直接录入" class="empty-tip" />
      </div>
      <div class="pane pane-right" :class="{ 'pane-full': mode === 'direct' }">
        <div class="table-wrap">
          <ProofTable
            ref="proofRef"
            :records="records"
            :current-row="currentRow"
            @select="selectRow"
            @toggle-status="toggleStatus"
            @insert="insertRow"
            @remove="removeRow"
          />
        </div>
        <!-- 当前页合计：礼金 + 礼品数字提取 + 最终合计 -->
        <div class="page-summary">
          <span>共 <b>{{ records.length }}</b> 笔</span>
          <span>礼金合计：<b>¥{{ fmtMoney(pageSummary.gift) }}</b></span>
          <span>礼品金额合计：<b>¥{{ fmtMoney(pageSummary.giftNum) }}</b></span>
          <span class="total">本页最终合计：<b>¥{{ fmtMoney(pageSummary.total) }}</b></span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload, ArrowLeft, Plus, CircleCheck, Check, Download, Printer } from '@element-plus/icons-vue'
import { convertFileSrc } from '@tauri-apps/api/core'

import type { Archive, GiftRecord, PageInfo, RecordInput } from '@/types'
import { listArchives } from '@/api/archive'
import { deletePage, importPages, listPages, rotatePage } from '@/api/page'
import { deleteRecords, listRecords, saveRecord, saveRecords } from '@/api/record'
import { pickImageFiles } from '@/api/backup'
import { exportArrayExcel } from '@/utils/excel'
import { exportLedgerPdf } from '@/utils/pdf'
import { errText } from '@/utils/layout'

import ImageCanvas from './components/ImageCanvas.vue'
import ProofTable from './components/ProofTable.vue'

const route = useRoute()
const router = useRouter()

// 路由参数
const archiveId = route.params.archiveId as string
const initialPageId = route.params.pageId as string | undefined

// ---------- 状态 ----------
const archive = ref<Archive>()
const pages = ref<PageInfo[]>([])
/// 录入方式：photo=对照原图录入，direct=无图片直接录入
const mode = ref<'photo' | 'direct'>('photo')
const currentPageId = ref('')
const records = ref<GiftRecord[]>([])
const currentRow = ref(0)

/// 当前页合计：礼金合计 + 礼品金额合计 + 最终合计
const pageSummary = computed(() => {
  let gift = 0
  let giftNum = 0
  for (const r of records.value) {
    gift += Number(r.amount) || 0
    giftNum += Number(r.gift_amount) || 0
  }
  const round2 = (v: number) => Math.round(v * 100) / 100
  return { gift: round2(gift), giftNum: round2(giftNum), total: round2(gift + giftNum) }
})

/// 金额格式化：千分位，最多两位小数
function fmtMoney(v: number): string {
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })
}

// ============================================================
// 未保存修改检测：逐行快照对比，切换页/切模式/离开页面时提示保存
// ============================================================
/// 行对象 → 最近保存时的快照（WeakMap 跟随行对象，插入/删除行不串位）
const savedMap = new WeakMap<object, string>()

/// 生成单行快照（只含可编辑字段，排除 updated_at 等保存后变化字段）
function rowSnap(r: GiftRecord): string {
  return JSON.stringify([r.guest_name, r.relation, r.amount, r.gift, r.gift_amount, r.remark, r.status])
}
/// 加载/保存成功后，将列表全部行标记为“已保存”
function markSaved(list: GiftRecord[]) {
  list.forEach((r) => savedMap.set(r, rowSnap(r)))
}
/// 是否有未保存的修改（新增行无快照也算未保存）
const dirty = computed(() => records.value.some((r) => savedMap.get(r) !== rowSnap(r)))

/// 有未保存修改时弹窗确认。返回 true=继续操作（已保存或放弃修改），false=取消操作
async function ensureSaved(): Promise<boolean> {
  if (!dirty.value) return true
  const action = await ElMessageBox.confirm(
    '当前有未保存的修改，继续操作将丢失。是否先保存？',
    '未保存提醒',
    {
      type: 'warning',
      confirmButtonText: '保存并继续',
      cancelButtonText: '不保存',
      showClose: true, // 右上角 X = 取消本次操作
      distinguishCancelAndClose: true
    }
  ).then(() => 'confirm' as const).catch((a) => a as 'cancel' | 'close')
  if (action === 'close') return false
  if (action === 'confirm') {
    try {
      await persistAll(false)
    } catch {
      return false // 保存失败则不继续
    }
  }
  return true
}

/// 离开录入页（返回列表/跳转其他菜单）时提示保存
onBeforeRouteLeave(async () => await ensureSaved())

const saving = ref(false)
const rotating = ref(false)

/// 画布重挂载标记（旋转后强制重建组件，按新尺寸适应窗口）
const canvasKey = ref(0)
/// 图片缓存版本（旋转写回同一文件，用查询参数强制 WebView 重新加载）
const imgVersion = ref(0)

const currentPage = computed(() => pages.value.find((p) => p.id === currentPageId.value) ?? null)
const imageSrc = computed(() =>
  currentPage.value?.abs_path
    ? `${convertFileSrc(currentPage.value.abs_path)}?v=${imgVersion.value}`
    : ''
)

/// 旋转当前页面图片：调用后端原地旋转 → 重挂载画布
async function onRotate(angle: number) {
  if (rotating.value) return
  rotating.value = true
  try {
    const updated = await rotatePage(currentPageId.value, angle)
    // 同步 pages 缓存
    const idx = pages.value.findIndex((p) => p.id === updated.id)
    if (idx >= 0) pages.value[idx] = updated
    imgVersion.value += 1
    canvasKey.value += 1
    ElMessage.success(angle > 0 ? '已向右旋转90°' : '已向左旋转90°')
  } catch (e) {
    ElMessage.error('图片旋转失败：' + errText(e))
  } finally {
    rotating.value = false
  }
}

// ============================================================
// 初始化
// ============================================================
onMounted(async () => {
  const all = await listArchives(undefined, true)
  archive.value = all.find((a) => a.id === archiveId)
  if (!archive.value) {
    ElMessage.error('礼簿档案不存在')
    return
  }
  pages.value = await listPages(archiveId)
  if (pages.value.length) {
    mode.value = 'photo'
    const target = initialPageId && pages.value.some((p) => p.id === initialPageId)
      ? initialPageId
      : pages.value[0].id
    await switchPage(target)
    // 溯源：检索页跳转携带 recordId，定位记录并展示对应原图
    const rid = route.query.recordId as string | undefined
    if (rid) locateRecord(rid)
  } else {
    // 无照片时默认进入直接录入模式
    mode.value = 'direct'
    await loadDirectRecords()
  }
})

/// 加载直接录入（无页面关联）的记录
async function loadDirectRecords() {
  currentPageId.value = ''
  records.value = await listRecords({ archive_id: archiveId, no_page: true })
  currentRow.value = 0
  markSaved(records.value)
}

/// 录入方式切换（有未保存修改时先提示；取消则回退选择）
async function onModeChange(newMode: string | number | boolean | undefined) {
  if (!(await ensureSaved())) {
    // 取消：回退到原模式（v-model 已先行变更）
    mode.value = newMode === 'direct' ? 'photo' : 'direct'
    return
  }
  if (mode.value === 'direct') {
    await loadDirectRecords()
  } else if (pages.value.length) {
    currentPageId.value = '' // 避免 switchPage 因 id 相同跳过
    await switchPage(pages.value[0].id)
  } else {
    currentPageId.value = ''
    records.value = []
    markSaved(records.value)
  }
}

/// 根据 recordId 定位记录（跨页时先切页/切模式，原图随之切换展示）
async function locateRecord(recordId: string) {
  let idx = records.value.findIndex((r) => r.id === recordId)
  if (idx < 0) {
    // 在本档案全部记录中查找所属页
    const allRecords = await listRecords({ archive_id: archiveId })
    const target = allRecords.find((r) => r.id === recordId)
    if (target?.page_id) {
      mode.value = 'photo'
      if (target.page_id !== currentPageId.value) {
        await switchPage(target.page_id)
      }
    } else if (target) {
      // 直接录入的记录无页面
      mode.value = 'direct'
      await loadDirectRecords()
    }
    idx = records.value.findIndex((r) => r.id === recordId)
  }
  if (idx >= 0) {
    selectRow(idx)
    ;(proofRef.value as { scrollToRow: (i: number) => void } | undefined)?.scrollToRow(idx)
  }
}

/// 切换页面，加载该页记录（有未保存修改时先提示）
async function switchPage(pageId: string) {
  if (pageId !== currentPageId.value && !(await ensureSaved())) return
  currentPageId.value = pageId
  records.value = await listRecords({
    archive_id: archiveId,
    page_id: pageId
  })
  currentRow.value = 0
  markSaved(records.value)
}

// ============================================================
// 录入操作
// ============================================================
const proofRef = ref()

function selectRow(i: number) {
  currentRow.value = i
}

/// 空记录（cell 坐标字段保留置 0，兼容旧数据结构；无页面时 page_id 为 null）
function emptyRecord(index: number): GiftRecord {
  return {
    id: '',
    archive_id: archiveId,
    page_id: currentPageId.value || null,
    person_id: null,
    guest_name: '',
    relation: null,
    amount: 0,
    gift: null,
    gift_amount: 0,
    remark: null,
    operator: null,
    status: 'pending',
    row_order: index,
    cell_x: 0,
    cell_y: 0,
    cell_w: 0,
    cell_h: 0,
    created_at: '',
    updated_at: ''
  }
}

/// 状态标记切换（单条立即保存，仅更新该行快照）
async function toggleStatus(i: number) {
  const r = records.value[i]
  r.status = r.status === 'confirmed' ? 'pending' : 'confirmed'
  const saved = await saveRecord(toInput(r, i))
  records.value[i] = saved
  savedMap.set(saved, rowSnap(saved))
}

/// 插入行
function insertRow(i: number) {
  records.value.splice(i, 0, emptyRecord(i))
}
/// 新增行（追加到末尾）
function addRow() {
  records.value.push(emptyRecord(records.value.length))
}
/// 删除行
async function removeRow(i: number) {
  const r = records.value[i]
  if (r.id) await deleteRecords([r.id])
  records.value.splice(i, 1)
}
/// 全部确认（保存前先做重名提醒）
async function confirmAll() {
  if (!(await confirmDuplicates())) return
  records.value.forEach((r) => (r.status = 'confirmed'))
  await persistAll(false)
}

// ============================================================
// 重名提醒：同一本礼簿内出现相同姓名时提示用户区分
// ============================================================
/// 保存前检查本礼簿（含当前页与其他页）是否存在重名。
/// 有重名则弹窗提醒，返回 true=继续保存，false=返回修改
async function confirmDuplicates(): Promise<boolean> {
  // 取本礼簿全部已保存记录，排除本页待覆盖的记录（本页稍后按新内容重新计数）
  const all = await listRecords({ archive_id: archiveId })
  const batchIds = new Set(records.value.map((r) => r.id).filter(Boolean) as string[])
  const counts = new Map<string, number>()
  for (const r of all) {
    if (batchIds.has(r.id)) continue
    const n = (r.guest_name || '').trim()
    if (n) counts.set(n, (counts.get(n) ?? 0) + 1)
  }
  for (const r of records.value) {
    const n = (r.guest_name || '').trim()
    if (n) counts.set(n, (counts.get(n) ?? 0) + 1)
  }
  const dups = [...counts.entries()].filter(([, c]) => c > 1)
  if (!dups.length) return true

  const detail = dups.map(([n, c]) => `${n}（${c} 条）`).join('、')
  try {
    await ElMessageBox.confirm(
      `本礼簿已存在同名记录：${detail}。若为不同的人，请加后缀区分（如「张伟(小)」「张伟(村东头)」）；若确为同一人，可继续保存。`,
      '重名提醒',
      { type: 'warning', confirmButtonText: '继续保存', cancelButtonText: '返回修改' }
    )
    return true
  } catch {
    return false
  }
}

// ============================================================
// 保存
// ============================================================
function toInput(r: GiftRecord, index: number): RecordInput {
  return {
    id: r.id || undefined,
    archive_id: archiveId,
    page_id: currentPageId.value || null,
    guest_name: r.guest_name,
    relation: r.relation,
    amount: r.amount,
    gift: r.gift,
    gift_amount: r.gift_amount ?? 0,
    remark: r.remark,
    operator: r.operator,
    status: r.status,
    row_order: index,
    cell_x: r.cell_x,
    cell_y: r.cell_y,
    cell_w: r.cell_w,
    cell_h: r.cell_h
  }
}

/// 持久化全部记录
async function persistAll(silent: boolean) {
  saving.value = true
  try {
    const inputs = records.value.map((r, i) => toInput(r, i))
    records.value = await saveRecords(inputs)
    markSaved(records.value)
    if (!silent) ElMessage.success('保存成功')
  } finally {
    saving.value = false
  }
}
/// 保存按钮（保存前先做重名提醒）
async function saveAll() {
  if (!(await confirmDuplicates())) return
  await persistAll(false)
}

// ============================================================
// 页面管理：上传 / 排序 / 删除
// ============================================================
async function uploadImages() {
  const paths = await pickImageFiles()
  if (!paths.length) return
  pages.value = await importPages(archiveId, paths)
  mode.value = 'photo'
  await switchPage(pages.value[pages.value.length - 1].id)
  ElMessage.success(`已导入 ${paths.length} 张照片，可逐页切换录入`)
}

async function removeCurrentPage() {
  // 本页有未保存修改时先提示
  if (!(await ensureSaved())) return
  await ElMessageBox.confirm('确定删除当前页面照片吗？（记录会保留，原图查看将失效）', '提示', {
    type: 'warning'
  })
  await deletePage(currentPageId.value)
  pages.value = await listPages(archiveId)
  if (pages.value.length) await switchPage(pages.value[0].id)
  else await loadDirectRecords()
}

// ============================================================
// 导出 / 打印
// ============================================================
function exportExcel() {
  const rows = records.value.map((r, i) => ({
    序号: i + 1,
    来宾姓名: r.guest_name,
    礼金金额: r.amount,
    礼品金额: r.gift_amount ?? 0,
    合计: (r.amount || 0) + (r.gift_amount || 0),
    礼品: r.gift ?? '',
    备注: r.remark ?? '',
    核对状态: r.status === 'confirmed' ? '已确认' : '待核对'
  }))
  // 末尾追加合计行
  rows.push({
    序号: '' as unknown as number,
    来宾姓名: '合计',
    礼金金额: pageSummary.value.gift,
    礼品金额: pageSummary.value.giftNum,
    合计: pageSummary.value.total,
    礼品: '',
    备注: '',
    核对状态: ''
  })
  const pageLabel = mode.value === 'direct'
    ? '直接录入'
    : `第${pages.value.findIndex((p) => p.id === currentPageId.value) + 1}页`
  exportArrayExcel(`${archive.value?.name}-${pageLabel}.xlsx`, '礼簿明细', rows)
}

function printPdf() {
  const pageLabel = mode.value === 'direct'
    ? '直接录入'
    : `第${pages.value.findIndex((p) => p.id === currentPageId.value) + 1}页`
  exportLedgerPdf(
    `${archive.value?.name}(${pageLabel})`,
    records.value,
    `宴席日期：${archive.value?.event_date ?? ''}`
  )
}

function back() {
  router.push('/archives')
}
</script>

<style scoped>
.entry-root {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.entry-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}
.archive-name {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}
.page-tabs {
  margin: 0;
}
.page-tabs :deep(.el-tabs__header) {
  margin: 0;
}
.no-page-tip {
  color: #909399;
  font-size: 13px;
}
.flex-spacer {
  flex: 1;
}
.action-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #fafafa;
  border-bottom: 1px solid #e4e7ed;
}
.tip-text {
  color: #909399;
  font-size: 12px;
}
.entry-body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.pane {
  height: 100%;
  min-width: 0;
}
.pane-left {
  flex: 1.25;
}
.pane-right {
  flex: 1;
  border-left: 3px solid #dcdfe6;
  display: flex;
  flex-direction: column;
}
.table-wrap {
  flex: 1;
  min-height: 0;
}
/* 直接录入模式：表格占满全宽 */
.pane-full {
  border-left: none;
}
.empty-tip {
  margin-top: 120px;
}
/* 当前页合计栏 */
.page-summary {
  display: flex;
  align-items: center;
  gap: 28px;
  padding: 8px 16px;
  border-top: 1px solid #e4e7ed;
  background: #faf8f3;
  color: #606266;
  font-size: 14px;
}
.page-summary b {
  color: #303133;
}
.page-summary .total {
  margin-left: auto;
  font-size: 15px;
}
.page-summary .total b {
  color: #b8860b;
  font-size: 17px;
}
</style>
