// ============================================================
// 统一错误类型：命令返回给前端时序列化为纯字符串，方便 catch 处理
// ============================================================
use serde::Serialize;
use thiserror::Error;

#[derive(Error, Debug)]
pub enum AppError {
    #[error("数据库错误: {0}")]
    Db(#[from] rusqlite::Error),

    #[error("文件IO错误: {0}")]
    Io(#[from] std::io::Error),

    #[error("序列化错误: {0}")]
    Serde(#[from] serde_json::Error),

    #[error("ZIP压缩错误: {0}")]
    Zip(#[from] zip::result::ZipError),

    #[error("Tauri运行时错误: {0}")]
    Tauri(#[from] tauri::Error),

    #[error("Shell错误: {0}")]
    Shell(#[from] tauri_plugin_shell::Error),

    #[error("{0}")]
    Other(String),
}

/// 命令统一返回类型
pub type AppResult<T> = Result<T, AppError>;

// 序列化为 Display 文本（前端拿到的是字符串而非错误对象）
impl Serialize for AppError {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        serializer.serialize_str(&self.to_string())
    }
}
