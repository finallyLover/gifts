// ============================================================
// 模块1：礼簿档案管理命令
// ============================================================
use chrono::Local;
use rusqlite::{params, params_from_iter, Row};
use tauri::{AppHandle, State};

use crate::db::DbState;
use crate::error::AppResult;
use crate::models::{Archive, ArchiveInput};
use crate::storage;

/// 当前本地时间字符串
fn now_str() -> String {
    Local::now().format("%Y-%m-%d %H:%M:%S").to_string()
}

/// 行映射
fn row_to_archive(row: &Row) -> rusqlite::Result<Archive> {
    Ok(Archive {
        id: row.get("id")?,
        name: row.get("name")?,
        banquet_type: row.get("banquet_type")?,
        event_date: row.get("event_date")?,
        host_name: row.get("host_name")?,
        host_contact: row.get("host_contact")?,
        remark: row.get("remark")?,
        status: row.get("status")?,
        created_at: row.get("created_at")?,
        updated_at: row.get("updated_at")?,
        archived_at: row.get("archived_at")?,
    })
}

/// 档案列表（默认不包含已归档；支持名称模糊搜索）
#[tauri::command]
pub fn list_archives(
    state: State<DbState>,
    keyword: Option<String>,
    include_archived: Option<bool>,
) -> AppResult<Vec<Archive>> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    let mut sql = String::from(
        "SELECT * FROM archives WHERE 1=1",
    );
    let mut args: Vec<Box<dyn rusqlite::ToSql>> = Vec::new();
    if include_archived != Some(true) {
        sql.push_str(" AND status != 'archived'");
    }
    if let Some(kw) = keyword.filter(|s| !s.trim().is_empty()) {
        sql.push_str(" AND name LIKE ?");
        args.push(Box::new(format!("%{}%", kw)));
    }
    sql.push_str(" ORDER BY created_at DESC");
    let mut stmt = conn.prepare(&sql)?;
    let list = stmt
        .query_map(params_from_iter(args.iter()), row_to_archive)?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(list)
}

/// 新增档案
#[tauri::command]
pub fn create_archive(state: State<DbState>, input: ArchiveInput) -> AppResult<Archive> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    let id = uuid::Uuid::new_v4().to_string();
    let now = now_str();
    conn.execute(
        "INSERT INTO archives
            (id, name, banquet_type, event_date, host_name, host_contact, remark, status, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, 'active', ?, ?)",
        params![
            id,
            input.name,
            input.banquet_type,
            input.event_date,
            input.host_name,
            input.host_contact,
            input.remark,
            now,
            now
        ],
    )?;
    let archive = Archive {
        id,
        name: input.name,
        banquet_type: input.banquet_type,
        event_date: input.event_date,
        host_name: input.host_name,
        host_contact: input.host_contact,
        remark: input.remark,
        status: "active".to_string(),
        created_at: now.clone(),
        updated_at: now,
        archived_at: None,
    };
    Ok(archive)
}

/// 编辑档案
#[tauri::command]
pub fn update_archive(state: State<DbState>, input: ArchiveInput) -> AppResult<Archive> {
    let id = input
        .id
        .clone()
        .ok_or_else(|| crate::error::AppError::Other("缺少档案id".to_string()))?;
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    let affected = conn.execute(
        "UPDATE archives SET
            name=?, banquet_type=?, event_date=?, host_name=?, host_contact=?, remark=?, updated_at=?
         WHERE id=?",
        params![
            input.name,
            input.banquet_type,
            input.event_date,
            input.host_name,
            input.host_contact,
            input.remark,
            now_str(),
            id
        ],
    )?;
    if affected == 0 {
        return Err(crate::error::AppError::Other("档案不存在".to_string()));
    }
    // 查询返回最新数据
    conn.query_row("SELECT * FROM archives WHERE id=?", params![id], row_to_archive)
        .map_err(Into::into)
}

/// 归档 / 取消归档
#[tauri::command]
pub fn set_archive_status(
    state: State<DbState>,
    id: String,
    archived: bool,
) -> AppResult<()> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    if archived {
        conn.execute(
            "UPDATE archives SET status='archived', archived_at=?, updated_at=? WHERE id=?",
            params![now_str(), now_str(), id],
        )?;
    } else {
        conn.execute(
            "UPDATE archives SET status='active', archived_at=NULL, updated_at=? WHERE id=?",
            params![now_str(), id],
        )?;
    }
    Ok(())
}

/// 删除档案；keep_images=true 时保留原始图片文件
#[tauri::command]
pub fn delete_archive(
    app: AppHandle,
    state: State<DbState>,
    id: String,
    keep_images: bool,
) -> AppResult<()> {
    if !keep_images {
        storage::remove_archive_images(&app, &id)?;
    }
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    conn.execute("DELETE FROM archives WHERE id=?", params![id])?;
    Ok(())
}
