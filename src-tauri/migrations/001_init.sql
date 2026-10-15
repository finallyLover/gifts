-- ============================================================
-- 电子礼簿 初始化数据库结构（迁移 001）
-- 设计要点：
--   1. 全部金额使用 REAL 存储，CHECK 约束禁止负数；
--   2. 图片只存相对路径（相对 images/ 目录），物理文件独立管理；
--   3. 人员(persons) 为双向人情聚合主体，收礼/回礼均关联 person_id；
--   4. 礼簿记录保留单元格原图坐标(cell_x...)，支持溯源定位。
-- ============================================================

PRAGMA foreign_keys = ON;

-- ---------- 模块1：礼簿档案 ----------
CREATE TABLE IF NOT EXISTS archives (
    id            TEXT PRIMARY KEY,                 -- UUID
    name          TEXT NOT NULL,                    -- 档案名称（如：张三婚礼礼簿）
    banquet_type  TEXT NOT NULL DEFAULT '其他',     -- 宴席类型：婚礼/寿宴/乔迁/满月/其他
    event_date    TEXT,                             -- 举办日期 YYYY-MM-DD
    host_name     TEXT,                             -- 主人姓名
    host_contact  TEXT,                             -- 主人联系方式
    remark        TEXT,                             -- 备注
    status        TEXT NOT NULL DEFAULT 'active',   -- 状态：active=在用 / archived=已归档
    created_at    TEXT NOT NULL,                    -- 创建时间
    updated_at    TEXT NOT NULL,                    -- 更新时间
    archived_at   TEXT                              -- 归档时间
);

-- ---------- 模块1：礼簿分页（一张照片对应一页） ----------
CREATE TABLE IF NOT EXISTS ledger_pages (
    id             TEXT PRIMARY KEY,
    archive_id     TEXT NOT NULL,
    file_path      TEXT NOT NULL,                   -- 相对路径，如 {archive_id}/xxx.jpg
    file_name      TEXT,                            -- 原始文件名
    page_order     INTEGER NOT NULL DEFAULT 0,      -- 页码排序（从0开始）
    width          INTEGER,                         -- 原图宽度（像素）
    height         INTEGER,                         -- 原图高度（像素）
    segments_json  TEXT,                            -- 表格分割结果JSON：{rows,cols,col_roles}
    created_at     TEXT NOT NULL,
    FOREIGN KEY (archive_id) REFERENCES archives(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_pages_archive ON ledger_pages(archive_id);

-- ---------- 模块3：人员人情档案（以姓名为聚合主体） ----------
CREATE TABLE IF NOT EXISTS persons (
    id             TEXT PRIMARY KEY,
    name           TEXT NOT NULL UNIQUE,            -- 来宾姓名（唯一）
    pinyin         TEXT,                            -- 姓名拼音（可选，辅助检索）
    remark         TEXT,                            -- 备注
    return_status  TEXT NOT NULL DEFAULT 'pending', -- 回礼标记：pending=待回礼 / returned=已回礼
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL
);

-- ---------- 模块2：收礼记录（来自礼簿录入，即“别人随我的礼”） ----------
CREATE TABLE IF NOT EXISTS gift_records (
    id           TEXT PRIMARY KEY,
    archive_id   TEXT NOT NULL,
    page_id      TEXT,
    person_id    TEXT,
    guest_name   TEXT NOT NULL DEFAULT '',          -- 来宾姓名（冗余存储，便于历史查询）
    relation     TEXT,                              -- 关系（亲戚/朋友/同事等标签）
    amount       REAL NOT NULL DEFAULT 0 CHECK (amount >= 0),  -- 礼金金额，禁止负数
    gift         TEXT,                              -- 礼品名称
    remark       TEXT,                              -- 备注
    operator     TEXT,                              -- 录入人
    status       TEXT NOT NULL DEFAULT 'pending',   -- 核对状态：pending=待核对 / confirmed=已确认
    row_order    INTEGER NOT NULL DEFAULT 0,        -- 行序号（页面内行序）
    -- 溯源：该记录在原图中的单元格坐标
    cell_x       REAL,
    cell_y       REAL,
    cell_w       REAL,
    cell_h       REAL,
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL,
    FOREIGN KEY (archive_id) REFERENCES archives(id) ON DELETE CASCADE,
    FOREIGN KEY (page_id)    REFERENCES ledger_pages(id) ON DELETE SET NULL,
    FOREIGN KEY (person_id)  REFERENCES persons(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_records_archive ON gift_records(archive_id);
CREATE INDEX IF NOT EXISTS idx_records_page    ON gift_records(page_id);
CREATE INDEX IF NOT EXISTS idx_records_person  ON gift_records(person_id);
CREATE INDEX IF NOT EXISTS idx_records_name    ON gift_records(guest_name);

-- ---------- 模块3：回礼记录（手动新增，即“我随别人的礼”） ----------
CREATE TABLE IF NOT EXISTS return_gifts (
    id           TEXT PRIMARY KEY,
    person_id    TEXT,
    person_name  TEXT NOT NULL,                     -- 对方姓名（冗余）
    event_name   TEXT,                              -- 对方宴席名称
    event_date   TEXT,                              -- 宴席时间
    amount       REAL NOT NULL DEFAULT 0 CHECK (amount >= 0),  -- 我方随礼金额，禁止负数
    gift         TEXT,                              -- 礼品
    remark       TEXT,
    operator     TEXT,
    created_at   TEXT NOT NULL,
    FOREIGN KEY (person_id) REFERENCES persons(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_return_person ON return_gifts(person_id);
CREATE INDEX IF NOT EXISTS idx_return_name   ON return_gifts(person_name);

-- ---------- 全局键值设置 ----------
CREATE TABLE IF NOT EXISTS settings (
    key    TEXT PRIMARY KEY,
    value  TEXT
);
