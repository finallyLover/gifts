// ============================================================
// 模块3：人情往来双向管理命令
//   收礼（gift_records）—— 来自礼簿录入
//   回礼（return_gifts）—— 手动新增
// ============================================================
use chrono::Local;
use rusqlite::{params, params_from_iter, Row};
use tauri::State;

use crate::db::DbState;
use crate::error::{AppError, AppResult};
use crate::models::{
    GiftRecord, PersonDetail, PersonSummary, ReturnGift, ReturnGiftInput,
};

fn now_str() -> String {
    Local::now().format("%Y-%m-%d %H:%M:%S").to_string()
}

/// 人员汇总行映射（含双向聚合金额；合计含礼品折算，差额=收-出）
fn row_to_summary(row: &Row) -> rusqlite::Result<PersonSummary> {
    let total_in: f64 = row.get("total_in")?;
    let total_out: f64 = row.get("total_out")?;
    Ok(PersonSummary {
        id: row.get("id")?,
        name: row.get("name")?,
        pinyin: row.get("pinyin")?,
        remark: row.get("remark")?,
        return_status: row.get("return_status")?,
        total_in,
        total_out,
        gift_in: row.get("gift_in")?,
        gift_out: row.get("gift_out")?,
        diff: (total_in - total_out).round_to_2(),
        in_count: row.get("in_count")?,
        out_count: row.get("out_count")?,
        created_at: row.get("created_at")?,
        updated_at: row.get("updated_at")?,
    })
}

// 给 f64 加保留两位小数的便捷方法
trait Round2 {
    fn round_to_2(self) -> f64;
}
impl Round2 for f64 {
    fn round_to_2(self) -> f64 {
        (self * 100.0).round() / 100.0
    }
}

/// 人员汇总列表（可按姓名/回礼状态筛选），按收礼总额降序
#[tauri::command]
pub fn list_person_summaries(
    state: State<DbState>,
    keyword: Option<String>,
    return_status: Option<String>,
) -> AppResult<Vec<PersonSummary>> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    // 子查询聚合：合计=礼金+礼品折算；gift_in/gift_out 为其中礼品折算部分
    let mut sql = String::from(
        r#"
        SELECT p.*,
            COALESCE((SELECT SUM(amount + COALESCE(gift_amount,0)) FROM gift_records gr WHERE gr.person_id=p.id), 0) AS total_in,
            COALESCE((SELECT SUM(amount + COALESCE(gift_amount,0)) FROM return_gifts rg WHERE rg.person_id=p.id), 0) AS total_out,
            COALESCE((SELECT SUM(gift_amount) FROM gift_records gr WHERE gr.person_id=p.id), 0) AS gift_in,
            COALESCE((SELECT SUM(gift_amount) FROM return_gifts rg WHERE rg.person_id=p.id), 0) AS gift_out,
            (SELECT COUNT(*) FROM gift_records gr WHERE gr.person_id=p.id) AS in_count,
            (SELECT COUNT(*) FROM return_gifts rg WHERE rg.person_id=p.id) AS out_count
        FROM persons p
        WHERE 1=1
        "#,
    );
    let mut args: Vec<Box<dyn rusqlite::ToSql>> = Vec::new();
    if let Some(kw) = keyword.filter(|s| !s.trim().is_empty()) {
        sql.push_str(" AND (p.name LIKE ? OR p.pinyin LIKE ?)");
        args.push(Box::new(format!("%{}%", kw)));
        args.push(Box::new(format!("%{}%", kw)));
    }
    if let Some(st) = return_status.filter(|s| !s.is_empty()) {
        sql.push_str(" AND p.return_status=?");
        args.push(Box::new(st));
    }
    sql.push_str(" ORDER BY total_in DESC, p.created_at DESC");

    let mut stmt = conn.prepare(&sql)?;
    let list = stmt
        .query_map(params_from_iter(args.iter()), row_to_summary)?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(list)
}

/// 单人详情：基本信息 + 全部收礼记录 + 全部回礼记录
#[tauri::command]
pub fn get_person_detail(state: State<DbState>, id: String) -> AppResult<PersonDetail> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    let summary = conn.query_row(
        r#"
        SELECT p.*,
            COALESCE((SELECT SUM(amount + COALESCE(gift_amount,0)) FROM gift_records gr WHERE gr.person_id=p.id), 0) AS total_in,
            COALESCE((SELECT SUM(amount + COALESCE(gift_amount,0)) FROM return_gifts rg WHERE rg.person_id=p.id), 0) AS total_out,
            COALESCE((SELECT SUM(gift_amount) FROM gift_records gr WHERE gr.person_id=p.id), 0) AS gift_in,
            COALESCE((SELECT SUM(gift_amount) FROM return_gifts rg WHERE rg.person_id=p.id), 0) AS gift_out,
            (SELECT COUNT(*) FROM gift_records gr WHERE gr.person_id=p.id) AS in_count,
            (SELECT COUNT(*) FROM return_gifts rg WHERE rg.person_id=p.id) AS out_count
        FROM persons p WHERE p.id=?
        "#,
        params![id],
        row_to_summary,
    )?;

    // 收礼记录（附带档案名）
    let mut stmt_in = conn.prepare(
        r#"SELECT gr.*, a.name AS archive_name
           FROM gift_records gr LEFT JOIN archives a ON gr.archive_id=a.id
           WHERE gr.person_id=?
           ORDER BY gr.created_at DESC"#,
    )?;
    let incoming = stmt_in
        .query_map(params![id], |row| {
            let mut rec: GiftRecord = crate::commands::record::row_to_record(row)?;
            rec.archive_name = row.get("archive_name")?;
            Ok(rec)
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;

    // 回礼记录
    let mut stmt_out = conn.prepare(
        "SELECT * FROM return_gifts WHERE person_id=? ORDER BY created_at DESC",
    )?;
    let outgoing = stmt_out
        .query_map(params![id], row_to_return)?
        .collect::<rusqlite::Result<Vec<_>>>()?;

    Ok(PersonDetail {
        summary,
        incoming,
        outgoing,
    })
}

fn row_to_return(row: &Row) -> rusqlite::Result<ReturnGift> {
    Ok(ReturnGift {
        id: row.get("id")?,
        person_id: row.get("person_id")?,
        person_name: row.get("person_name")?,
        event_name: row.get("event_name")?,
        event_date: row.get("event_date")?,
        amount: row.get("amount")?,
        gift: row.get("gift")?,
        gift_amount: row.get("gift_amount")?,
        remark: row.get("remark")?,
        operator: row.get("operator")?,
        created_at: row.get("created_at")?,
    })
}

/// 按姓名获取/新建人员
fn ensure_person(conn: &rusqlite::Connection, name: &str) -> AppResult<String> {
    let name = name.trim();
    if name.is_empty() {
        return Err(AppError::Other("姓名不能为空".to_string()));
    }
    if let Ok(id) = conn.query_row(
        "SELECT id FROM persons WHERE name=?",
        params![name],
        |r| r.get::<_, String>(0),
    ) {
        return Ok(id);
    }
    let id = uuid::Uuid::new_v4().to_string();
    let now = now_str();
    conn.execute(
        "INSERT INTO persons (id, name, return_status, created_at, updated_at)
         VALUES (?, ?, 'pending', ?, ?)",
        params![id, name, now, now],
    )?;
    Ok(id)
}

/// 新增回礼记录（自动把人员标记为"已回礼"）
#[tauri::command]
pub fn add_return_gift(
    state: State<DbState>,
    input: ReturnGiftInput,
) -> AppResult<ReturnGift> {
    if input.amount < 0.0 || input.gift_amount < 0.0 {
        return Err(AppError::Other("金额与礼品金额均不能为负数".to_string()));
    }
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    let person_id = ensure_person(&conn, &input.person_name)?;
    let id = uuid::Uuid::new_v4().to_string();
    let now = now_str();
    conn.execute(
        "INSERT INTO return_gifts
            (id, person_id, person_name, event_name, event_date, amount, gift, gift_amount, remark, operator, created_at)
         VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        params![
            id,
            person_id,
            input.person_name,
            input.event_name,
            input.event_date,
            input.amount,
            input.gift,
            input.gift_amount,
            input.remark,
            input.operator,
            now
        ],
    )?;
    // 自动标记已回礼
    conn.execute(
        "UPDATE persons SET return_status='returned', updated_at=? WHERE id=?",
        params![now, person_id],
    )?;
    conn.query_row("SELECT * FROM return_gifts WHERE id=?", params![id], row_to_return)
        .map_err(Into::into)
}

/// 编辑回礼记录
#[tauri::command]
pub fn update_return_gift(
    state: State<DbState>,
    input: ReturnGiftInput,
) -> AppResult<()> {
    let id = input
        .id
        .clone()
        .ok_or_else(|| AppError::Other("缺少回礼记录id".to_string()))?;
    if input.amount < 0.0 || input.gift_amount < 0.0 {
        return Err(AppError::Other("金额与礼品金额均不能为负数".to_string()));
    }
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    conn.execute(
        "UPDATE return_gifts SET
            event_name=?, event_date=?, amount=?, gift=?, gift_amount=?, remark=?, operator=?
         WHERE id=?",
        params![
            input.event_name,
            input.event_date,
            input.amount,
            input.gift,
            input.gift_amount,
            input.remark,
            input.operator,
            id
        ],
    )?;
    Ok(())
}

/// 删除回礼记录
#[tauri::command]
pub fn delete_return_gift(state: State<DbState>, id: String) -> AppResult<()> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    conn.execute("DELETE FROM return_gifts WHERE id=?", params![id])?;
    // 删除回礼后，若该人已无任何往来记录，则清理其人员档案
    crate::db::cleanup_orphan_persons(&conn)?;
    Ok(())
}

/// 手动切换人员回礼标记（pending/returned）
#[tauri::command]
pub fn set_person_status(
    state: State<DbState>,
    person_id: String,
    return_status: String,
) -> AppResult<()> {
    if return_status != "pending" && return_status != "returned" {
        return Err(AppError::Other("非法的回礼状态".to_string()));
    }
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    conn.execute(
        "UPDATE persons SET return_status=?, updated_at=? WHERE id=?",
        params![return_status, now_str(), person_id],
    )?;
    Ok(())
}
