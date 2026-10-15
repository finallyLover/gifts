// 模块5：统计报表 API
import type { ArchiveStat, GlobalStat, PersonSummary } from '@/types'
import { call } from './http'

/// 单场礼簿统计
export function getArchiveStat(archiveId: string) {
  return call<ArchiveStat>('archive_stat', { archiveId })
}

/// 全局人情统计
export function getGlobalStat() {
  return call<GlobalStat>('global_stat')
}

/// 人情对照表
export function getComparisonReport(keyword?: string) {
  return call<PersonSummary[]>('comparison_report', { keyword })
}
