// ============================================================
// 模块2：收礼记录命令（礼簿录入的核心数据操作）
// ============================================================
use chrono::Local;
use rusqlite::{params, params_from_iter, Row};
use tauri::State;

use crate::db::DbState;
use crate::error::{AppError, AppResult};
use crate::models::{GiftRecord, RecordInput};

fn now_str() -> String {
    Local::now().format("%Y-%m-%d %H:%M:%S").to_string()
}

/// 行映射（pub(crate) 供 person 等模块复用）
pub(crate) fn row_to_record(row: &Row) -> rusqlite::Result<GiftRecord> {
    Ok(GiftRecord {
        id: row.get("id")?,
        archive_id: row.get("archive_id")?,
        page_id: row.get("page_id")?,
        person_id: row.get("person_id")?,
        guest_name: row.get("guest_name")?,
        relation: row.get("relation")?,
        amount: row.get("amount")?,
        gift: row.get("gift")?,
        gift_amount: row.get("gift_amount")?,
        remark: row.get("remark")?,
        operator: row.get("operator")?,
        status: row.get("status")?,
        row_order: row.get("row_order")?,
        cell_x: row.get("cell_x")?,
        cell_y: row.get("cell_y")?,
        cell_w: row.get("cell_w")?,
        cell_h: row.get("cell_h")?,
        created_at: row.get("created_at")?,
        updated_at: row.get("updated_at")?,
        archive_name: None,
    })
}

/// 按姓名获取/自动创建人员档案，返回 person_id
fn ensure_person(conn: &rusqlite::Connection, name: &str) -> AppResult<Option<String>> {
    let name = name.trim();
    if name.is_empty() {
        return Ok(None);
    }
    if let Some(id) = conn
        .query_row(
            "SELECT id FROM persons WHERE name=?",
            params![name],
            |r| r.get::<_, String>(0),
        )
        .ok()
    {
        return Ok(Some(id));
    }
    let id = uuid::Uuid::new_v4().to_string();
    let now = now_str();
    conn.execute(
        "INSERT INTO persons (id, name, return_status, created_at, updated_at)
         VALUES (?, ?, 'pending', ?, ?)",
        params![id, name, now, now],
    )?;
    Ok(Some(id))
}

/// 查询记录列表（支持按档案/页面/金额区间/有无礼品/状态筛选）
/// no_page=true 时仅查询"直接录入"（无关联页面）的记录
#[tauri::command]
pub fn list_records(
    state: State<DbState>,
    archive_id: Option<String>,
    page_id: Option<String>,
    min_amount: Option<f64>,
    max_amount: Option<f64>,
    has_gift: Option<bool>,
    relation: Option<String>,
    status: Option<String>,
    no_page: Option<bool>,
) -> AppResult<Vec<GiftRecord>> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    let mut sql = String::from(
        "SELECT gr.*, a.name AS archive_name
         FROM gift_records gr LEFT JOIN archives a ON gr.archive_id = a.id
         WHERE 1=1",
    );
    let mut args: Vec<Box<dyn rusqlite::ToSql>> = Vec::new();
    if let Some(v) = archive_id.filter(|s| !s.is_empty()) {
        sql.push_str(" AND gr.archive_id=?");
        args.push(Box::new(v));
    }
    if let Some(v) = page_id.filter(|s| !s.is_empty()) {
        sql.push_str(" AND gr.page_id=?");
        args.push(Box::new(v));
    }
    if no_page == Some(true) {
        // 直接录入的记录：无关联页面
        sql.push_str(" AND gr.page_id IS NULL");
    }
    if let Some(v) = min_amount {
        sql.push_str(" AND gr.amount>=?");
        args.push(Box::new(v));
    }
    if let Some(v) = max_amount {
        sql.push_str(" AND gr.amount<=?");
        args.push(Box::new(v));
    }
    if has_gift == Some(true) {
        sql.push_str(" AND gr.gift IS NOT NULL AND gr.gift != ''");
    }
    if let Some(v) = relation.filter(|s| !s.is_empty()) {
        sql.push_str(" AND gr.relation=?");
        args.push(Box::new(v));
    }
    if let Some(v) = status.filter(|s| !s.is_empty()) {
        sql.push_str(" AND gr.status=?");
        args.push(Box::new(v));
    }
    sql.push_str(" ORDER BY gr.page_id, gr.row_order, gr.created_at");

    let mut stmt = conn.prepare(&sql)?;
    let mut list = stmt
        .query_map(params_from_iter(args.iter()), |row| {
            let mut rec = row_to_record(row)?;
            rec.archive_name = row.get("archive_name")?;
            Ok(rec)
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    // 无页面的记录排到最后（上面按 page_id 排序 SQLite NULL 在前，这里调整）
    list.sort_by_key(|r| (r.page_id.is_none(), r.row_order));
    Ok(list)
}

/// 新增或更新单条记录（有 id 为更新）
#[tauri::command]
pub fn save_record(state: State<DbState>, input: RecordInput) -> AppResult<GiftRecord> {
    let rec = save_record_state(&state, input)?;
    // 修改来宾姓名后，旧姓名对应的人员档案可能已无任何记录，一并清理
    {
        let conn = state.0.lock().map_err(crate::db::lock_err)?;
        crate::db::cleanup_orphan_persons(&conn)?;
    }
    Ok(rec)
}

/// 批量保存（一次落库多行记录）
#[tauri::command]
pub fn save_records(state: State<DbState>, inputs: Vec<RecordInput>) -> AppResult<Vec<GiftRecord>> {
    let mut result = Vec::with_capacity(inputs.len());
    for input in inputs {
        // 直接复用单条保存逻辑（每条记录独立加锁，简单可靠）
        result.push(save_record_state(&state, input)?);
    }
    // 全部保存结束后统一清理孤儿人员档案
    // （不能放在单条保存里：批量保存时各条人员归属会交叉变更，中途清理会误删）
    {
        let conn = state.0.lock().map_err(crate::db::lock_err)?;
        crate::db::cleanup_orphan_persons(&conn)?;
    }
    Ok(result)
}

/// save_record 的内部实现（避免 State<DbState> 被 move 后无法复用）
fn save_record_state(state: &State<DbState>, input: RecordInput) -> AppResult<GiftRecord> {
    if input.amount < 0.0 || input.gift_amount < 0.0 {
        return Err(AppError::Other("金额与礼品金额均不能为负数".to_string()));
    }
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    let person_id = ensure_person(&conn, &input.guest_name)?;
    let now = now_str();
    let id = input
        .id
        .clone()
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
    let status = input.status.clone().unwrap_or_else(|| "pending".to_string());
    let row_order = input.row_order.unwrap_or(0);

    // UPSERT：先尝试更新，不存在则插入
    let affected = conn.execute(
        "UPDATE gift_records SET
            page_id=?, person_id=?, guest_name=?, relation=?, amount=?, gift=?, gift_amount=?, remark=?,
            operator=?, status=?, row_order=?, cell_x=?, cell_y=?, cell_w=?, cell_h=?, updated_at=?
         WHERE id=?",
        params![
            input.page_id,
            person_id,
            input.guest_name,
            input.relation,
            input.amount,
            input.gift,
            input.gift_amount,
            input.remark,
            input.operator,
            status,
            row_order,
            input.cell_x,
            input.cell_y,
            input.cell_w,
            input.cell_h,
            now,
            id
        ],
    )?;
    if affected == 0 {
        conn.execute(
            "INSERT INTO gift_records
                (id, archive_id, page_id, person_id, guest_name, relation, amount, gift, gift_amount, remark,
                 operator, status, row_order, cell_x, cell_y, cell_w, cell_h, created_at, updated_at)
             VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            params![
                id,
                input.archive_id,
                input.page_id,
                person_id,
                input.guest_name,
                input.relation,
                input.amount,
                input.gift,
                input.gift_amount,
                input.remark,
                input.operator,
                status,
                row_order,
                input.cell_x,
                input.cell_y,
                input.cell_w,
                input.cell_h,
                now,
                now
            ],
        )?;
    }
    conn.query_row(
        "SELECT * FROM gift_records WHERE id=?",
        params![id],
        row_to_record,
    )
    .map_err(Into::into)
}

/// 批量修改核对状态
#[tauri::command]
pub fn set_records_status(
    state: State<DbState>,
    ids: Vec<String>,
    status: String,
) -> AppResult<()> {
    if status != "pending" && status != "confirmed" {
        return Err(AppError::Other("非法的核对状态".to_string()));
    }
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    for id in &ids {
        conn.execute(
            "UPDATE gift_records SET status=?, updated_at=? WHERE id=?",
            params![status, now_str(), id],
        )?;
    }
    Ok(())
}

/// 批量删除记录
#[tauri::command]
pub fn delete_records(state: State<DbState>, ids: Vec<String>) -> AppResult<()> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    for id in &ids {
        conn.execute("DELETE FROM gift_records WHERE id=?", params![id])?;
    }
    // 删除记录后，不再有任何往来的人员档案一并清理
    crate::db::cleanup_orphan_persons(&conn)?;
    Ok(())
}
