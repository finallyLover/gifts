// ============================================================
// 模块4：全局检索（跨所有礼簿档案，按人名模糊匹配）
// ============================================================
use rusqlite::params;
use tauri::State;

use crate::db::DbState;
use crate::error::AppResult;
use crate::models::{PersonMatch, ReturnGift, SearchResult};

#[tauri::command]
pub fn search_all(state: State<DbState>, keyword: String) -> AppResult<SearchResult> {
    let kw = keyword.trim();
    if kw.is_empty() {
        return Ok(SearchResult::default());
    }
    let like = format!("%{}%", kw);
    let conn = state.0.lock().map_err(crate::db::lock_err)?;

    // 1. 匹配人员
    let mut stmt_p = conn.prepare("SELECT id, name FROM persons WHERE name LIKE ?")?;
    let persons = stmt_p
        .query_map(params![like], |r| {
            Ok(PersonMatch {
                id: r.get(0)?,
                name: r.get(1)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;

    // 2. 收礼记录（跨档案，附档案名用于跳转）
    let mut stmt_in = conn.prepare(
        r#"SELECT gr.*, a.name AS archive_name
           FROM gift_records gr LEFT JOIN archives a ON gr.archive_id=a.id
           WHERE gr.guest_name LIKE ?
           ORDER BY gr.created_at DESC"#,
    )?;
    let incoming = stmt_in
        .query_map(params![like], |row| {
            let mut rec = crate::commands::record::row_to_record(row)?;
            rec.archive_name = row.get("archive_name")?;
            Ok(rec)
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;

    // 3. 回礼记录
    let mut stmt_out = conn.prepare(
        "SELECT * FROM return_gifts WHERE person_name LIKE ? ORDER BY created_at DESC",
    )?;
    let outgoing = stmt_out
        .query_map(params![like], |row| {
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
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;

    Ok(SearchResult {
        persons,
        incoming,
        outgoing,
    })
}
