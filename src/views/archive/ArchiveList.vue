<template>
  <div class="page-wrap">
    <!-- 工具栏 -->
    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="openCreate">新建礼簿</el-button>
      <el-input
        v-model="keyword"
        placeholder="搜索档案名称"
        clearable
        :prefix-icon="Search"
        style="width: 240px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-checkbox v-model="includeArchived" @change="load">显示已归档</el-checkbox>
      <div class="flex-spacer" />
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <!-- 档案列表 -->
    <el-table :data="archives" v-loading="loading" border stripe height="calc(100% - 56px)">
      <el-table-column prop="name" label="档案名称" min-width="180" show-overflow-tooltip />
      <el-table-column prop="banquet_type" label="宴席类型" width="100" />
      <el-table-column prop="event_date" label="举办日期" width="120" />
      <el-table-column prop="host_name" label="主人" width="100" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'archived' ? 'info' : 'success'">
            {{ row.status === 'archived' ? '已归档' : '在用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" label="创建时间" width="170" />
      <el-table-column label="操作" width="300" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="primary" @click="goEntry(row)">录入校对</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link :type="row.status === 'archived' ? 'success' : 'warning'"
            @click="toggleArchive(row)">
            {{ row.status === 'archived' ? '取消归档' : '归档' }}
          </el-button>
          <el-button link type="danger" @click="askDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 编辑对话框 -->
    <ArchiveEditDialog v-model="editVisible" :archive="editing" @saved="load" />

    <!-- 删除确认对话框（校验数据量 + 可选择是否保留图片） -->
    <el-dialog v-model="deleteVisible" title="删除礼簿档案" width="440px">
      <p>确定删除档案「{{ deleting?.name }}」吗？此操作不可撤销。</p>
      <!-- 删除前校验：展示该档案内已有数据量 -->
      <el-alert
        v-if="deleteStat"
        :type="deleteStat.records > 0 || deleteStat.pages > 0 ? 'warning' : 'success'"
        :closable="false"
        class="delete-stat"
      >
        <template v-if="deleteStat.records > 0 || deleteStat.pages > 0">
          该档案包含 <b>{{ deleteStat.pages }}</b> 页照片、<b>{{ deleteStat.records }}</b> 条收礼记录，
          礼金合计 <b>¥{{ deleteStat.amount }}</b>，删除后无法找回！
        </template>
        <template v-else>该档案暂无数据，可直接删除。</template>
      </el-alert>
      <el-checkbox v-model="keepImages">保留原始图片文件（仅删除数据库记录）</el-checkbox>
      <template #footer>
        <el-button @click="deleteVisible = false">取消</el-button>
        <el-button type="danger" :loading="deletingFlag" @click="confirmDelete">确认删除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search, Refresh } from '@element-plus/icons-vue'
import type { Archive } from '@/types'
import { deleteArchive, listArchives, setArchiveStatus } from '@/api/archive'
import { listRecords } from '@/api/record'
import { listPages } from '@/api/page'
import ArchiveEditDialog from './ArchiveEditDialog.vue'

const router = useRouter()

const archives = ref<Archive[]>([])
const loading = ref(false)
const keyword = ref('')
const includeArchived = ref(false)

/// 加载档案列表
async function load() {
  loading.value = true
  try {
    archives.value = await listArchives(
      keyword.value || undefined,
      includeArchived.value
    )
  } finally {
    loading.value = false
  }
}

// ---- 新增 / 编辑 ----
const editVisible = ref(false)
const editing = ref<Archive | null>(null)
function openCreate() {
  editing.value = null
  editVisible.value = true
}
function openEdit(row: Archive) {
  editing.value = row
  editVisible.value = true
}

// ---- 归档切换 ----
async function toggleArchive(row: Archive) {
  const toArchived = row.status !== 'archived'
  await setArchiveStatus(row.id, toArchived)
  ElMessage.success(toArchived ? '已归档' : '已取消归档')
  load()
}

// ---- 跳转录入页 ----
function goEntry(row: Archive) {
  router.push({ name: 'entry', params: { archiveId: row.id } })
}

// ---- 删除 ----
const deleteVisible = ref(false)
const deleting = ref<Archive | null>(null)
const keepImages = ref(true)
const deletingFlag = ref(false)
/// 删除前校验出的档案数据量
const deleteStat = ref<{ pages: number; records: number; amount: number } | null>(null)

async function askDelete(row: Archive) {
  deleting.value = row
  keepImages.value = true
  deleteStat.value = null
  deleteVisible.value = true
  // 校验档案内数据量：页数 / 记录数 / 礼金合计
  const [pages, records] = await Promise.all([
    listPages(row.id),
    listRecords({ archive_id: row.id })
  ])
  deleteStat.value = {
    pages: pages.length,
    records: records.length,
    amount: Math.round(records.reduce((s, r) => s + (r.amount || 0), 0) * 100) / 100
  }
}
async function confirmDelete() {
  if (!deleting.value) return
  deletingFlag.value = true
  try {
    if (!keepImages.value) {
      // 再次确认将同时删除图片
      await ElMessageBox.confirm(
        '将同时删除该档案对应的全部原始图片，确定吗？',
        '风险提示',
        { type: 'warning', confirmButtonText: '删除图片', cancelButtonText: '取消' }
      )
    }
    await deleteArchive(deleting.value.id, keepImages.value)
    ElMessage.success('删除成功')
    deleteVisible.value = false
    load()
  } finally {
    deletingFlag.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.flex-spacer {
  flex: 1;
}
.delete-stat {
  margin-bottom: 12px;
}
</style>
