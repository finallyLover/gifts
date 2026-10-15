// ============================================================
// 系统文件选择对话框（Rust 侧调用 tauri-plugin-dialog）
// ============================================================
use std::sync::mpsc;
use std::time::Duration;

use tauri::AppHandle;
use tauri_plugin_dialog::DialogExt;

use crate::error::AppResult;

/// 选择一张或多张礼簿照片
#[tauri::command]
pub fn pick_image_files(app: AppHandle) -> AppResult<Vec<String>> {
    let (tx, rx) = mpsc::channel();
    app.dialog()
        .file()
        .set_title("选择纸质礼簿照片")
        .add_filter(
            "图片文件",
            &["jpg", "jpeg", "png", "bmp", "webp"],
        )
        .pick_files(move |paths| {
            let list: Vec<String> = paths
                .map(|l| {
                    l.iter()
                        .filter_map(|f| f.as_path().map(|p| p.to_string_lossy().to_string()))
                        .collect()
                })
                .unwrap_or_default();
            tx.send(list).ok();
        });
    // 最长等待1小时，避免对话框长时间打开时命令提前返回
    Ok(rx.recv_timeout(Duration::from_secs(3600)).unwrap_or_default())
}

/// 选择保存路径（target_path 由用户选定）
#[tauri::command]
pub fn pick_save_file(
    app: AppHandle,
    default_name: String,
    ext: String,
    desc: String,
) -> AppResult<Option<String>> {
    let (tx, rx) = mpsc::channel();
    app.dialog()
        .file()
        .set_title("保存到")
        .set_file_name(&default_name)
        .add_filter(&desc, std::slice::from_ref(&ext.as_str()))
        .save_file(move |path| {
            let p = path.and_then(|f| f.as_path().map(|x| x.to_string_lossy().to_string()));
            tx.send(p).ok();
        });
    Ok(rx.recv_timeout(Duration::from_secs(3600)).unwrap_or(None))
}

/// 选择待打开的文件
#[tauri::command]
pub fn pick_open_file(
    app: AppHandle,
    ext: String,
    desc: String,
) -> AppResult<Option<String>> {
    let (tx, rx) = mpsc::channel();
    app.dialog()
        .file()
        .set_title("选择文件")
        .add_filter(&desc, std::slice::from_ref(&ext.as_str()))
        .pick_file(move |path| {
            let p = path.and_then(|f| f.as_path().map(|x| x.to_string_lossy().to_string()));
            tx.send(p).ok();
        });
    Ok(rx.recv_timeout(Duration::from_secs(3600)).unwrap_or(None))
}
