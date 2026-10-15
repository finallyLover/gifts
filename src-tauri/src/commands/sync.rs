// ============================================================
// 模块8：坚果云同步相关 Tauri 命令
//
// 关键设计（保证 SQLite/WAL 数据安全）：
//   - 推送前：PRAGMA wal_checkpoint(TRUNCATE) 把 WAL 合并进主库
//   - 拉取时：先下载到临时文件，校验 SQLite 头；随后在 DB 锁内
//     取出并关闭旧连接 → 替换 db 文件（删除 -wal/-shm）→ 重新打开连接
//   - 异步 I/O 期间绝不持有 DB 锁（rusqlite Connection 不是 Send）
// ============================================================
use std::mem::replace;

use rusqlite::Connection;
use tauri::{AppHandle, State};

use crate::db::DbState;
use crate::error::{AppError, AppResult};
use crate::sync::{self, SyncConfig, SyncResult};

// ---------- 配置 ----------

/// 保存坚果云同步配置（account / app_password / dav_url / remote_dir）
#[tauri::command]
pub fn configure_sync(
    state: State<DbState>,
    config: SyncConfig,
) -> AppResult<()> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    sync::save_config(&conn, &config)?;
    sync::get_or_create_device_id(&conn)?;
    Ok(())
}

/// 查询当前坚果云配置状态（是否已配置、设备 ID、上次同步时间等）
#[tauri::command]
pub fn get_sync_info(
    state: State<DbState>,
) -> AppResult<SyncInfo> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    let has_config = sync::load_config(&conn)?.is_some();
    let device_id = sync::get_or_create_device_id(&conn)?;

    let last_sync_at = query_meta_field(&conn, "last_sync_at");
    let last_push_at = query_meta_field(&conn, "last_push_at");
    let last_pull_at = query_meta_field(&conn, "last_pull_at");

    Ok(SyncInfo {
        has_config,
        device_id,
        last_sync_at,
        last_push_at,
        last_pull_at,
    })
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct SyncInfo {
    pub has_config: bool,
    pub device_id: String,
    pub last_sync_at: Option<String>,
    pub last_push_at: Option<String>,
    pub last_pull_at: Option<String>,
}

fn query_meta_field(conn: &Connection, field: &str) -> Option<String> {
    let sql = format!("SELECT {} FROM sync_meta LIMIT 1", field);
    conn.query_row(&sql, [], |r| r.get::<_, String>(0)).ok()
}

// ---------- 测试连接 ----------

/// 测试坚果云 WebDAV 连接（异步）
#[tauri::command]
pub async fn test_webdav(
    state: State<'_, DbState>,
    config: Option<SyncConfig>,
) -> AppResult<String> {
    let cfg = match config {
        Some(c) => c,
        None => {
            let conn = state.0.lock().map_err(crate::db::lock_err)?;
            sync::load_config(&conn)?.ok_or_else(|| {
                AppError::Other("尚未配置坚果云账号".into())
            })?
        }
    };
    let client = sync::WebdavClient::new(&cfg.account, &cfg.app_password);
    client.test_connection(&cfg.remote_base()).await?;
    Ok("连接成功".into())
}

// ---------- 同步操作 ----------

fn load_cfg(conn: &Connection) -> AppResult<SyncConfig> {
    sync::load_config(conn)?.ok_or_else(|| {
        AppError::Other("尚未配置坚果云账号，请先设置".into())
    })
}

/// 推送到坚果云
#[tauri::command]
pub async fn push_to_cloud(
    app: AppHandle,
    state: State<'_, DbState>,
) -> AppResult<SyncResult> {
    // 1. 取配置 + checkpoint（确保 WAL 已写入主库文件）
    {
        let conn = state.0.lock().map_err(crate::db::lock_err)?;
        sync::checkpoint_db(&conn)?;
    }
    // 2. 取配置
    let cfg = {
        let conn = state.0.lock().map_err(crate::db::lock_err)?;
        load_cfg(&conn)?
    };
    // 3. 异步推送（期间不持有 DB 锁）
    let result = sync::push_all(&app, &cfg).await?;
    // 4. 写 meta
    {
        let conn = state.0.lock().map_err(crate::db::lock_err)?;
        sync::update_sync_meta(&conn, "push", &result.at)?;
    }
    Ok(result)
}

/// 从坚果云拉取
#[tauri::command]
pub async fn pull_from_cloud(
    app: AppHandle,
    state: State<'_, DbState>,
) -> AppResult<SyncResult> {
    // 1. 取配置
    let cfg = {
        let conn = state.0.lock().map_err(crate::db::lock_err)?;
        load_cfg(&conn)?
    };
    // 2. 下载到临时文件（不触碰活动库）
    let pulled = sync::pull_to_temp(&app, &cfg).await?;

    // 3. 在 DB 锁内替换数据库并重连
    {
        let mut guard = state.0.lock().map_err(crate::db::lock_err)?;
        // 取出旧连接并 drop（关闭文件句柄，释放 Windows 文件锁）
        let old = replace(&mut *guard, Connection::open_in_memory()?);
        drop(old);

        // 替换文件（内部会备份旧库、删除 -wal/-shm）
        if let Err(e) = sync::replace_active_db(&app, &pulled.tmp_db) {
            // 替换失败：尝试重新打开原库，避免应用处于无连接状态
            if let Ok(db_path) = sync::local_db_path(&app) {
                if let Ok(conn) = crate::db::init_db(&db_path) {
                    *guard = conn;
                }
            }
            return Err(e);
        }

        // 重新打开并迁移
        let db_path = sync::local_db_path(&app)?;
        let new_conn = crate::db::init_db(&db_path)?;
        *guard = new_conn;

        // 远程库带来的是另一台设备的 device_id，重置为本机 ID
        reset_local_device_id(&guard)?;
        sync::update_sync_meta(&guard, "pull", &pulled.result.at)?;
    }

    Ok(pulled.result)
}

/// 全量同步（自动决定推/拉；拉取时安全重连）
#[tauri::command]
pub async fn sync_with_cloud(
    app: AppHandle,
    state: State<'_, DbState>,
) -> AppResult<SyncResult> {
    // 1. checkpoint + 取配置
    {
        let conn = state.0.lock().map_err(crate::db::lock_err)?;
        sync::checkpoint_db(&conn)?;
    }
    let cfg = {
        let conn = state.0.lock().map_err(crate::db::lock_err)?;
        load_cfg(&conn)?
    };
    // 2. 执行同步决策
    let action = sync::full_sync(&app, &cfg).await?;

    match action {
        sync::SyncAction::Push(result) => {
            let conn = state.0.lock().map_err(crate::db::lock_err)?;
            sync::update_sync_meta(&conn, "push", &result.at)?;
            Ok(result)
        }
        sync::SyncAction::Pull(pulled) => {
            let mut guard = state.0.lock().map_err(crate::db::lock_err)?;
            let old = replace(&mut *guard, Connection::open_in_memory()?);
            drop(old);

            if let Err(e) = sync::replace_active_db(&app, &pulled.tmp_db) {
                if let Ok(db_path) = sync::local_db_path(&app) {
                    if let Ok(conn) = crate::db::init_db(&db_path) {
                        *guard = conn;
                    }
                }
                return Err(e);
            }

            let db_path = sync::local_db_path(&app)?;
            let new_conn = crate::db::init_db(&db_path)?;
            *guard = new_conn;

            reset_local_device_id(&guard)?;
            sync::update_sync_meta(&guard, "pull", &pulled.result.at)?;
            Ok(pulled.result)
        }
    }
}

/// 拉取后重置本机设备 ID（远程库中的 ID 属于另一台设备）
fn reset_local_device_id(conn: &Connection) -> AppResult<()> {
    conn.execute("DELETE FROM settings WHERE key = 'sync.device_id'", [])?;
    sync::get_or_create_device_id(conn)?;
    Ok(())
}
