// ============================================================
// 数据库层：SQLiteOpenHelper + 与桌面端完全一致的建表结构
//
// 关键约束（与桌面端保持一致）：
//   1. 全部金额 REAL，CHECK 禁止负数
//   2. 外键级联：删除档案 → 级联删除其记录
//   3. persons 由触发器自动跟随 guest_name / person_name 变更
//   4. 数据库文件保留原名 gifts.db，便于与桌面端通过坚果云互换
// ============================================================
package com.example.afigts.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

object AppDb {
    /** 数据库文件名，必须与桌面端一致（坚果云同步依赖） */
    const val DB_NAME = "gifts.db"

    /** user_version，对应桌面端迁移 004 */
    private const val DB_VERSION = 4

    private lateinit var appCtx: Context
    private var helper: Helper? = null
    private val lock = Any()

    /** 应用启动时调用一次 */
    fun init(context: Context) {
        synchronized(lock) {
            if (!::appCtx.isInitialized) appCtx = context.applicationContext
            if (helper == null) helper = Helper(appCtx)
        }
    }

    val db: SQLiteDatabase
        get() = synchronized(lock) {
            val h = helper ?: Helper(appCtx).also { helper = it }
            h.writableDatabase
        }

    /** 数据库文件（用于坚果云推送/拉取） */
    val dbFile: File get() = appCtx.getDatabasePath(DB_NAME)

    /**
     * 图片存储根目录（与电脑端 storage.rs::images_root 对齐）：
     *   电脑端：{app_data_dir}/images/
     *   Android：/data/data/{pkg}/files/images/
     *
     * 数据库 ledger_pages.file_path 存的是 "{archive_id}/{uuid}.jpg" 相对路径
     * （不带 images/ 前缀，见 storage.rs line 66）。
     * 拼完整路径用 File(imageBaseDir, file_path)。
     */
    val imageBaseDir: File
        get() = java.io.File(appCtx.filesDir, "images")

    /** 把 WAL 日志合并进主库文件（推送前必须调用） */
    fun checkpoint() {
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    }

    /** 拉取前关闭数据库，释放文件句柄（Android 不需要像 Windows 那样换文件锁） */
    fun closeForReplace() {
        synchronized(lock) {
            helper?.close()
            helper = null
        }
    }

    /** 替换文件后重新打开 */
    fun reopen() {
        synchronized(lock) {
            if (helper == null) helper = Helper(appCtx)
        }
    }

    // ---------- 建表 ----------
    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            SCHEMA.forEach { db.execSQL(it) }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 兼容从桌面端旧版本拉取过来的库
            if (oldVersion < 2) {
                addColumnIfMissing(db, "gift_records", "gift_amount")
                addColumnIfMissing(db, "return_gifts", "gift_amount")
            }
            if (oldVersion < 3) TRIGGERS.forEach { db.execSQL(it) }
            if (oldVersion < 4) db.execSQL(CREATE_SYNC_META)
        }

        private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String) {
            val exists = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                var found = false
                val idx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) {
                    if (c.getString(idx) == column) {
                        found = true
                        break
                    }
                }
                found
            }
            if (!exists) {
                db.execSQL(
                    "ALTER TABLE $table ADD COLUMN $column REAL NOT NULL DEFAULT 0 CHECK($column >= 0)"
                )
            }
        }
    }

    private const val CREATE_ARCHIVES = """
        CREATE TABLE IF NOT EXISTS archives (
            id            TEXT PRIMARY KEY,
            name          TEXT NOT NULL,
            banquet_type  TEXT NOT NULL DEFAULT '其他',
            event_date    TEXT,
            host_name     TEXT,
            host_contact  TEXT,
            remark        TEXT,
            status        TEXT NOT NULL DEFAULT 'active',
            created_at    TEXT NOT NULL,
            updated_at    TEXT NOT NULL,
            archived_at   TEXT
        )
    """

    private const val CREATE_PAGES = """
        CREATE TABLE IF NOT EXISTS ledger_pages (
            id             TEXT PRIMARY KEY,
            archive_id     TEXT NOT NULL,
            file_path      TEXT NOT NULL,
            file_name      TEXT,
            page_order     INTEGER NOT NULL DEFAULT 0,
            width          INTEGER,
            height         INTEGER,
            segments_json  TEXT,
            created_at     TEXT NOT NULL,
            FOREIGN KEY (archive_id) REFERENCES archives(id) ON DELETE CASCADE
        )
    """

    private const val CREATE_PERSONS = """
        CREATE TABLE IF NOT EXISTS persons (
            id             TEXT PRIMARY KEY,
            name           TEXT NOT NULL UNIQUE,
            pinyin         TEXT,
            remark         TEXT,
            return_status  TEXT NOT NULL DEFAULT 'pending',
            created_at     TEXT NOT NULL,
            updated_at     TEXT NOT NULL
        )
    """

    private const val CREATE_GIFT_RECORDS = """
        CREATE TABLE IF NOT EXISTS gift_records (
            id           TEXT PRIMARY KEY,
            archive_id   TEXT NOT NULL,
            page_id      TEXT,
            person_id    TEXT,
            guest_name   TEXT NOT NULL DEFAULT '',
            relation     TEXT,
            amount       REAL NOT NULL DEFAULT 0 CHECK (amount >= 0),
            gift         TEXT,
            gift_amount  REAL NOT NULL DEFAULT 0 CHECK (gift_amount >= 0),
            remark       TEXT,
            operator     TEXT,
            status       TEXT NOT NULL DEFAULT 'pending',
            row_order    INTEGER NOT NULL DEFAULT 0,
            cell_x       REAL,
            cell_y       REAL,
            cell_w       REAL,
            cell_h       REAL,
            created_at   TEXT NOT NULL,
            updated_at   TEXT NOT NULL,
            FOREIGN KEY (archive_id) REFERENCES archives(id) ON DELETE CASCADE,
            FOREIGN KEY (page_id)    REFERENCES ledger_pages(id) ON DELETE SET NULL,
            FOREIGN KEY (person_id)  REFERENCES persons(id) ON DELETE SET NULL
        )
    """

    private const val CREATE_RETURN_GIFTS = """
        CREATE TABLE IF NOT EXISTS return_gifts (
            id           TEXT PRIMARY KEY,
            person_id    TEXT,
            person_name  TEXT NOT NULL,
            event_name   TEXT,
            event_date   TEXT,
            amount       REAL NOT NULL DEFAULT 0 CHECK (amount >= 0),
            gift         TEXT,
            gift_amount  REAL NOT NULL DEFAULT 0 CHECK (gift_amount >= 0),
            remark       TEXT,
            operator     TEXT,
            created_at   TEXT NOT NULL,
            FOREIGN KEY (person_id) REFERENCES persons(id) ON DELETE SET NULL
        )
    """

    private const val CREATE_SETTINGS = """
        CREATE TABLE IF NOT EXISTS settings (
            key    TEXT PRIMARY KEY,
            value  TEXT
        )
    """

    private const val CREATE_SYNC_META = """
        CREATE TABLE IF NOT EXISTS sync_meta (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id    TEXT NOT NULL UNIQUE,
            cloud_etag   TEXT,
            cloud_mtime  TEXT,
            local_mtime  TEXT,
            last_sync_at TEXT,
            last_push_at TEXT,
            last_pull_at TEXT,
            cloud_path   TEXT
        )
    """

    private val INDEXES = listOf(
        "CREATE INDEX IF NOT EXISTS idx_pages_archive ON ledger_pages(archive_id)",
        "CREATE INDEX IF NOT EXISTS idx_records_archive ON gift_records(archive_id)",
        "CREATE INDEX IF NOT EXISTS idx_records_page    ON gift_records(page_id)",
        "CREATE INDEX IF NOT EXISTS idx_records_person  ON gift_records(person_id)",
        "CREATE INDEX IF NOT EXISTS idx_records_name    ON gift_records(guest_name)",
        "CREATE INDEX IF NOT EXISTS idx_return_person ON return_gifts(person_id)",
        "CREATE INDEX IF NOT EXISTS idx_return_name   ON return_gifts(person_name)"
    )

    /** 人员档案自动同步触发器（迁移 003） */
    private val TRIGGERS = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS tr_gift_records_person_insert
        AFTER INSERT ON gift_records
        WHEN NEW.guest_name IS NOT NULL AND trim(NEW.guest_name) != ''
        BEGIN
          INSERT OR IGNORE INTO persons (id, name, return_status, created_at, updated_at)
            VALUES (lower(hex(randomblob(16))), trim(NEW.guest_name), 'pending', datetime('now'), datetime('now'));
          UPDATE gift_records SET person_id = (SELECT id FROM persons WHERE name = trim(NEW.guest_name)) WHERE id = NEW.id;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS tr_gift_records_person_update
        AFTER UPDATE OF guest_name ON gift_records
        WHEN NEW.guest_name IS NOT NULL AND trim(NEW.guest_name) != ''
        BEGIN
          INSERT OR IGNORE INTO persons (id, name, return_status, created_at, updated_at)
            VALUES (lower(hex(randomblob(16))), trim(NEW.guest_name), 'pending', datetime('now'), datetime('now'));
          UPDATE gift_records SET person_id = (SELECT id FROM persons WHERE name = trim(NEW.guest_name)) WHERE id = NEW.id;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS tr_return_gifts_person_insert
        AFTER INSERT ON return_gifts
        WHEN NEW.person_name IS NOT NULL AND trim(NEW.person_name) != ''
        BEGIN
          INSERT OR IGNORE INTO persons (id, name, return_status, created_at, updated_at)
            VALUES (lower(hex(randomblob(16))), trim(NEW.person_name), 'pending', datetime('now'), datetime('now'));
          UPDATE return_gifts SET person_id = (SELECT id FROM persons WHERE name = trim(NEW.person_name)) WHERE id = NEW.id;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS tr_return_gifts_person_update
        AFTER UPDATE OF person_name ON return_gifts
        WHEN NEW.person_name IS NOT NULL AND trim(NEW.person_name) != ''
        BEGIN
          INSERT OR IGNORE INTO persons (id, name, return_status, created_at, updated_at)
            VALUES (lower(hex(randomblob(16))), trim(NEW.person_name), 'pending', datetime('now'), datetime('now'));
          UPDATE return_gifts SET person_id = (SELECT id FROM persons WHERE name = trim(NEW.person_name)) WHERE id = NEW.id;
        END
        """
    )

    private val SCHEMA: List<String> = listOf(
        CREATE_ARCHIVES,
        CREATE_PAGES,
        CREATE_PERSONS,
        CREATE_GIFT_RECORDS,
        CREATE_RETURN_GIFTS,
        CREATE_SETTINGS,
        CREATE_SYNC_META
    ) + INDEXES + TRIGGERS
}
