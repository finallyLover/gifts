-- ============================================================
-- 迁移 004：坚果云同步基础设施
--   sync_meta 表：记录同步元数据，用于判断冲突与版本
--     cloud_etag  - 云端文件 ETag（用于检测云端变化）
--     last_sync_at - 上次同步时间
--     last_push_at - 上次推送时间
--     last_pull_at - 上次拉取时间
-- ============================================================

CREATE TABLE IF NOT EXISTS sync_meta (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id    TEXT NOT NULL UNIQUE,          -- 本设备唯一标识
    cloud_etag   TEXT,                          -- 云端 DB 文件 ETag（if-none-match / if-match）
    cloud_mtime  TEXT,                          -- 云端 DB 文件 mtime（RFC 822 时间）
    local_mtime  TEXT,                          -- 上次推送时的本地 DB mtime
    last_sync_at TEXT,                          -- 最近一次同步（任意方向）时间
    last_push_at TEXT,                          -- 最近一次推送时间
    last_pull_at TEXT,                          -- 最近一次拉取时间
    cloud_path   TEXT                           -- 坚果云端 DB 所在路径（如 /gift-ledger/gifts.db）
);

-- 首次使用 sync 时由应用初始化插入；迁移只建表不插数据。
