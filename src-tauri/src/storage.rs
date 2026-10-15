// ============================================================
// 本地数据存储：
//   桌面端：程序 exe 同目录（不占用 C 盘）
//     data/gifts.db       SQLite 数据库
//     images/{档案id}/    礼簿照片
//   Android 端：使用 Tauri 标准 app_data_dir（应用私有存储）
// ============================================================
use std::fs;
use std::path::{Path, PathBuf};
use tauri::AppHandle;
#[cfg(target_os = "android")]
use tauri::Manager;
use uuid::Uuid;

use crate::error::{AppError, AppResult};

/// 应用数据根目录：
///   - 桌面端：程序 exe 所在目录（方便绿色版便携）
///   - Android 端：Tauri 标准 app_data_dir（应用私有沙箱）
pub fn app_data_dir(app: &AppHandle) -> AppResult<PathBuf> {
    #[cfg(target_os = "android")]
    {
        // Android：使用 Tauri 提供的 app_data_dir（Context.getFilesDir()）
        app.path()
            .app_data_dir()
            .map_err(|e| AppError::Other(format!("无法定位应用数据目录：{}", e)))
    }

    #[cfg(not(target_os = "android"))]
    {
        let _ = app; // 桌面端使用 exe 路径，app 参数暂不需要
        // 桌面端：exe 所在目录（绿色版便携策略）
        let exe = std::env::current_exe()?;
        exe.parent()
            .map(|d| d.to_path_buf())
            .ok_or_else(|| AppError::Other("无法定位程序目录".to_string()))
    }
}

/// 图片根目录
pub fn images_root(app: &AppHandle) -> AppResult<PathBuf> {
    Ok(app_data_dir(app)?.join("images"))
}

/// 相对路径 → 本机绝对路径
pub fn resolve_path(app: &AppHandle, rel_path: &str) -> AppResult<PathBuf> {
    Ok(images_root(app)?.join(rel_path))
}

/// 导入单张图片到本档案目录，返回 (相对路径, 原始文件名)
pub fn import_image(app: &AppHandle, archive_id: &str, src: &Path) -> AppResult<(String, String)> {
    if !src.exists() {
        return Err(AppError::Other(format!("源文件不存在: {}", src.display())));
    }
    // 扩展名兜底为 jpg
    let ext = src
        .extension()
        .and_then(|e| e.to_str())
        .map(|s| s.to_lowercase())
        .unwrap_or_else(|| "jpg".to_string());
    let file_name = src
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("unknown")
        .to_string();
    let rel_path = format!("{}/{}.{}", archive_id, Uuid::new_v4(), ext);

    let dest_dir = images_root(app)?.join(archive_id);
    fs::create_dir_all(&dest_dir)?;
    let dest = images_root(app)?.join(&rel_path);
    fs::copy(src, &dest)?;
    Ok((rel_path, file_name))
}

/// 删除某档案的整个图片目录
pub fn remove_archive_images(app: &AppHandle, archive_id: &str) -> AppResult<()> {
    let dir = images_root(app)?.join(archive_id);
    if dir.exists() {
        fs::remove_dir_all(&dir)?;
    }
    Ok(())
}

/// 删除单张图片文件（文件不存在视为成功）
pub fn remove_image(app: &AppHandle, rel_path: &str) -> AppResult<()> {
    let p = resolve_path(app, rel_path)?;
    if p.exists() {
        fs::remove_file(p)?;
    }
    Ok(())
}
