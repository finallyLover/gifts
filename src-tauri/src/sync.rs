// ============================================================
// 坚果云 WebDAV 数据同步模块
//
// 设计原则：
//   1. 非实时同步：手动触发 push / pull，避免并发冲突
//   2. 全量同步：直接同步 SQLite 文件 blob，简单可靠
//   3. 图片同步：遍历 images/ 目录逐个上传/下载
//   4. 冲突处理：比较云端/本地 mtime 或 ETag，较新者胜；
//      若无法自动判断则提示用户
//   5. 配置安全：应用密码存入 settings 表，仅本设备可见
//
// 坚果云 WebDAV 基础信息：
//   Server:   https://dav.jianguoyun.com/dav/
//   Auth:     Basic Auth (邮箱 + 第三方应用密码)
//   免费版限制: 每 30 分钟 600 次请求
// ============================================================

use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::Connection;
use tauri::AppHandle;
use uuid::Uuid;
use walkdir::WalkDir;

use crate::error::{AppError, AppResult};
use crate::storage;

// ---------- 常量 ----------
/// 坚果云 WebDAV 服务器根（末尾带 /）
pub const DEFAULT_DAV_URL: &str = "https://dav.jianguoyun.com/dav/";
/// 默认远程同步目录
pub const DEFAULT_REMOTE_DIR: &str = "gift-ledger";
/// 远程数据库文件名
pub const REMOTE_DB_NAME: &str = "gifts.db";
/// 远程图片目录名
pub const REMOTE_IMAGES_DIR: &str = "images";
/// settings 表中各配置项的 key
pub const SETTING_ACCOUNT: &str = "sync.account";
pub const SETTING_APP_PASS: &str = "sync.app_password";
pub const SETTING_DAV_URL: &str = "sync.dav_url";
pub const SETTING_REMOTE_DIR: &str = "sync.remote_dir";
pub const SETTING_DEVICE_ID: &str = "sync.device_id";

// ============================================================
// 同步配置结构体（前端配置后通过 Tauri 命令传入）
// ============================================================
#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
pub struct SyncConfig {
    /// 坚果云登录邮箱
    pub account: String,
    /// 第三方应用密码（非登录密码）
    pub app_password: String,
    /// WebDAV 服务器地址（默认 https://dav.jianguoyun.com/dav/）
    #[serde(default = "default_dav_url")]
    pub dav_url: String,
    /// 远程同步目录（默认 gift-ledger）
    #[serde(default = "default_remote_dir")]
    pub remote_dir: String,
}

fn default_dav_url() -> String {
    DEFAULT_DAV_URL.to_string()
}
fn default_remote_dir() -> String {
    DEFAULT_REMOTE_DIR.to_string()
}

impl SyncConfig {
    /// 拼接远程完整路径（dav_url + remote_dir），确保以 / 结尾
    pub fn remote_base(&self) -> String {
        let dav = self.dav_url.trim_end_matches('/');
        let dir = self.remote_dir.trim_matches('/');
        format!("{}/{}/", dav, dir)
    }

    pub fn remote_db_url(&self) -> String {
        format!("{}{}", self.remote_base(), REMOTE_DB_NAME)
    }

    pub fn remote_images_url(&self) -> String {
        format!("{}{}/", self.remote_base(), REMOTE_IMAGES_DIR)
    }
}

// ============================================================
// 配置持久化（存入 / 读取 settings 表）
// ============================================================

/// 从 settings 表加载配置（若未配置则返回 None）
pub fn load_config(conn: &Connection) -> AppResult<Option<SyncConfig>> {
    let account = get_setting(conn, SETTING_ACCOUNT)?;
    let app_pass = get_setting(conn, SETTING_APP_PASS)?;
    let dav_url = get_setting(conn, SETTING_DAV_URL)?.unwrap_or_else(default_dav_url);
    let remote_dir = get_setting(conn, SETTING_REMOTE_DIR)?.unwrap_or_else(default_remote_dir);

    let Some(account) = account else { return Ok(None) };
    let Some(app_pass) = app_pass else { return Ok(None) };

    Ok(Some(SyncConfig { account, app_password: app_pass, dav_url, remote_dir }))
}

/// 保存配置到 settings 表
pub fn save_config(conn: &Connection, cfg: &SyncConfig) -> AppResult<()> {
    set_setting(conn, SETTING_ACCOUNT, &cfg.account)?;
    set_setting(conn, SETTING_APP_PASS, &cfg.app_password)?;
    set_setting(conn, SETTING_DAV_URL, &cfg.dav_url)?;
    set_setting(conn, SETTING_REMOTE_DIR, &cfg.remote_dir)?;
    Ok(())
}

/// 获取或生成本设备唯一 ID（存在 settings 表中）
pub fn get_or_create_device_id(conn: &Connection) -> AppResult<String> {
    if let Some(id) = get_setting(conn, SETTING_DEVICE_ID)? {
        return Ok(id);
    }
    let new_id = format!("device-{}", Uuid::new_v4());
    set_setting(conn, SETTING_DEVICE_ID, &new_id)?;
    Ok(new_id)
}

fn get_setting(conn: &Connection, key: &str) -> AppResult<Option<String>> {
    let mut stmt = conn.prepare("SELECT value FROM settings WHERE key = ?1")?;
    let mut rows = stmt.query([key])?;
    if let Some(row) = rows.next()? {
        Ok(row.get::<_, String>(0).ok())
    } else {
        Ok(None)
    }
}

fn set_setting(conn: &Connection, key: &str, value: &str) -> AppResult<()> {
    conn.execute(
        "INSERT INTO settings(key, value) VALUES(?1, ?2)
         ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        rusqlite::params![key, value],
    )?;
    Ok(())
}

// ============================================================
// WebDAV 基础客户端
// ============================================================

pub struct WebdavClient {
    client: reqwest::Client,
    auth_header: String,
}

impl WebdavClient {
    pub fn new(account: &str, app_password: &str) -> Self {
        let auth_raw = format!("{}:{}", account, app_password);
        let auth_header = format!("Basic {}", base64::Engine::encode(&base64::engine::general_purpose::STANDARD, auth_raw.as_bytes()));
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(120))
            .build()
            .expect("构建 HTTP 客户端失败");
        Self { client, auth_header }
    }

    fn auth(&self) -> &str { &self.auth_header }

    /// 测试连接：对远程目录发 PROPFIND，期望 207（成功）或 404（目录不存在但账户有效）
    pub async fn test_connection(&self, remote_base: &str) -> AppResult<()> {
        let url = remote_base.trim_end_matches('/').to_string();
        let resp = self.client
            .request(reqwest::Method::from_bytes(b"PROPFIND").unwrap_or(reqwest::Method::GET), &url)
            .header("Authorization", self.auth())
            .header("Depth", "0")
            .send()
            .await
            .map_err(|e| AppError::Other(format!("网络请求失败：{}", e)))?;

        let status = resp.status();
        if status.is_success() {
            Ok(())
        } else if status == reqwest::StatusCode::NOT_FOUND {
            // 目录不存在但账户有效 —— 后续会自动创建
            Ok(())
        } else if status == reqwest::StatusCode::UNAUTHORIZED {
            Err(AppError::Other("账户或应用密码错误，请检查".into()))
        } else {
            Err(AppError::Other(format!("连接失败 (HTTP {})", status)))
        }
    }

    /// 创建目录（不存在则创建；已存在时服务端返回 405 Method Not Allowed）
    ///
    /// 注意：按 RFC 4918，MKCOL 返回 409 Conflict 表示「父目录不存在」，
    /// 不能当作「目录已存在」吞掉，否则后面 PUT 会继续 409 且难以定位。
    pub async fn mkdir(&self, dir_url: &str) -> AppResult<()> {
        let resp = self.client
            .request(reqwest::Method::from_bytes(b"MKCOL").unwrap_or(reqwest::Method::GET), dir_url)
            .header("Authorization", self.auth())
            .send()
            .await
            .map_err(|e| AppError::Other(format!("创建目录失败：{}", e)))?;

        let status = resp.status();
        if status.is_success() {
            Ok(())
        } else if status == reqwest::StatusCode::METHOD_NOT_ALLOWED {
            // 目录已存在
            Ok(())
        } else if status == reqwest::StatusCode::CONFLICT {
            Err(AppError::Other(format!("创建目录失败：父目录不存在 ({})", dir_url)))
        } else {
            Err(AppError::Other(format!("创建目录失败 (HTTP {})：{}", status, dir_url)))
        }
    }

    /// 上传文件（覆盖已存在）
    pub async fn put_file(&self, url: &str, data: Vec<u8>) -> AppResult<()> {
        let resp = self.client
            .put(url)
            .header("Authorization", self.auth())
            .body(data)
            .send()
            .await
            .map_err(|e| AppError::Other(format!("上传失败：{}", e)))?;

        if resp.status().is_success() || resp.status() == reqwest::StatusCode::CREATED || resp.status() == reqwest::StatusCode::NO_CONTENT {
            Ok(())
        } else if resp.status() == reqwest::StatusCode::CONFLICT {
            // 409 = 远程父目录不存在（图片的档案子目录未创建）
            Err(AppError::Other(format!(
                "上传失败 (HTTP 409 Conflict)：远程父目录不存在，请先确认目录已创建 ({})",
                url
            )))
        } else {
            Err(AppError::Other(format!("上传失败 (HTTP {})：{}", resp.status(), url)))
        }
    }

    /// 下载文件；返回 None 表示不存在
    pub async fn get_file(&self, url: &str) -> AppResult<Option<Vec<u8>>> {
        let resp = self.client
            .get(url)
            .header("Authorization", self.auth())
            .send()
            .await
            .map_err(|e| AppError::Other(format!("下载失败：{}", e)))?;

        if resp.status() == reqwest::StatusCode::NOT_FOUND {
            return Ok(None);
        }
        if !resp.status().is_success() {
            return Err(AppError::Other(format!("下载失败 (HTTP {})", resp.status())));
        }
        let bytes = resp.bytes().await.map_err(|e| AppError::Other(format!("读取响应失败：{}", e)))?;
        Ok(Some(bytes.to_vec()))
    }

    /// 远程文件是否存在（用 HEAD / PROPFIND 轻量检查）
    pub async fn file_exists(&self, url: &str) -> AppResult<bool> {
        let url_no_slash = url.trim_end_matches('/');
        let resp = self.client
            .request(reqwest::Method::from_bytes(b"PROPFIND").unwrap_or(reqwest::Method::GET), url_no_slash)
            .header("Authorization", self.auth())
            .header("Depth", "0")
            .send()
            .await
            .map_err(|e| AppError::Other(format!("检测文件失败：{}", e)))?;
        Ok(resp.status().is_success())
    }

    /// 删除远程文件
    #[allow(dead_code)]
    pub async fn delete_file(&self, url: &str) -> AppResult<()> {
        let resp = self.client
            .delete(url)
            .header("Authorization", self.auth())
            .send()
            .await
            .map_err(|e| AppError::Other(format!("删除失败：{}", e)))?;
        if resp.status().is_success() || resp.status() == reqwest::StatusCode::NO_CONTENT || resp.status() == reqwest::StatusCode::NOT_FOUND {
            Ok(())
        } else {
            Err(AppError::Other(format!("删除失败 (HTTP {})", resp.status())))
        }
    }
}

// ============================================================
// 同步结果结构体
// ============================================================
#[derive(Debug, Clone, serde::Serialize)]
pub struct SyncResult {
    pub success: bool,
    pub message: String,
    /// 图片上传/下载数量
    pub image_count: usize,
    /// 时间戳
    pub at: String,
}

// ============================================================
// 主同步入口（仅做文件系统与 WebDAV I/O，meta 更新由调用方负责）
// ============================================================

/// 把 WAL 日志合并进主数据库文件（推送/替换前必须调用，
/// 否则直接复制 gifts.db 会丢失 -wal 中尚未 checkpoint 的数据）
pub fn checkpoint_db(conn: &Connection) -> AppResult<()> {
    conn.query_row::<String, _, _>("PRAGMA wal_checkpoint(TRUNCATE)", [], |r| r.get(0))
        .ok();
    Ok(())
}

/// 本地数据库文件路径
pub fn local_db_path(app: &AppHandle) -> AppResult<std::path::PathBuf> {
    Ok(storage::app_data_dir(app)?.join("data").join("gifts.db"))
}

/// 推送本地数据到坚果云（全量覆盖）
/// 调用前应先 checkpoint_db()，确保 WAL 已合并
pub async fn push_all(app: &AppHandle, cfg: &SyncConfig) -> AppResult<SyncResult> {
    let client = WebdavClient::new(&cfg.account, &cfg.app_password);

    // 1. 确保远程目录存在
    let base = cfg.remote_base();
    client.mkdir(&base).await?;
    let img_dir = cfg.remote_images_url();
    client.mkdir(&img_dir).await?;

    // 2. 读取本地 DB
    let local_db = local_db_path(app)?;
    let db_bytes = std::fs::read(&local_db)
        .map_err(|e| AppError::Other(format!("读取本地数据库失败：{}", e)))?;

    // 3. 上传 DB
    let db_url = cfg.remote_db_url();
    client.put_file(&db_url, db_bytes).await?;

    // 4. 上传图片
    //
    // 注意：图片相对路径为 `{档案id}/{文件名}`，即存在一级子目录。
    // 坚果云对 PUT 到不存在的父目录返回 409 Conflict，因此每张图上传前
    // 必须逐级 MKCOL 其父目录；用 HashSet 缓存已建目录避免重复请求
    // （坚果云免费版有 600 次/30 分钟 的请求限制）。
    let images_root = storage::images_root(app)?;
    let mut img_count = 0usize;
    let mut ensured_dirs: std::collections::HashSet<String> = std::collections::HashSet::new();
    if images_root.exists() {
        for entry in WalkDir::new(&images_root).into_iter().flatten() {
            if !entry.file_type().is_file() { continue; }
            let rel = entry.path().strip_prefix(&images_root).unwrap();

            // 逐级创建远程父目录（images/{档案id}/...）
            if let Some(parent) = rel.parent() {
                let mut prefix = String::new();
                for seg in parent.components() {
                    let seg = seg.as_os_str().to_string_lossy().replace('\\', "/");
                    if seg.is_empty() || seg.as_str() == "." { continue; }
                    prefix.push_str(&seg);
                    prefix.push('/');
                    if ensured_dirs.insert(prefix.clone()) {
                        client.mkdir(&format!("{}{}", img_dir, prefix)).await?;
                    }
                }
            }

            let rel_str = rel.to_string_lossy().replace('\\', "/");
            let remote_url = format!("{}{}", img_dir, rel_str);
            let local_bytes = std::fs::read(entry.path())
                .map_err(|e| AppError::Other(format!("读取图片失败：{}", e)))?;
            client.put_file(&remote_url, local_bytes).await?;
            img_count += 1;
        }
    }

    let now = now_iso();
    Ok(SyncResult {
        success: true,
        message: format!("推送成功：{} 张图片", img_count),
        image_count: img_count,
        at: now,
    })
}

/// 拉取结果：数据库写入临时文件，由调用方在关闭活动连接后替换
pub struct PulledData {
    pub result: SyncResult,
    /// 下载好的数据库临时文件路径（调用方负责替换活动库并删除临时文件）
    pub tmp_db: std::path::PathBuf,
}

/// 从坚果云拉取数据：DB 下载到临时文件，图片下载到 images 目录。
/// 不触碰活动数据库文件，避免 Windows 文件锁与 WAL 损坏。
pub async fn pull_to_temp(app: &AppHandle, cfg: &SyncConfig) -> AppResult<PulledData> {
    let client = WebdavClient::new(&cfg.account, &cfg.app_password);

    // 1. 下载 DB 到临时文件
    let db_url = cfg.remote_db_url();
    let Some(db_bytes) = client.get_file(&db_url).await? else {
        return Err(AppError::Other("远程数据库不存在，请先在另一端推送数据".into()));
    };

    // 校验下载内容是合法 SQLite 文件
    if db_bytes.len() < 16 || &db_bytes[0..16] != b"SQLite format 3\0" {
        return Err(AppError::Other("远程数据库文件格式不正确，已中止拉取".into()));
    }

    let tmp_db = std::env::temp_dir().join(format!("gift-pull-{}.db", Uuid::new_v4()));
    std::fs::write(&tmp_db, &db_bytes)
        .map_err(|e| AppError::Other(format!("写入临时数据库失败：{}", e)))?;

    // 2. 下载图片
    let img_count = download_all_images(&client, cfg, app).await?;

    let now = now_iso();
    Ok(PulledData {
        result: SyncResult {
            success: true,
            message: format!("拉取成功：{} 张图片", img_count),
            image_count: img_count,
            at: now,
        },
        tmp_db,
    })
}

/// 全量同步：返回需要执行的动作（由命令层处理连接重连）
pub enum SyncAction {
    /// 直接推送（无连接替换需求）
    Push(SyncResult),
    /// 需要用临时文件替换本地库后重连
    Pull(PulledData),
}

/// 全量同步：检查云端是否存在，比较大小决定推/拉
pub async fn full_sync(app: &AppHandle, cfg: &SyncConfig) -> AppResult<SyncAction> {
    let client = WebdavClient::new(&cfg.account, &cfg.app_password);

    // 1. 确保远程目录存在
    let base = cfg.remote_base();
    client.mkdir(&base).await?;

    let db_url = cfg.remote_db_url();
    let remote_exists = client.file_exists(&db_url).await?;

    let local_db = local_db_path(app)?;
    let local_exists = local_db.exists();

    match (remote_exists, local_exists) {
        (false, false) => Err(AppError::Other("本地和远程都没有数据，无法同步".into())),
        (false, true) => Ok(SyncAction::Push(push_all(app, cfg).await?)),
        (true, false) => Ok(SyncAction::Pull(pull_to_temp(app, cfg).await?)),
        (true, true) => {
            let local_meta = std::fs::metadata(&local_db)?;
            let remote_bytes = client.get_file(&db_url).await?.ok_or_else(|| {
                AppError::Other("远程数据库突然消失".into())
            })?;
            let remote_size = remote_bytes.len();
            let local_size = local_meta.len() as usize;

            // 远程更大 → 拉取；否则推送（保守策略：同大小默认推送本设备数据）
            if remote_size > local_size {
                if remote_bytes.len() < 16 || &remote_bytes[0..16] != b"SQLite format 3\0" {
                    return Err(AppError::Other("远程数据库文件格式不正确，已中止同步".into()));
                }
                let tmp_db = std::env::temp_dir().join(format!("gift-pull-{}.db", Uuid::new_v4()));
                std::fs::write(&tmp_db, &remote_bytes)?;
                let img_count = download_all_images(&client, cfg, app).await?;
                let now = now_iso();
                Ok(SyncAction::Pull(PulledData {
                    result: SyncResult {
                        success: true,
                        message: format!("拉取成功（远程数据较新）：{} 张图片", img_count),
                        image_count: img_count,
                        at: now,
                    },
                    tmp_db,
                }))
            } else {
                Ok(SyncAction::Push(push_all(app, cfg).await?))
            }
        }
    }
}

/// 用下载好的临时数据库替换活动库文件（调用方必须已取出并关闭旧连接）。
/// 会删除旧的 -wal/-shm 文件并备份当前库。
pub fn replace_active_db(app: &AppHandle, tmp_db: &Path) -> AppResult<()> {
    let local_db = local_db_path(app)?;
    if let Some(parent) = local_db.parent() {
        std::fs::create_dir_all(parent)?;
    }
    // 备份旧库
    if local_db.exists() {
        let backup_path = local_db.with_extension("db.bak");
        let _ = std::fs::copy(&local_db, &backup_path);
    }
    // 删除 WAL 相关文件，避免与新库混用
    for suffix in ["-wal", "-shm"] {
        let p = std::path::PathBuf::from(format!("{}{}", local_db.display(), suffix));
        if p.exists() {
            let _ = std::fs::remove_file(&p);
        }
    }
    std::fs::copy(tmp_db, &local_db)
        .map_err(|e| AppError::Other(format!("替换数据库失败：{}", e)))?;
    let _ = std::fs::remove_file(tmp_db);
    Ok(())
}

// ============================================================
// 辅助：下载远程 images/ 下所有文件
//   坚果云 WebDAV 不支持一次性列出所有文件并分页；
//   我们采用"先 PROPFIND 列目录，再逐个下载"的策略。
//   但 PROPFIND 带 Depth=infinity 可能触发坚果云的 750 文件限制，
//   更稳妥做法是：在首次拉取时直接 ZIP 打包上传，这里暂用简化版。
//
//   当前策略：拉取时，如果云端 images/ 目录存在且本地 images/ 为空，
//   提示用户先推送到这台设备；否则不做图片同步（避免 WebDAV 请求过多）。
//   后续可优化为：推送前打包 ZIP → 上传单个 ZIP → 拉取时下载 ZIP 并解压。
// ============================================================
async fn download_all_images(
    client: &WebdavClient,
    cfg: &SyncConfig,
    app: &AppHandle,
) -> AppResult<usize> {
    let images_root = storage::images_root(app)?;
    std::fs::create_dir_all(&images_root)?;

    // 使用 PROPFIND Depth=1 列 images 目录下的子目录
    let img_base = cfg.remote_images_url();
    let img_base_no_slash = img_base.trim_end_matches('/');

    let propfind_url = if img_base_no_slash.is_empty() {
        cfg.dav_url.trim_end_matches('/').to_string()
    } else {
        img_base_no_slash.to_string()
    };

    let resp = client.client
        .request(reqwest::Method::from_bytes(b"PROPFIND").unwrap_or(reqwest::Method::GET), &propfind_url)
        .header("Authorization", client.auth())
        .header("Depth", "1")
        .header("Content-Type", "application/xml")
        .send()
        .await
        .map_err(|e| AppError::Other(format!("列出远程 images 目录失败：{}", e)))?;

    if !resp.status().is_success() {
        // 远程 images 目录不存在或无权限，跳过
        return Ok(0);
    }

    let body = resp.text().await.unwrap_or_default();
    let hrefs = parse_propfind_hrefs(&body, &img_base);

    let mut count = 0usize;
    for href in hrefs {
        // href 可能是目录（以 / 结尾）或文件
        if href.ends_with('/') {
            // 递归下载子目录
            count += download_subdir(client, &href, &images_root, &img_base).await?;
        } else {
            if let Some(bytes) = client.get_file(&href).await? {
                if let Some(rel) = strip_prefix(&href, &img_base) {
                    let local_path = images_root.join(&rel);
                    if let Some(parent) = local_path.parent() {
                        std::fs::create_dir_all(parent)?;
                    }
                    std::fs::write(&local_path, &bytes)?;
                    count += 1;
                }
            }
        }
    }

    Ok(count)
}

async fn download_subdir(
    client: &WebdavClient,
    start_dir: &str,
    images_root: &Path,
    img_base: &str,
) -> AppResult<usize> {
    // 用 Vec 作为目录栈，避免 async 递归
    let mut dir_stack = vec![start_dir.trim_end_matches('/').to_string()];
    let mut count = 0usize;

    while let Some(dir_href) = dir_stack.pop() {
        let resp = client.client
            .request(reqwest::Method::from_bytes(b"PROPFIND").unwrap_or(reqwest::Method::GET), &dir_href)
            .header("Authorization", client.auth())
            .header("Depth", "1")
            .send()
            .await
            .map_err(|e| AppError::Other(format!("列出子目录失败：{}", e)))?;

        if !resp.status().is_success() {
            continue;
        }

        let body = resp.text().await.unwrap_or_default();
        let hrefs = parse_propfind_hrefs(&body, &dir_href);

        for href in hrefs {
            let trimmed = href.trim_end_matches('/');
            if trimmed == dir_href || trimmed == dir_href.trim_end_matches('/') {
                continue; // 跳过当前目录自身
            }
            if href.ends_with('/') {
                dir_stack.push(trimmed.to_string());
            } else if let Some(bytes) = client.get_file(&href).await? {
                if let Some(rel) = strip_prefix(&href, img_base) {
                    let local_path = images_root.join(&rel);
                    if let Some(parent) = local_path.parent() {
                        std::fs::create_dir_all(parent)?;
                    }
                    std::fs::write(&local_path, &bytes)?;
                    count += 1;
                }
            }
        }
    }
    Ok(count)
}

/// 从 PROPFIND XML 响应中提取所有 href。
/// 坚果云返回形如 `<D:href>/dav/user/gift-ledger/images/x.jpg</D:href>`，
/// 命名空间前缀可能是 d:/D: 或无前缀。用大小写不敏感的字符串扫描，
/// 比手写正则状态机可靠，且不引入 XML 依赖。
fn parse_propfind_hrefs(xml: &str, base: &str) -> Vec<String> {
    let lower = xml.to_ascii_lowercase();
    let bytes_lower = lower.as_bytes();
    let mut result = Vec::new();
    let mut pos = 0usize;

    while pos < bytes_lower.len() {
        // 查找下一个 '<'，随后可能是命名空间前缀（字母/数字/冒号）+ 'href>'
        let Some(lt) = memchr(b'<', &bytes_lower[pos..]) else { break };
        let tag_start = pos + lt;
        let after_lt = tag_start + 1;

        // 扫描标签名直到 '>'，要求以 href 结尾（允许 d: / D: / 无前缀）
        let mut j = after_lt;
        while j < bytes_lower.len() && bytes_lower[j] != b'>' && bytes_lower[j] != b'/' && bytes_lower[j] != b' ' {
            j += 1;
        }
        let tag_name = &lower[after_lt..j.min(xml.len())];
        let is_href_tag = tag_name == "href" || tag_name.ends_with(":href");

        if is_href_tag && j < bytes_lower.len() && bytes_lower[j] == b'>' {
            let content_start = j + 1;
            // 内容直到下一个 '<'（闭合标签）
            if let Some(rel_end) = memchr(b'<', &bytes_lower[content_start..]) {
                let content_end = content_start + rel_end;
                let href = xml[content_start..content_end].trim();
                if !href.is_empty() {
                    let normalized = normalize_href(href, base);
                    result.push(normalized);
                }
                pos = content_end + 1;
                continue;
            }
        }
        pos = after_lt;
    }
    result
}

/// 极简字节查找（避免引入 memchr crate 的 API 差异；标准库 windows API 可用）
fn memchr(needle: u8, haystack: &[u8]) -> Option<usize> {
    haystack.iter().position(|&b| b == needle)
}

/// 把 PROPFIND 返回的 href 统一成完整 URL
fn normalize_href(href: &str, base: &str) -> String {
    if href.starts_with("http://") || href.starts_with("https://") {
        return href.to_string();
    }
    if href.starts_with('/') {
        // 相对路径（坚果云通常返回 /dav/...）：拼接 scheme + host。
        // 注意必须跳过 "://" 本身再找 '/'，否则会命中协议里的斜杠。
        if let Some(idx) = base.find("://") {
            let after_scheme = idx + 3;
            let host_end = base[after_scheme..]
                .find('/')
                .map(|i| after_scheme + i)
                .unwrap_or(base.len());
            return format!("{}{}", &base[..host_end], href);
        }
    }
    href.to_string()
}

fn strip_prefix(s: &str, prefix: &str) -> Option<String> {
    let s_clean = s.trim_end_matches('/');
    let p_clean = prefix.trim_end_matches('/');
    if let Some(rest) = s_clean.strip_prefix(p_clean) {
        let rest = rest.trim_start_matches('/');
        if rest.is_empty() {
            None
        } else {
            Some(rest.to_string())
        }
    } else {
        None
    }
}

fn now_iso() -> String {
    let duration = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let secs = duration.as_secs() as i64;
    // RFC3339-ish（用 chrono 更精确，但避免新依赖）
    use std::time::{SystemTime as ST};
    let dt = ST::UNIX_EPOCH + std::time::Duration::from_secs(secs as u64);
    let datetime: chrono::DateTime<chrono::Local> = dt.into();
    datetime.to_rfc3339()
}

/// 更新 sync_meta 表
pub fn update_sync_meta(conn: &Connection, kind: &str, at: &str) -> AppResult<()> {
    // 确保 device_id 存在
    let _ = get_or_create_device_id(conn)?;

    let exists: i64 = conn.query_row(
        "SELECT COUNT(*) FROM sync_meta LIMIT 1",
        [],
        |r| r.get(0),
    ).unwrap_or(0);

    if exists == 0 {
        let device_id = get_or_create_device_id(conn)?;
        conn.execute(
            "INSERT INTO sync_meta(device_id, last_sync_at) VALUES(?1, ?2)",
            rusqlite::params![device_id, at],
        )?;
    } else {
        // 正常只有一行；更新 id 最大的那行，避免硬编码 id=1
        let set_field = match kind {
            "push" => "last_push_at = ?2, last_sync_at = ?2",
            "pull" => "last_pull_at = ?2, last_sync_at = ?2",
            _ => "last_sync_at = ?2",
        };
        let sql = format!(
            "UPDATE sync_meta SET {} WHERE id = (SELECT MAX(id) FROM sync_meta)",
            set_field
        );
        conn.execute(&sql, rusqlite::params![kind, at])?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_propfind_jianguoyun_style() {
        // 模拟坚果云常见 PROPFIND 响应（D: 前缀，相对路径）
        let xml = r#"<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/dav/user%40mail.com/gift-ledger/images/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/user%40mail.com/gift-ledger/images/abc/uuid1.jpg</D:href>
    <D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat>
  </D:response>
  <d:response xmlns:d="DAV:">
    <d:href>https://dav.jianguoyun.com/dav/gift-ledger/images/uuid2.jpg</d:href>
  </d:response>
</D:multistatus>"#;
        let base = "https://dav.jianguoyun.com/dav/user%40mail.com/gift-ledger/images/";
        let hrefs = parse_propfind_hrefs(xml, base);

        assert_eq!(hrefs.len(), 3, "应解析出 3 个 href，实际: {:?}", hrefs);
        assert!(hrefs[0].ends_with("images/"));
        assert!(hrefs[0].starts_with("https://dav.jianguoyun.com/"));
        assert!(hrefs[1].ends_with("uuid1.jpg"));
        // 完整 URL 保持原样
        assert_eq!(hrefs[2], "https://dav.jianguoyun.com/dav/gift-ledger/images/uuid2.jpg");
    }

    #[test]
    fn test_remote_path_building() {
        let cfg = SyncConfig {
            account: "a@b.com".into(),
            app_password: "pwd".into(),
            dav_url: "https://dav.jianguoyun.com/dav/".into(),
            remote_dir: "gift-ledger".into(),
        };
        assert_eq!(
            cfg.remote_db_url(),
            "https://dav.jianguoyun.com/dav/gift-ledger/gifts.db"
        );
        assert_eq!(
            cfg.remote_images_url(),
            "https://dav.jianguoyun.com/dav/gift-ledger/images/"
        );

        // 用户填写带斜杠的目录也应正常拼接
        let cfg2 = SyncConfig {
            remote_dir: "/gift-ledger/".into(),
            ..cfg
        };
        assert_eq!(
            cfg2.remote_db_url(),
            "https://dav.jianguoyun.com/dav/gift-ledger/gifts.db"
        );
    }

    #[test]
    fn test_strip_prefix_logic() {
        let base = "https://dav.jianguoyun.com/dav/gift-ledger/images/";
        assert_eq!(
            strip_prefix("https://dav.jianguoyun.com/dav/gift-ledger/images/a/b.jpg", base),
            Some("a/b.jpg".to_string())
        );
        assert_eq!(
            strip_prefix("https://dav.jianguoyun.com/dav/other/x.jpg", base),
            None
        );
    }
}
