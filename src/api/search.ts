// 模块4：全局检索 API
import type { SearchResult } from '@/types'
import { call } from './http'

export function searchAll(keyword: string) {
  return call<SearchResult>('search_all', { keyword })
}
