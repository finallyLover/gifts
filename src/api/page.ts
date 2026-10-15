// 模块1：礼簿分页 API
import type { PageInfo } from '@/types'
import { call } from './http'

/// 页面列表
export function listPages(archiveId: string) {
  return call<PageInfo[]>('list_pages', { archiveId })
}

/// 导入图片（paths 为系统选择对话框返回的绝对路径数组）
export function importPages(archiveId: string, paths: string[]) {
  return call<PageInfo[]>('import_pages', { archiveId, paths })
}

/// 调整页面顺序
export function reorderPages(pageIds: string[]) {
  return call<void>('reorder_pages', { pageIds })
}

/// 删除页面
export function deletePage(pageId: string) {
  return call<void>('delete_page', { pageId })
}

/// 旋转页面图片（angle：90 顺时针 / -90 逆时针），返回更新后的页面
export function rotatePage(pageId: string, angle: number) {
  return call<PageInfo>('rotate_page', { pageId, angle })
}
