// ============================================================
// 电子礼簿 —— Tauri 2 应用入口
// 模块划分：
//   db       本地 SQLite 连接与迁移
//   models   数据结构
//   storage  图片本地文件管理
//   commands 按业务模块拆分的 Tauri 命令
// ============================================================
mod commands;
mod db;
mod error;
mod models;
mod storage;
mod sync;

use std::path::Path;
use std::sync::Mutex;

use tauri::Manager;

use crate::db::DbState;

/// 应用启动入口
/// mobile_entry_point：Android/iOS 上由此生成 JNI 入口，
/// 缺少该属性会导致链接器 gc-sections 删除全部业务代码（空壳 .so）
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        // 官方插件：shell（打开目录）、dialog（文件选择）
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let handle = app.handle();

            // 1. 数据目录：程序 exe 同目录（data/gifts.db + images/），不占用 C 盘
            let data_dir = storage::app_data_dir(handle)?;
            // 旧版本数据在 %APPDATA%，首次启动自动迁移
            migrate_legacy_data(handle, &data_dir);
            std::fs::create_dir_all(data_dir.join("data"))?;
            std::fs::create_dir_all(data_dir.join("images"))?;

            // 2. 初始化 SQLite 数据库
            let db_path = data_dir.join("data").join("gifts.db");
            let conn = db::init_db(&db_path)?;
            app.manage(DbState(Mutex::new(conn)));

            Ok(())
        })
        // 注册全部命令（按模块分组）
        .invoke_handler(tauri::generate_handler![
            // 模块1：档案
            commands::archive::list_archives,
            commands::archive::create_archive,
            commands::archive::update_archive,
            commands::archive::set_archive_status,
            commands::archive::delete_archive,
            // 模块1：分页
            commands::page::list_pages,
            commands::page::import_pages,
            commands::page::reorder_pages,
            commands::page::delete_page,
            commands::page::save_page_layout,
            commands::page::rotate_page,
            // 模块2：收礼记录
            commands::record::list_records,
            commands::record::save_record,
            commands::record::save_records,
            commands::record::set_records_status,
            commands::record::delete_records,
            // 模块3：人情往来
            commands::person::list_person_summaries,
            commands::person::get_person_detail,
            commands::person::add_return_gift,
            commands::person::update_return_gift,
            commands::person::delete_return_gift,
            commands::person::set_person_status,
            // 模块4：检索
            commands::search::search_all,
            // 模块5：统计
            commands::stats::archive_stat,
            commands::stats::global_stat,
            commands::stats::comparison_report,
            // 模块6/7：文件对话框
            commands::dialog::pick_image_files,
            commands::dialog::pick_save_file,
            commands::dialog::pick_open_file,
            // 模块7：备份恢复
            commands::backup::get_app_info,
            commands::backup::open_data_dir,
            commands::backup::backup_database,
            commands::backup::backup_full,
            commands::backup::restore_backup,
            commands::backup::clear_all_data,
            // 模块8：坚果云同步
            commands::sync::configure_sync,
            commands::sync::get_sync_info,
            commands::sync::test_webdav,
            commands::sync::push_to_cloud,
            commands::sync::pull_from_cloud,
            commands::sync::sync_with_cloud,
        ])
        .run(tauri::generate_context!())
        .expect("电子礼簿启动失败");
}

/// 旧版本数据位于 %APPDATA%\com.gifts.ledger，首次启动时整体复制到 exe 同目录
/// （仅当新位置还没有数据库时才迁移，避免覆盖新数据）
fn migrate_legacy_data(handle: &tauri::AppHandle, new_root: &Path) {
    if new_root.join("data").join("gifts.db").exists() {
        return;
    }
    let Ok(old_root) = handle.path().app_data_dir() else {
        return;
    };
    if old_root == new_root || !old_root.join("data").join("gifts.db").exists() {
        return;
    }
    let _ = copy_dir_all(&old_root, new_root);
}

/// 递归复制目录
fn copy_dir_all(src: &Path, dst: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dst)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let target = dst.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_dir_all(&entry.path(), &target)?;
        } else {
            std::fs::copy(entry.path(), &target)?;
        }
    }
    Ok(())
}
