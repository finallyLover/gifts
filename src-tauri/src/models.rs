// ============================================================
// 数据模型层（serde 结构，与前端 src/types/index.ts 对应）
// ============================================================
use serde::{Deserialize, Serialize};

// ---------- 模块1：礼簿档案 ----------
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Archive {
    pub id: String,
    pub name: String,
    pub banquet_type: String,
    pub event_date: Option<String>,
    pub host_name: Option<String>,
    pub host_contact: Option<String>,
    pub remark: Option<String>,
    pub status: String, // active / archived
    pub created_at: String,
    pub updated_at: String,
    pub archived_at: Option<String>,
}

/// 新增/编辑档案入参
#[derive(Debug, Clone, Deserialize)]
pub struct ArchiveInput {
    pub id: Option<String>,
    pub name: String,
    pub banquet_type: String,
    pub event_date: Option<String>,
    pub host_name: Option<String>,
    pub host_contact: Option<String>,
    pub remark: Option<String>,
}

// ---------- 模块1：礼簿页面 ----------
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Page {
    pub id: String,
    pub archive_id: String,
    pub file_path: String,
    pub file_name: Option<String>,
    pub page_order: i64,
    pub width: Option<i64>,
    pub height: Option<i64>,
    pub segments_json: Option<String>,
    pub created_at: String,
    /// 图片在本机的绝对路径（仅用于序列化输出，前端 convertFileSrc 使用）
    #[serde(default)]
    pub abs_path: Option<String>,
}

// ---------- 模块2：表格分割结构 ----------
/// 一个矩形区域（行列线或单元格），坐标基于原图自然像素
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Cell {
    #[serde(default)]
    pub row: Option<i64>,
    #[serde(default)]
    pub col: Option<i64>,
    pub x: f64,
    pub y: f64,
    pub w: f64,
    pub h: f64,
}

/// 整页分割布局
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PageLayout {
    pub rows: Vec<Cell>,
    pub cols: Vec<Cell>,
    /// 每一列的字段角色：name / relation / amount / gift / remark / ignore
    #[serde(default)]
    pub col_roles: Vec<String>,
}

// ---------- 模块2：收礼记录 ----------
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GiftRecord {
    pub id: String,
    pub archive_id: String,
    pub page_id: Option<String>,
    pub person_id: Option<String>,
    pub guest_name: String,
    pub relation: Option<String>,
    pub amount: f64,
    pub gift: Option<String>,
    /// 礼品折算金额（填了=送了价值该金额的礼品；0=实物不计金额）
    #[serde(default)]
    pub gift_amount: f64,
    pub remark: Option<String>,
    pub operator: Option<String>,
    pub status: String, // pending / confirmed
    pub row_order: i64,
    pub cell_x: Option<f64>,
    pub cell_y: Option<f64>,
    pub cell_w: Option<f64>,
    pub cell_h: Option<f64>,
    pub created_at: String,
    pub updated_at: String,
    /// 所属档案名称（跨档案查询时附带）
    #[serde(default)]
    pub archive_name: Option<String>,
}

/// 新增/编辑收礼记录入参
#[derive(Debug, Clone, Deserialize)]
pub struct RecordInput {
    pub id: Option<String>,
    pub archive_id: String,
    #[serde(default)]
    pub page_id: Option<String>,
    #[serde(default)]
    pub guest_name: String,
    #[serde(default)]
    pub relation: Option<String>,
    #[serde(default)]
    pub amount: f64,
    #[serde(default)]
    pub gift: Option<String>,
    #[serde(default)]
    pub gift_amount: f64,
    #[serde(default)]
    pub remark: Option<String>,
    #[serde(default)]
    pub operator: Option<String>,
    #[serde(default)]
    pub status: Option<String>,
    #[serde(default)]
    pub row_order: Option<i64>,
    #[serde(default)]
    pub cell_x: Option<f64>,
    #[serde(default)]
    pub cell_y: Option<f64>,
    #[serde(default)]
    pub cell_w: Option<f64>,
    #[serde(default)]
    pub cell_h: Option<f64>,
}

// ---------- 模块3：回礼记录 ----------
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReturnGift {
    pub id: String,
    pub person_id: Option<String>,
    pub person_name: String,
    pub event_name: Option<String>,
    pub event_date: Option<String>,
    pub amount: f64,
    pub gift: Option<String>,
    /// 礼品折算金额（语义同收礼记录）
    #[serde(default)]
    pub gift_amount: f64,
    pub remark: Option<String>,
    pub operator: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ReturnGiftInput {
    #[serde(default)]
    pub id: Option<String>,
    pub person_name: String,
    #[serde(default)]
    pub event_name: Option<String>,
    #[serde(default)]
    pub event_date: Option<String>,
    #[serde(default)]
    pub amount: f64,
    #[serde(default)]
    pub gift: Option<String>,
    #[serde(default)]
    pub gift_amount: f64,
    #[serde(default)]
    pub remark: Option<String>,
    #[serde(default)]
    pub operator: Option<String>,
}

// ---------- 模块3：人员汇总 ----------
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PersonSummary {
    pub id: String,
    pub name: String,
    pub pinyin: Option<String>,
    pub remark: Option<String>,
    pub return_status: String, // pending / returned
    /// 收礼合计（礼金 + 礼品折算）
    pub total_in: f64,
    /// 回礼合计（礼金 + 礼品折算）
    pub total_out: f64,
    /// 其中：收礼的礼品折算部分
    #[serde(default)]
    pub gift_in: f64,
    /// 其中：回礼的礼品折算部分
    #[serde(default)]
    pub gift_out: f64,
    pub diff: f64, // 收 - 出
    pub in_count: i64,
    pub out_count: i64,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PersonDetail {
    #[serde(flatten)]
    pub summary: PersonSummary,
    pub incoming: Vec<GiftRecord>,
    pub outgoing: Vec<ReturnGift>,
}

// ---------- 模块4：全局检索结果 ----------
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SearchResult {
    pub persons: Vec<PersonMatch>,
    pub incoming: Vec<GiftRecord>,
    pub outgoing: Vec<ReturnGift>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PersonMatch {
    pub id: String,
    pub name: String,
}

// ---------- 模块5：统计报表 ----------
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ArchiveStat {
    pub total_count: i64,
    /// 礼金合计（含礼品折算）
    pub total_amount: f64,
    /// 其中：礼品折算合计
    #[serde(default)]
    pub gift_amount: f64,
    pub confirmed_count: i64,
    pub pending_count: i64,
    pub by_relation: Vec<GroupStat>,
    pub amount_brackets: Vec<GroupStat>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GroupStat {
    pub label: String,
    pub count: i64,
    pub amount: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct GlobalStat {
    /// 收礼合计（含礼品折算）
    pub total_in: f64,
    /// 回礼合计（含礼品折算）
    pub total_out: f64,
    /// 其中：收礼的礼品折算部分
    #[serde(default)]
    pub gift_in: f64,
    /// 其中：回礼的礼品折算部分
    #[serde(default)]
    pub gift_out: f64,
    pub net_diff: f64,
    pub in_count: i64,
    pub out_count: i64,
    pub pending_persons: Vec<PendingPerson>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PendingPerson {
    pub id: String,
    pub name: String,
    pub total_in: f64,
    pub last_date: Option<String>,
}

// ---------- 模块7：应用信息 ----------
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppInfo {
    pub data_dir: String,
    pub db_path: String,
    pub db_size: u64,
    pub image_count: u64,
}
