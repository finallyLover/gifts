// ============================================================
// 模块1/2：礼簿分页（图片页）管理命令
// ============================================================
use std::path::PathBuf;

use chrono::Local;
use rusqlite::{params, Row};
use tauri::{AppHandle, State};

use crate::db::DbState;
use crate::error::{AppError, AppResult};
use crate::models::{Page, PageLayout};
use crate::storage;

fn now_str() -> String {
    Local::now().format("%Y-%m-%d %H:%M:%S").to_string()
}

/// 行映射 + 补绝对路径
fn row_to_page(row: &Row, app: &AppHandle) -> rusqlite::Result<Page> {
    let file_path: String = row.get("file_path")?;
    let abs_path = storage::resolve_path(app, &file_path)
        .ok()
        .map(|p| p.to_string_lossy().to_string());
    Ok(Page {
        id: row.get("id")?,
        archive_id: row.get("archive_id")?,
        file_path,
        file_name: row.get("file_name")?,
        page_order: row.get("page_order")?,
        width: row.get("width")?,
        height: row.get("height")?,
        segments_json: row.get("segments_json")?,
        created_at: row.get("created_at")?,
        abs_path,
    })
}

/// 查询某档案的全部页面（按页序）
#[tauri::command]
pub fn list_pages(app: AppHandle, state: State<DbState>, archive_id: String) -> AppResult<Vec<Page>> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    let mut stmt = conn.prepare(
        "SELECT * FROM ledger_pages WHERE archive_id=? ORDER BY page_order ASC",
    )?;
    let list = stmt
        .query_map(params![archive_id], |r| row_to_page(r, &app))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(list)
}

/// 批量导入图片页，自动追加到页尾
#[tauri::command]
pub fn import_pages(
    app: AppHandle,
    state: State<DbState>,
    archive_id: String,
    paths: Vec<String>,
) -> AppResult<Vec<Page>> {
    if paths.is_empty() {
        return Err(AppError::Other("未选择任何图片".to_string()));
    }
    // 预先校验档案存在
    {
        let conn = state.0.lock().map_err(crate::db::lock_err)?;
        let exists: i64 = conn.query_row(
            "SELECT COUNT(*) FROM archives WHERE id=?",
            params![archive_id],
            |r| r.get(0),
        )?;
        if exists == 0 {
            return Err(AppError::Other("礼簿档案不存在".to_string()));
        }
    }

    let mut inserted = Vec::new();
    for src_str in paths {
        let src = PathBuf::from(&src_str);
        // 复制图片到本地目录
        let (rel_path, file_name) = storage::import_image(&app, &archive_id, &src)?;
        let conn = state.0.lock().map_err(crate::db::lock_err)?;
        // 当前最大页序 +1
        let next_order: i64 = conn.query_row(
            "SELECT COALESCE(MAX(page_order), -1) + 1 FROM ledger_pages WHERE archive_id=?",
            params![archive_id],
            |r| r.get(0),
        )?;
        let id = uuid::Uuid::new_v4().to_string();
        let now = now_str();
        conn.execute(
            "INSERT INTO ledger_pages
                (id, archive_id, file_path, file_name, page_order, created_at)
             VALUES (?, ?, ?, ?, ?, ?)",
            params![id, archive_id, rel_path, file_name, next_order, now],
        )?;
        inserted.push(id);
    }
    // 返回最新页面列表
    list_pages(app, state, archive_id)
}

/// 调整页面顺序：传入按新顺序排列的页面id
#[tauri::command]
pub fn reorder_pages(state: State<DbState>, page_ids: Vec<String>) -> AppResult<()> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    for (i, id) in page_ids.iter().enumerate() {
        conn.execute(
            "UPDATE ledger_pages SET page_order=? WHERE id=?",
            params![i as i64, id],
        )?;
    }
    Ok(())
}

/// 删除页面（同步删除图片文件；关联记录的 page_id 置空）
#[tauri::command]
pub fn delete_page(app: AppHandle, state: State<DbState>, page_id: String) -> AppResult<()> {
    let file_path: String = {
        let conn = state.0.lock().map_err(crate::db::lock_err)?;
        conn.query_row(
            "SELECT file_path FROM ledger_pages WHERE id=?",
            params![page_id],
            |r| r.get(0),
        )?
    };
    storage::remove_image(&app, &file_path)?;
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    conn.execute("DELETE FROM ledger_pages WHERE id=?", params![page_id])?;
    Ok(())
}

/// 保存表格分割布局（手动调整后的 rows/cols/col_roles）
#[tauri::command]
pub fn save_page_layout(
    state: State<DbState>,
    page_id: String,
    layout: PageLayout,
) -> AppResult<()> {
    let json = serde_json::to_string(&layout)?;
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    conn.execute(
        "UPDATE ledger_pages SET segments_json=? WHERE id=?",
        params![json, page_id],
    )?;
    Ok(())
}

/// 旋转页面图片（angle：90 顺时针 / -90 逆时针）
/// 说明：旋转直接写入图片文件，保证原图方向正确，由 Rust 本地完成；
/// 旋转后宽高交换，旧分割坐标失效，一并清空分割布局。
#[tauri::command]
pub fn rotate_page(
    app: AppHandle,
    state: State<DbState>,
    page_id: String,
    angle: i32,
) -> AppResult<Page> {
    // 取页面图片绝对路径
    let abs = {
        let conn = state.0.lock().map_err(crate::db::lock_err)?;
        let rel: String = conn.query_row(
            "SELECT file_path FROM ledger_pages WHERE id=?",
            params![page_id],
            |r| r.get(0),
        )?;
        storage::resolve_path(&app, &rel)?
    };

    // 本地旋转：仅支持 90 的倍数（-90 归一化为 270，即逆时针 90°）
    let img = image::open(&abs).map_err(|e| AppError::Other(format!("读取图片失败: {e}")))?;
    let rotated = match angle.rem_euclid(360) {
        90 => img.rotate90(),
        180 => img.rotate180(),
        270 => img.rotate270(),
        other => return Err(AppError::Other(format!("不支持的旋转角度: {other}"))),
    };
    rotated.save(&abs).map_err(|e| AppError::Other(format!("保存图片失败: {e}")))?;
    let width = rotated.width() as i64;
    let height = rotated.height() as i64;

    // 更新页面宽高，并清空已失效的分割布局
    {
        let conn = state.0.lock().map_err(crate::db::lock_err)?;
        conn.execute(
            "UPDATE ledger_pages SET width=?, height=?, segments_json=NULL WHERE id=?",
            params![width, height, page_id],
        )?;
    }

    // 返回更新后的页面信息
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    let page = conn.query_row(
        "SELECT * FROM ledger_pages WHERE id=?",
        params![page_id],
        |r| row_to_page(r, &app),
    )?;
    Ok(page)
}
