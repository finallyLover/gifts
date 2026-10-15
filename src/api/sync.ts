// 模块8：坚果云同步 API
import { call } from './http'

export interface SyncConfig {
  account: string
  app_password: string
  dav_url?: string
  remote_dir?: string
}

export interface SyncInfo {
  has_config: boolean
  device_id: string
  last_sync_at: string | null
  last_push_at: string | null
  last_pull_at: string | null
}

export interface SyncResult {
  success: boolean
  message: string
  image_count: number
  at: string
}

/// 保存坚果云同步配置
export function configureSync(config: SyncConfig) {
  return call<void>('configure_sync', { config })
}

/// 查询当前同步状态
export function getSyncInfo() {
  return call<SyncInfo>('get_sync_info')
}

/// 测试 WebDAV 连接
export function testWebdav(config?: SyncConfig) {
  return call<string>('test_webdav', { config })
}

/// 推送到坚果云
export function pushToCloud() {
  return call<SyncResult>('push_to_cloud')
}

/// 从坚果云拉取
export function pullFromCloud() {
  return call<SyncResult>('pull_from_cloud')
}

/// 全量同步（自动决定推/拉）
export function syncWithCloud() {
  return call<SyncResult>('sync_with_cloud')
}
