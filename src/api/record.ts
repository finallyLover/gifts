// 模块2：收礼记录 API
import type { GiftRecord, RecordFilter, RecordInput } from '@/types'
import { call } from './http'

/// 按筛选条件查询记录
export function listRecords(filter: RecordFilter = {}) {
  return call<GiftRecord[]>('list_records', {
    archiveId: filter.archive_id,
    pageId: filter.page_id,
    minAmount: filter.min_amount,
    maxAmount: filter.max_amount,
    hasGift: filter.has_gift,
    relation: filter.relation,
    status: filter.status,
    noPage: filter.no_page
  })
}

/// 新增/更新单条
export function saveRecord(input: RecordInput) {
  return call<GiftRecord>('save_record', { input })
}

/// 批量保存
export function saveRecords(inputs: RecordInput[]) {
  return call<GiftRecord[]>('save_records', { inputs })
}

/// 批量修改核对状态
export function setRecordsStatus(ids: string[], status: 'pending' | 'confirmed') {
  return call<void>('set_records_status', { ids, status })
}

/// 批量删除
export function deleteRecords(ids: string[]) {
  return call<void>('delete_records', { ids })
}
