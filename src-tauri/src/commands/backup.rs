// ============================================================
// 模块7：数据备份与恢复
//   backup_database：仅备份数据库（.db）
//   backup_full    ：全量备份（.giftbak = ZIP：db + images，可跨电脑恢复）
//   restore_backup ：自动识别 .db / .giftbak 并恢复
// ============================================================
use std::fs::{self, File};
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::time::Duration;

use rusqlite::Connection;
use tauri::{AppHandle, State};
use tauri_plugin_shell::ShellExt;
use walkdir::WalkDir;
use zip::{write::SimpleFileOptions, CompressionMethod, ZipArchive, ZipWriter};

use crate::db::DbState;
use crate::error::{AppError, AppResult};
use crate::models::AppInfo;
use crate::storage;

/// (数据根目录, 数据库文件路径)
fn app_paths(app: &AppHandle) -> AppResult<(PathBuf, PathBuf)> {
    let data_dir = storage::app_data_dir(app)?;
    let db_path = data_dir.join("data").join("gifts.db");
    Ok((data_dir, db_path))
}

/// 应用/数据信息（首页设置页展示）
#[tauri::command]
pub fn get_app_info(app: AppHandle) -> AppResult<AppInfo> {
    let (data_dir, db_path) = app_paths(&app)?;
    let db_size = fs::metadata(&db_path).map(|m| m.len()).unwrap_or(0);
    let mut image_count = 0u64;
    let images = storage::images_root(&app)?;
    if images.exists() {
        for e in WalkDir::new(&images).into_iter().flatten() {
            if e.file_type().is_file() {
                image_count += 1;
            }
        }
    }
    Ok(AppInfo {
        data_dir: data_dir.to_string_lossy().to_string(),
        db_path: db_path.to_string_lossy().to_string(),
        db_size,
        image_count,
    })
}

/// 打开数据所在目录（后续可替换为 tauri-plugin-opener）
#[allow(deprecated)]
#[tauri::command]
pub fn open_data_dir(app: AppHandle) -> AppResult<()> {
    let (data_dir, _) = app_paths(&app)?;
    app.shell()
        .open(data_dir.to_string_lossy().to_string(), None)?;
    Ok(())
}

/// 使用 SQLite 在线备份接口把活动库一致地复制到目标文件
fn backup_db_to(conn: &Connection, target: &Path) -> AppResult<()> {
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent)?;
    }
    // 目标文件已存在则删除，避免叠加
    if target.exists() {
        fs::remove_file(target)?;
    }
    let mut dst = Connection::open(target)?;
    {
        // backup 持有 dst 的可变借用且实现了 Drop，需用独立作用域确保先释放
        let backup = rusqlite::backup::Backup::new(conn, &mut dst)?;
        backup.run_to_completion(100, Duration::from_millis(50), None)?;
    }
    drop(dst); // 关闭确保落盘
    Ok(())
}

/// 仅备份数据库
#[tauri::command]
pub fn backup_database(
    state: State<DbState>,
    target_path: String,
) -> AppResult<String> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    let target = PathBuf::from(&target_path);
    backup_db_to(&conn, &target)?;
    Ok(target_path)
}

/// 全量备份：数据库 + 图片，打包为 ZIP（.giftbak），可拷贝到另一台电脑恢复
#[tauri::command]
pub fn backup_full(
    app: AppHandle,
    state: State<DbState>,
    target_path: String,
) -> AppResult<String> {
    // 1. 先把 DB 备份到临时文件
    let tmp_db = std::env::temp_dir().join(format!("gift-backup-{}.db", uuid::Uuid::new_v4()));
    {
        let conn = state.0.lock().map_err(crate::db::lock_err)?;
        backup_db_to(&conn, &tmp_db)?;
    }

    // 2. 创建 ZIP
    let file = File::create(&target_path)?;
    let mut zip = ZipWriter::new(file);
    let opts = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);

    zip.start_file("data/gifts.db", opts)?;
    io::copy(&mut File::open(&tmp_db)?, &mut zip)?;

    // 3. 追加全部图片（相对 images/ 的路径，保留 {archive_id} 层级）
    let root = storage::images_root(&app)?;
    if root.exists() {
        for entry in WalkDir::new(&root).into_iter().flatten() {
            if !entry.file_type().is_file() {
                continue;
            }
            let rel = entry.path().strip_prefix(&root).unwrap();
            let inner_name = format!("images/{}", rel.to_string_lossy().replace('\\', "/"));
            zip.start_file(inner_name, opts)?;
            io::copy(&mut File::open(entry.path())?, &mut zip)?;
        }
    }
    zip.finish()?;
    let _ = fs::remove_file(&tmp_db);
    Ok(target_path)
}

/// 恢复备份（自动识别 ZIP 全量包或单个 .db 文件）
#[tauri::command]
pub fn restore_backup(
    app: AppHandle,
    state: State<DbState>,
    source_path: String,
) -> AppResult<String> {
    let src = PathBuf::from(&source_path);
    if !src.exists() {
        return Err(AppError::Other("备份文件不存在".to_string()));
    }

    // 读取前4字节判断文件类型
    let mut magic = [0u8; 4];
    File::open(&src)?.read_exact(&mut magic)?;
    let is_zip = &magic == b"PK\x03\x04";

    let tmp_dir = std::env::temp_dir().join(format!("gift-restore-{}", uuid::Uuid::new_v4()));
    let db_file: PathBuf;

    if is_zip {
        fs::create_dir_all(&tmp_dir)?;
        let mut archive = ZipArchive::new(File::open(&src)?)?;
        archive.extract(&tmp_dir)?;
        db_file = tmp_dir.join("data").join("gifts.db");
    } else {
        db_file = src.clone();
    }

    if !db_file.exists() {
        return Err(AppError::Other("备份包中未找到数据库文件 data/gifts.db".to_string()));
    }

    // 校验备份库可打开且包含核心表
    let source_conn = Connection::open(&db_file)?;
    let has_table: i64 = source_conn.query_row(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='archives'",
        [],
        |r| r.get(0),
    )?;
    if has_table == 0 {
        return Err(AppError::Other("备份文件缺少必需的数据表，已取消恢复".to_string()));
    }

    // 通过在线备份接口，把备份库内容写入当前活动库
    {
        let mut conn = state.0.lock().map_err(crate::db::lock_err)?;
        {
            // Backup 实现 Drop，需在独立作用域内先行释放，再对 conn 做迁移
            let backup = rusqlite::backup::Backup::new(&source_conn, &mut conn)?;
            backup.run_to_completion(100, Duration::from_millis(50), None)?;
        }
        // 旧备份可能缺少新字段，迁移补全结构
        crate::db::migrate(&conn)?;
    }

    // 全量包：恢复图片到 images 目录（同名覆盖）
    if is_zip {
        let img_src = tmp_dir.join("images");
        if img_src.exists() {
            let img_dst = storage::images_root(&app)?;
            fs::create_dir_all(&img_dst)?;
            copy_dir_all(&img_src, &img_dst)?;
        }
    }

    let _ = fs::remove_dir_all(&tmp_dir);
    Ok("数据恢复完成".to_string())
}

/// 递归复制目录
fn copy_dir_all(src: &Path, dst: &Path) -> io::Result<()> {
    fs::create_dir_all(dst)?;
    for entry in fs::read_dir(src)? {
        let entry = entry?;
        let from = entry.path();
        let to = dst.join(entry.file_name());
        if from.is_dir() {
            copy_dir_all(&from, &to)?;
        } else {
            fs::copy(&from, &to)?;
        }
    }
    Ok(())
}

/// 清空全部数据：删除所有表数据与全部图片文件
/// 数据库结构保留；操作不可恢复，前端需做双重确认
#[tauri::command]
pub fn clear_all_data(app: AppHandle, state: State<DbState>) -> AppResult<()> {
    {
        let conn = state.0.lock().map_err(crate::db::lock_err)?;
        // 按外键依赖顺序清空业务表
        conn.execute_batch(
            "DELETE FROM gift_records;
             DELETE FROM return_gifts;
             DELETE FROM ledger_pages;
             DELETE FROM archives;
             DELETE FROM persons;",
        )?;
    }
    // 清空图片目录后重建空目录
    let images = storage::images_root(&app)?;
    if images.exists() {
        fs::remove_dir_all(&images)?;
    }
    fs::create_dir_all(&images)?;
    Ok(())
}
