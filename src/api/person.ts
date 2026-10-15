// 模块3：人情往来 API
import type { PersonDetail, PersonSummary, ReturnGiftInput } from '@/types'
import { call } from './http'

/// 人员汇总列表
export function listPersonSummaries(keyword?: string, returnStatus?: string) {
  return call<PersonSummary[]>('list_person_summaries', { keyword, returnStatus })
}

/// 单人详情
export function getPersonDetail(id: string) {
  return call<PersonDetail>('get_person_detail', { id })
}

/// 新增回礼（自动标记已回礼）
export function addReturnGift(input: ReturnGiftInput) {
  return call('add_return_gift', { input })
}

/// 编辑回礼
export function updateReturnGift(input: ReturnGiftInput) {
  return call<void>('update_return_gift', { input })
}

/// 删除回礼
export function deleteReturnGift(id: string) {
  return call<void>('delete_return_gift', { id })
}

/// 切换回礼标记
export function setPersonStatus(personId: string, returnStatus: 'pending' | 'returned') {
  return call<void>('set_person_status', { personId, returnStatus })
}
