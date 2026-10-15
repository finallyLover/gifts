// ============================================================
// 数据库层：SQLite 连接管理 + 初始化迁移
// ============================================================
use rusqlite::Connection;
use std::fs;
use std::path::Path;
use std::sync::Mutex;

use crate::error::AppResult;

/// 全局共享数据库状态（Tauri managed state）
pub struct DbState(pub Mutex<Connection>);

/// 打开/创建数据库并执行建表迁移
pub fn init_db(db_path: &Path) -> AppResult<Connection> {
    if let Some(parent) = db_path.parent() {
        fs::create_dir_all(parent)?;
    }
    let conn = Connection::open(db_path)?;
    // 开启外键约束
    conn.pragma_update(None, "foreign_keys", true)?;
    // WAL 模式：崩溃恢复更安全，读写并发更好
    conn.pragma_update(None, "journal_mode", "WAL")?;

    // 按 user_version 顺序执行迁移（老库自动补跑新迁移）
    migrate(&conn)?;
    // 启动时清理孤儿人员档案：修正历史数据中"修改来宾姓名后残留的旧人员"
    cleanup_orphan_persons(&conn)?;
    Ok(conn)
}

/// 清理孤儿人员档案：既无收礼记录也无回礼记录的人员
/// 人员档案只会在录入记录时自动创建，故"零记录人员"必然是脏数据（改名/删记录后残留），可安全删除
pub fn cleanup_orphan_persons(conn: &Connection) -> AppResult<usize> {
    let n = conn.execute(
        "DELETE FROM persons
         WHERE NOT EXISTS (SELECT 1 FROM gift_records gr WHERE gr.person_id = persons.id)
           AND NOT EXISTS (SELECT 1 FROM return_gifts rg WHERE rg.person_id = persons.id)",
        [],
    )?;
    Ok(n)
}

/// 顺序执行迁移；备份恢复后也应调用，保证旧备份库结构升级到最新
pub fn migrate(conn: &Connection) -> AppResult<()> {
    let version: i64 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
    if version < 1 {
        // 001：初始建表（IF NOT EXISTS，幂等）
        conn.execute_batch(include_str!("../../migrations/001_init.sql"))?;
        conn.pragma_update(None, "user_version", 1)?;
    }
    if version < 2 {
        // 002：新增礼品金额字段
        conn.execute_batch(include_str!("../../migrations/002_gift_amount.sql"))?;
        conn.pragma_update(None, "user_version", 2)?;
    }
    if version < 3 {
        // 003：人员档案同步触发器（guest_name/person_name 变更自动重挂 person_id）
        conn.execute_batch(include_str!("../../migrations/003_person_sync_triggers.sql"))?;
        conn.pragma_update(None, "user_version", 3)?;
    }
    if version < 4 {
        // 004：坚果云同步基础设施
        conn.execute_batch(include_str!("../../migrations/004_sync.sql"))?;
        conn.pragma_update(None, "user_version", 4)?;
    }
    Ok(())
}

/// 获取连接的小工具：把锁中毒等错误统一转成字符串错误
#[allow(dead_code)]
pub fn lock_err<E: std::fmt::Display>(e: E) -> crate::error::AppError {
    crate::error::AppError::Other(e.to_string())
}
