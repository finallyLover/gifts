// 模块7：备份恢复 / 文件对话框 API
import type { AppInfo } from '@/types'
import { call } from './http'

/// 获取应用数据信息
export function getAppInfo() {
  return call<AppInfo>('get_app_info')
}

/// 打开数据目录
export function openDataDir() {
  return call<void>('open_data_dir')
}

/// 选择图片文件（系统对话框）
export function pickImageFiles() {
  return call<string[]>('pick_image_files')
}

/// 选择保存路径
export function pickSaveFile(defaultName: string, ext: string, desc: string) {
  return call<string | null>('pick_save_file', { defaultName, ext, desc })
}

/// 选择打开文件
export function pickOpenFile(ext: string, desc: string) {
  return call<string | null>('pick_open_file', { ext, desc })
}

/// 仅备份数据库
export function backupDatabase(targetPath: string) {
  return call<string>('backup_database', { targetPath })
}

/// 全量备份（db + 图片）
export function backupFull(targetPath: string) {
  return call<string>('backup_full', { targetPath })
}

/// 恢复备份
export function restoreBackup(sourcePath: string) {
  return call<string>('restore_backup', { sourcePath })
}

/// 清空全部数据（不可恢复）
export function clearAllData() {
  return call<void>('clear_all_data')
}
