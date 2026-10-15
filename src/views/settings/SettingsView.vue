<template>
  <div class="page-wrap settings-page">
    <!-- 数据存储信息 -->
    <el-card shadow="never" class="block-card">
      <template #header><b>本地数据存储</b></template>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="数据目录">{{ info.data_dir }}</el-descriptions-item>
        <el-descriptions-item label="数据库文件">{{ info.db_path }}</el-descriptions-item>
        <el-descriptions-item label="数据库大小">{{ formatSize(info.db_size) }}</el-descriptions-item>
        <el-descriptions-item label="图片数量">{{ info.image_count }} 张</el-descriptions-item>
      </el-descriptions>
      <div style="margin-top: 12px">
        <el-button :icon="FolderOpened" @click="openFolder">打开数据目录</el-button>
        <el-button :icon="Refresh" @click="loadInfo">刷新信息</el-button>
      </div>
    </el-card>

    <!-- 备份 -->
    <el-card shadow="never" class="block-card">
      <template #header><b>数据备份</b></template>
      <el-alert type="info" :closable="false" class="info-alert"
        title="建议定期备份。全量备份（.giftbak）包含数据库与全部原始图片，可拷贝到另一台电脑恢复；数据库备份（.db）仅包含数据。" />
      <div class="btn-row">
        <el-button type="primary" :icon="Coin" @click="doBackupDb">备份数据库（.db）</el-button>
        <el-button type="success" :icon="Box" @click="doBackupFull">全量备份（.giftbak）</el-button>
      </div>
    </el-card>

    <!-- 恢复 -->
    <el-card shadow="never" class="block-card">
      <template #header><b>数据恢复</b></template>
      <el-alert type="warning" :closable="false" class="info-alert"
        title="恢复会用备份内容覆盖当前全部数据。恢复成功后软件将自动刷新，恢复前建议先做一次当前数据备份。" />
      <div class="btn-row">
        <el-button type="warning" :icon="Upload" @click="doRestore">选择备份文件恢复</el-button>
      </div>
    </el-card>

    <!-- 危险操作 -->
    <el-card shadow="never" class="block-card danger-card">
      <template #header><b class="danger-title">危险操作</b></template>
      <el-alert type="error" :closable="false" class="info-alert"
        title="清空后将删除全部礼簿档案、收礼/回礼记录和所有照片，且不可恢复。建议先做一次全量备份。" />
      <div class="btn-row">
        <el-button type="danger" :icon="Delete" @click="doClearAll">清空全部数据</el-button>
      </div>
    </el-card>

    <!-- 坚果云同步 -->
    <el-card shadow="never" class="block-card">
      <template #header>
        <b>坚果云 WebDAV 同步</b>
        <el-tag :type="syncInfo.has_config ? 'success' : 'info'" style="margin-left: 10px">
          {{ syncInfo.has_config ? '已配置' : '未配置' }}
        </el-tag>
      </template>

      <!-- 配置表单（未配置或点击修改时显示） -->
      <el-alert v-if="showForm || !syncInfo.has_config" type="info" :closable="false" class="info-alert"
        title="使用坚果云 WebDAV 实现桌面端与手机端数据互通。请先在坚果云账户中生成「第三方应用密码」，输入邮箱和应用密码即可。" />
      <el-form v-if="showForm || !syncInfo.has_config" :model="form" label-width="120px" class="sync-form">
        <el-form-item label="坚果云邮箱">
          <el-input v-model="form.account" placeholder="your@email.com" />
        </el-form-item>
        <el-form-item label="应用密码">
          <el-input v-model="form.app_password" type="password" show-password placeholder="在坚果云「安全选项 → 第三方应用管理」中生成" />
        </el-form-item>
        <el-form-item label="服务器地址">
          <el-input v-model="form.dav_url" />
        </el-form-item>
        <el-form-item label="远程同步目录">
          <el-input v-model="form.remote_dir" placeholder="gift-ledger" />
        </el-form-item>
        <div class="btn-row">
          <el-button type="primary" :icon="Connection" :loading="savingCfg" @click="saveConfig">保存配置</el-button>
          <el-button :icon="Link" :loading="testing" @click="doTest">测试连接</el-button>
          <el-button v-if="syncInfo.has_config" :icon="Close" @click="showForm = false">取消</el-button>
        </div>
      </el-form>

      <!-- 已配置信息 & 同步操作 -->
      <template v-else>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="设备 ID">
            <code style="font-size: 12px">{{ syncInfo.device_id }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="上次推送">{{ fmtTime(syncInfo.last_push_at) }}</el-descriptions-item>
          <el-descriptions-item label="上次拉取">{{ fmtTime(syncInfo.last_pull_at) }}</el-descriptions-item>
          <el-descriptions-item label="上次同步">{{ fmtTime(syncInfo.last_sync_at) }}</el-descriptions-item>
        </el-descriptions>
        <div class="btn-row sync-btn-row">
          <el-button type="primary" :icon="UploadFilled" :loading="syncing" @click="doPush">推送到云端</el-button>
          <el-button type="success" :icon="Download" :loading="syncing" @click="doPull">从云端拉取</el-button>
          <el-button type="warning" :icon="Refresh" :loading="syncing" @click="doSync">自动同步</el-button>
          <el-button :icon="Setting" @click="showForm = true">修改配置</el-button>
        </div>
        <el-alert v-if="lastResult" :type="lastResult.success ? 'success' : 'error'" :closable="false" style="margin-top: 10px">
          {{ lastResult.message }}<template v-if="lastResult.success">（{{ lastResult.image_count }} 张图片）</template>
        </el-alert>
      </template>
    </el-card>

    <!-- 安全说明 -->
    <el-card shadow="never" class="block-card">
      <template #header><b>隐私与安全</b></template>
      <ul class="safe-list">
        <li>软件完全离线运行，不联网、不上传任何数据；</li>
        <li>所有金额禁止负数录入；删除档案时可选择是否保留原始图片；</li>
        <li>记录支持单条/批量删除，删除操作需手动确认。</li>
      </ul>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  Box, Close, Coin, Connection, Delete, Download, FolderOpened,
  Link, Refresh, Setting, Upload, UploadFilled
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { AppInfo } from '@/types'
import {
  backupDatabase, backupFull, clearAllData, getAppInfo, openDataDir,
  pickOpenFile, pickSaveFile, restoreBackup
} from '@/api/backup'
import {
  configureSync, getSyncInfo, pullFromCloud, pushToCloud, syncWithCloud,
  testWebdav,
  type SyncConfig, type SyncInfo, type SyncResult
} from '@/api/sync'

const info = ref<AppInfo>({
  data_dir: '', db_path: '', db_size: 0, image_count: 0
})

async function loadInfo() {
  info.value = await getAppInfo()
}

async function openFolder() {
  await openDataDir()
}

/// 文件大小格式化
function formatSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(2) + ' MB'
}

/// 备份数据库
async function doBackupDb() {
  const target = await pickSaveFile('gifts-data.db', 'db', '数据库备份')
  if (!target) return
  await backupDatabase(target)
  ElMessage.success('数据库备份成功')
}

/// 全量备份
async function doBackupFull() {
  const target = await pickSaveFile(
    `gift-backup-${today()}.giftbak`, 'giftbak', '全量备份'
  )
  if (!target) return
  await backupFull(target)
  ElMessage.success('全量备份成功')
}

/// 恢复
async function doRestore() {
  const source = await pickOpenFile('giftbak', '备份文件（.giftbak/.db）')
  if (!source) return
  await ElMessageBox.confirm(
    '恢复将覆盖当前所有数据，确定继续？',
    '恢复确认',
    { type: 'warning', confirmButtonText: '继续恢复', cancelButtonText: '取消' }
  )
  const msg = await restoreBackup(source)
  ElMessage.success(msg)
  // 刷新整个前端状态（后端连接保持不变，数据已替换）
  setTimeout(() => location.reload(), 800)
}

function today() {
  return new Date().toISOString().slice(0, 10)
}

/// 清空全部数据（双重确认，防误触）
async function doClearAll() {
  await ElMessageBox.confirm(
    '将删除全部礼簿档案、收礼/回礼记录和所有照片，且不可恢复。是否先做一次全量备份？',
    '清空确认（1/2）',
    { type: 'warning', confirmButtonText: '我已备份，继续', cancelButtonText: '取消' }
  )
  await ElMessageBox.confirm(
    '最后确认：清空后数据将无法找回，确定清空全部数据？',
    '清空确认（2/2）',
    { type: 'error', confirmButtonText: '确定清空', cancelButtonText: '取消' }
  )
  await clearAllData()
  ElMessage.success('已清空全部数据')
  setTimeout(() => location.reload(), 800)
}

onMounted(() => { loadInfo(); loadSyncInfo() })

// ---------- 坚果云同步 ----------
const syncInfo = ref<SyncInfo>({
  has_config: false,
  device_id: '',
  last_sync_at: null,
  last_push_at: null,
  last_pull_at: null,
})
const form = reactive<SyncConfig>({
  account: '',
  app_password: '',
  dav_url: 'https://dav.jianguoyun.com/dav/',
  remote_dir: 'gift-ledger',
})
const showForm = ref(false)
const savingCfg = ref(false)
const testing = ref(false)
const syncing = ref(false)
const lastResult = ref<SyncResult | null>(null)

async function loadSyncInfo() {
  try {
    syncInfo.value = await getSyncInfo()
  } catch { /* 首次可能报错，忽略 */ }
}

function fmtTime(t: string | null) {
  if (!t) return '—'
  try {
    const d = new Date(t)
    if (isNaN(d.getTime())) return t
    return d.toLocaleString()
  } catch { return t }
}

async function saveConfig() {
  if (!form.account || !form.app_password) {
    ElMessage.warning('请填写坚果云邮箱和应用密码')
    return
  }
  savingCfg.value = true
  try {
    await configureSync({ ...form })
    ElMessage.success('配置已保存')
    showForm.value = false
    await loadSyncInfo()
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    savingCfg.value = false
  }
}

async function doTest() {
  const cfg = { ...form }
  testing.value = true
  try {
    const msg = await testWebdav(cfg)
    ElMessage.success(msg)
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    testing.value = false
  }
}

async function doPush() {
  syncing.value = true
  lastResult.value = null
  try {
    const r = await pushToCloud()
    lastResult.value = r
    if (r.success) {
      ElMessage.success(r.message)
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    lastResult.value = { success: false, message: msg, image_count: 0, at: '' }
    ElMessage.error(msg)
  } finally {
    syncing.value = false
    loadSyncInfo()
  }
}

async function doPull() {
  await ElMessageBox.confirm(
    '从云端拉取将覆盖本地数据库和图片，拉取前会自动备份当前数据。确定继续？',
    '拉取确认',
    { type: 'warning', confirmButtonText: '继续拉取', cancelButtonText: '取消' }
  )
  syncing.value = true
  lastResult.value = null
  try {
    const r = await pullFromCloud()
    lastResult.value = r
    if (r.success) {
      ElMessage.success(r.message + '，即将刷新页面…')
      setTimeout(() => location.reload(), 1500)
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    lastResult.value = { success: false, message: msg, image_count: 0, at: '' }
    ElMessage.error(msg)
  } finally {
    syncing.value = false
    loadSyncInfo()
  }
}

async function doSync() {
  syncing.value = true
  lastResult.value = null
  try {
    const r = await syncWithCloud()
    lastResult.value = r
    if (r.success) {
      ElMessage.success(r.message)
      if (r.message.includes('拉取')) {
        setTimeout(() => location.reload(), 1500)
      }
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    lastResult.value = { success: false, message: msg, image_count: 0, at: '' }
    ElMessage.error(msg)
  } finally {
    syncing.value = false
    loadSyncInfo()
  }
}
</script>

<style scoped>
.settings-page {
  max-width: 900px;
}
.block-card {
  margin-bottom: 14px;
}
.info-alert {
  margin-bottom: 12px;
}
.btn-row {
  display: flex;
  gap: 12px;
}
.danger-card {
  border-color: #f56c6c;
}
.danger-title {
  color: #f56c6c;
}
.safe-list {
  margin: 0;
  padding-left: 20px;
  line-height: 1.9;
  color: #606266;
}
</style>
