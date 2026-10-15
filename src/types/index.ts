// ============================================================
// 前端类型定义（与 Rust models 一一对应，字段保持 snake_case）
// ============================================================

// ---------- 模块1：礼簿档案 ----------
export interface Archive {
  id: string
  name: string
  banquet_type: string
  event_date: string | null
  host_name: string | null
  host_contact: string | null
  remark: string | null
  status: 'active' | 'archived'
  created_at: string
  updated_at: string
  archived_at: string | null
}

export interface ArchiveInput {
  id?: string
  name: string
  banquet_type: string
  event_date?: string | null
  host_name?: string | null
  host_contact?: string | null
  remark?: string | null
}

// ---------- 模块1：页面 ----------
export interface PageInfo {
  id: string
  archive_id: string
  file_path: string
  file_name: string | null
  page_order: number
  width: number | null
  height: number | null
  segments_json: string | null
  created_at: string
  abs_path: string | null
}

// ---------- 模块2：表格分割 ----------
export interface Cell {
  row?: number | null
  col?: number | null
  x: number
  y: number
  w: number
  h: number
}

export interface PageLayout {
  rows: Cell[]
  cols: Cell[]
  col_roles: string[]
}

// ---------- 模块2：收礼记录 ----------
export interface GiftRecord {
  id: string
  archive_id: string
  page_id: string | null
  person_id: string | null
  guest_name: string
  relation: string | null
  amount: number
  gift: string | null
  /** 礼品折算金额（填了=送了价值该金额的礼品；0=实物不计金额） */
  gift_amount: number
  remark: string | null
  operator: string | null
  status: 'pending' | 'confirmed'
  row_order: number
  cell_x: number | null
  cell_y: number | null
  cell_w: number | null
  cell_h: number | null
  created_at: string
  updated_at: string
  archive_name?: string | null
}

export interface RecordInput {
  id?: string
  archive_id: string
  page_id?: string | null
  guest_name: string
  relation?: string | null
  amount: number
  gift?: string | null
  gift_amount?: number
  remark?: string | null
  operator?: string | null
  status?: 'pending' | 'confirmed'
  row_order?: number
  cell_x?: number | null
  cell_y?: number | null
  cell_w?: number | null
  cell_h?: number | null
}

// ---------- 模块3：回礼 ----------
export interface ReturnGift {
  id: string
  person_id: string | null
  person_name: string
  event_name: string | null
  event_date: string | null
  amount: number
  gift: string | null
  gift_amount: number
  remark: string | null
  operator: string | null
  created_at: string
}

export interface ReturnGiftInput {
  id?: string
  person_name: string
  event_name?: string | null
  event_date?: string | null
  amount: number
  gift?: string | null
  gift_amount?: number
  remark?: string | null
  operator?: string | null
}

// ---------- 模块3：人员汇总 ----------
export interface PersonSummary {
  id: string
  name: string
  pinyin: string | null
  remark: string | null
  return_status: 'pending' | 'returned'
  /** 收礼合计（礼金+礼品折算） */
  total_in: number
  /** 回礼合计（礼金+礼品折算） */
  total_out: number
  /** 其中：收礼的礼品折算 */
  gift_in: number
  /** 其中：回礼的礼品折算 */
  gift_out: number
  diff: number
  in_count: number
  out_count: number
  created_at: string
  updated_at: string
}

export interface PersonDetail {
  id: string
  name: string
  pinyin: string | null
  remark: string | null
  return_status: 'pending' | 'returned'
  total_in: number
  total_out: number
  gift_in: number
  gift_out: number
  diff: number
  in_count: number
  out_count: number
  created_at: string
  updated_at: string
  incoming: GiftRecord[]
  outgoing: ReturnGift[]
}

// ---------- 模块4：检索 ----------
export interface PersonMatch {
  id: string
  name: string
}

export interface SearchResult {
  persons: PersonMatch[]
  incoming: GiftRecord[]
  outgoing: ReturnGift[]
}

// ---------- 模块5：统计 ----------
export interface GroupStat {
  label: string
  count: number
  amount: number
}

export interface ArchiveStat {
  total_count: number
  /** 合计（含礼品折算） */
  total_amount: number
  /** 其中：礼品折算合计 */
  gift_amount: number
  confirmed_count: number
  pending_count: number
  by_relation: GroupStat[]
  amount_brackets: GroupStat[]
}

export interface PendingPerson {
  id: string
  name: string
  total_in: number
  last_date: string | null
}

export interface GlobalStat {
  /** 收礼合计（含礼品折算） */
  total_in: number
  /** 回礼合计（含礼品折算） */
  total_out: number
  /** 其中：收礼的礼品折算 */
  gift_in: number
  /** 其中：回礼的礼品折算 */
  gift_out: number
  net_diff: number
  in_count: number
  out_count: number
  pending_persons: PendingPerson[]
}

// ---------- 模块7：应用信息 ----------
export interface AppInfo {
  data_dir: string
  db_path: string
  db_size: number
  image_count: number
}

// 记录筛选条件
export interface RecordFilter {
  archive_id?: string
  page_id?: string
  /** true = 仅查询无关联页面的"直接录入"记录 */
  no_page?: boolean
  min_amount?: number
  max_amount?: number
  has_gift?: boolean
  relation?: string
  status?: string
}
