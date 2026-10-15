// 模块1：礼簿档案 API
import type { Archive, ArchiveInput } from '@/types'
import { call } from './http'

/// 档案列表（默认不含已归档）
export function listArchives(keyword?: string, includeArchived = false) {
  return call<Archive[]>('list_archives', { keyword, includeArchived })
}

/// 新增档案
export function createArchive(input: ArchiveInput) {
  return call<Archive>('create_archive', { input })
}

/// 编辑档案
export function updateArchive(input: ArchiveInput) {
  return call<Archive>('update_archive', { input })
}

/// 归档 / 取消归档
export function setArchiveStatus(id: string, archived: boolean) {
  return call<void>('set_archive_status', { id, archived })
}

/// 删除档案；keepImages=true 保留原始图片
export function deleteArchive(id: string, keepImages: boolean) {
  return call<void>('delete_archive', { id, keepImages })
}
