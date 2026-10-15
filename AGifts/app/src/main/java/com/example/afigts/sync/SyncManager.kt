// ============================================================
// 坚果云同步管理器（与桌面端 sync.rs / commands/sync.rs 行为一致）
//
//   push  ：checkpoint(WAL合并) → 上传 gifts.db → 上传 images/
//   pull  ：下载 gifts.db → 校验 SQLite 头 → 关闭库 → 备份 → 替换 → 重开
//           → 下载 images/ 下所有图片
//   smartSync：比较云端/本地 DB 文件大小，较大者胜（与桌面端 full_sync 一致）
//              图片总是双向同步（push 时上传本地新图，pull 时下载远程新图）
//
//   images/ 远程路径约定：{remoteBase}/images/{archive_id}/{uuid}.jpg
//   images/ 本地路径约定：{AppDb.imageBaseDir}/{archive_id}/{uuid}.jpg
// ============================================================
package com.example.afigts.sync

import com.example.afigts.data.AppDb
import com.example.afigts.data.SyncConfig
import com.example.afigts.data.SyncInfo
import com.example.afigts.data.SyncResult
import com.example.afigts.util.nowStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object SyncManager {

    const val DEFAULT_DAV_URL = "https://dav.jianguoyun.com/dav/"
    const val DEFAULT_REMOTE_DIR = "gift-ledger"
    const val REMOTE_DB_NAME = "gifts.db"
    const val REMOTE_IMAGES_DIR = "images"

    private const val KEY_ACCOUNT = "sync.account"
    private const val KEY_APP_PASS = "sync.app_password"
    private const val KEY_DAV_URL = "sync.dav_url"
    private const val KEY_REMOTE_DIR = "sync.remote_dir"
    private const val KEY_DEVICE_ID = "sync.device_id"

    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * 兜底建表：Android 从电脑端拉取过来的库如果 version 恰好等于本地 DB_VERSION，
     * SQLiteOpenHelper.onUpgrade 不会触发，settings / sync_meta 表可能缺失。
     * 所有读写方法入口统一先确保表存在。
     */
    private fun ensureSyncTables() {
        AppDb.db.execSQL(
            "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)"
        )
        AppDb.db.execSQL(
            """CREATE TABLE IF NOT EXISTS sync_meta (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id    TEXT NOT NULL UNIQUE,
                cloud_etag   TEXT,
                cloud_mtime  TEXT,
                local_mtime  TEXT,
                last_sync_at TEXT,
                last_push_at TEXT,
                last_pull_at TEXT,
                cloud_path   TEXT
            )"""
        )
    }

    // ---------- 配置读写 ----------

    suspend fun loadConfig(): SyncConfig? {
        ensureSyncTables()
        val account = getSetting(KEY_ACCOUNT) ?: return null
        val pass = getSetting(KEY_APP_PASS) ?: return null
        val davUrl = getSetting(KEY_DAV_URL) ?: DEFAULT_DAV_URL
        val remoteDir = getSetting(KEY_REMOTE_DIR) ?: DEFAULT_REMOTE_DIR
        return SyncConfig(account, pass, davUrl, remoteDir)
    }

    suspend fun saveConfig(cfg: SyncConfig) = withContext(Dispatchers.IO) {
        ensureSyncTables()
        setSetting(KEY_ACCOUNT, cfg.account)
        setSetting(KEY_APP_PASS, cfg.appPassword)
        setSetting(KEY_DAV_URL, cfg.davUrl.ifBlank { DEFAULT_DAV_URL })
        setSetting(KEY_REMOTE_DIR, cfg.remoteDir.ifBlank { DEFAULT_REMOTE_DIR })
        getOrCreateDeviceId()
    }

    private suspend fun getSetting(key: String): String? =
        AppDb.db.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    private fun setSetting(key: String, value: String) {
        AppDb.db.execSQL(
            "INSERT OR REPLACE INTO settings(key, value) VALUES(?,?)",
            arrayOf<Any?>(key, value)
        )
    }

    suspend fun getOrCreateDeviceId(): String {
        getSetting(KEY_DEVICE_ID)?.let { return it }
        val id = "device-${UUID.randomUUID()}"
        setSetting(KEY_DEVICE_ID, id)
        return id
    }

    private suspend fun resetLocalDeviceId() {
        AppDb.db.execSQL("DELETE FROM settings WHERE key=?", arrayOf<Any?>(KEY_DEVICE_ID))
        getOrCreateDeviceId()
    }

    suspend fun syncInfo(): SyncInfo = withContext(Dispatchers.IO) {
        val hasConfig = loadConfig() != null
        val deviceId = getOrCreateDeviceId()
        SyncInfo(
            hasConfig = hasConfig,
            deviceId = deviceId,
            lastSyncAt = metaField("last_sync_at"),
            lastPushAt = metaField("last_push_at"),
            lastPullAt = metaField("last_pull_at")
        )
    }

    private fun metaField(field: String): String? =
        AppDb.db.rawQuery("SELECT $field FROM sync_meta LIMIT 1", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    private suspend fun updateSyncMeta(kind: String, at: String) {
        val deviceId = getOrCreateDeviceId()
        val exists = AppDb.db.rawQuery("SELECT COUNT(*) FROM sync_meta", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
        if (exists == 0L) {
            AppDb.db.execSQL(
                "INSERT INTO sync_meta(device_id, last_sync_at) VALUES(?,?)",
                arrayOf<Any?>(deviceId, at)
            )
        } else {
            val setField = when (kind) {
                "push" -> "last_push_at = ?, last_sync_at = ?"
                "pull" -> "last_pull_at = ?, last_sync_at = ?"
                else -> "last_sync_at = ?"
            }
            val args = if (kind == "push" || kind == "pull") arrayOf<Any?>(at, at)
            else arrayOf<Any?>(at)
            AppDb.db.execSQL(
                "UPDATE sync_meta SET $setField WHERE id = (SELECT MAX(id) FROM sync_meta)",
                args
            )
        }
    }

    // ---------- URL 拼接 ----------

    private fun remoteBase(cfg: SyncConfig): String {
        val dav = cfg.davUrl.trim().trimEnd('/')
        val dir = cfg.remoteDir.trim().trim('/')
        return "$dav/$dir/"
    }

    private fun remoteDbUrl(cfg: SyncConfig) = remoteBase(cfg) + REMOTE_DB_NAME

    private fun remoteImagesUrl(cfg: SyncConfig) = remoteBase(cfg) + REMOTE_IMAGES_DIR + "/"

    private val localImagesDir: File
        get() = AppDb.imageBaseDir

    // ---------- 对外操作 ----------

    /** 测试连接 */
    suspend fun testConnection(cfg: SyncConfig): String {
        WebDavClient(cfg.account, cfg.appPassword).testConnection(remoteBase(cfg))
        return "连接成功"
    }

    /** 推送本地 DB + 图片 到坚果云 */
    suspend fun push(): SyncResult = withContext(Dispatchers.IO) {
        val cfg = requireConfig()
        AppDb.checkpoint()

        val client = WebDavClient(cfg.account, cfg.appPassword)
        val base = remoteBase(cfg)
        val imgDir = remoteImagesUrl(cfg)
        client.mkcol(base)
        client.mkcol(imgDir)

        // 1. 上传 DB
        val dbBytes = AppDb.dbFile.readBytes()
        client.putFile(remoteDbUrl(cfg), dbBytes)

        // 2. 上传图片
        val imgCount = uploadLocalImages(client, imgDir)

        val at = nowStr()
        updateSyncMeta("push", at)
        SyncResult(
            success = true,
            message = "推送成功（数据库 ${formatSize(dbBytes.size)}，图片 $imgCount 张）",
            at = at
        )
    }

    /** 从坚果云拉取 DB + 图片（会覆盖本机数据库） */
    suspend fun pull(): SyncResult = withContext(Dispatchers.IO) {
        val cfg = requireConfig()
        val client = WebDavClient(cfg.account, cfg.appPassword)

        // 1. 下载 DB（403/429 时重试，应对频率限制）
        val dbBytes = client.getFile(remoteDbUrl(cfg), retries = 2)
            ?: throw SyncException("远程数据库不存在，请先在电脑端推送数据")
        applyRemoteDb(dbBytes)

        // 2. 下载图片（失败不阻断，仅统计）
        val img = downloadRemoteImages(client, remoteImagesUrl(cfg))

        val at = nowStr()
        updateSyncMeta("pull", at)
        SyncResult(
            success = true,
            message = "拉取成功（数据库 ${formatSize(dbBytes.size)}，${imgSummary(img)}）",
            at = at
        )
    }

    /** 智能同步：DB 文件较大者胜；图片总是双向同步 */
    suspend fun smartSync(): SyncResult = withContext(Dispatchers.IO) {
        val cfg = requireConfig()
        AppDb.checkpoint()

        val client = WebDavClient(cfg.account, cfg.appPassword)
        val base = remoteBase(cfg)
        val imgDir = remoteImagesUrl(cfg)
        client.mkcol(base)
        client.mkcol(imgDir)

        val remoteDbBytes = client.getFile(remoteDbUrl(cfg), retries = 2)
        val localDbFile = AppDb.dbFile
        val localPushImages = localImagesDir.exists()

        when {
            // 两边都没有 DB
            remoteDbBytes == null && !localDbFile.exists() ->
                throw SyncException("本地和云端都没有数据，无法同步")

            // 只有本地有 → 推送 DB + 上传图片
            remoteDbBytes == null -> {
                client.putFile(remoteDbUrl(cfg), localDbFile.readBytes())
                val ic = uploadLocalImages(client, imgDir)
                val at = nowStr()
                updateSyncMeta("push", at)
                SyncResult(true, "推送成功（云端原无 DB，图片 $ic 张）", at)
            }

            // 只有远程有 → 拉取 DB + 下载图片
            !localDbFile.exists() -> {
                applyRemoteDb(remoteDbBytes)
                val img = downloadRemoteImages(client, imgDir)
                val at = nowStr()
                updateSyncMeta("pull", at)
                SyncResult(true, "拉取成功（本机原无数据，${imgSummary(img)}）", at)
            }

            // 两边都有 → DB 较大者胜，图片双向同步
            else -> {
                val pullRemote = remoteDbBytes.size > localDbFile.length()
                val dbMsg = if (pullRemote) {
                    applyRemoteDb(remoteDbBytes)
                    "拉取远程 DB（${formatSize(remoteDbBytes.size)}）"
                } else {
                    client.putFile(remoteDbUrl(cfg), localDbFile.readBytes())
                    "推送本地 DB（${formatSize(localDbFile.length().toInt())}）"
                }
                val uploadCount = if (localPushImages) uploadLocalImages(client, imgDir) else 0
                val img = downloadRemoteImages(client, imgDir)
                val at = nowStr()
                updateSyncMeta(if (pullRemote) "pull" else "push", at)
                SyncResult(
                    true,
                    "$dbMsg；上传图片 $uploadCount 张，${imgSummary(img)}",
                    at
                )
            }
        }
    }

    // ---------- DB 替换 ----------

    private suspend fun applyRemoteDb(bytes: ByteArray) {
        if (bytes.size < 16 || !bytes.copyOfRange(0, 16).contentEquals(SQLITE_HEADER)) {
            throw SyncException("远程数据库文件格式不正确，已中止")
        }
        val dbFile = AppDb.dbFile
        AppDb.closeForReplace()
        try {
            if (dbFile.exists()) {
                dbFile.copyTo(File(dbFile.absolutePath + ".bak"), overwrite = true)
            }
        } catch (_: Exception) {}
        listOf("-wal", "-shm").forEach { suffix ->
            val f = File(dbFile.absolutePath + suffix)
            if (f.exists()) f.delete()
        }
        dbFile.writeBytes(bytes)
        AppDb.reopen()
        resetLocalDeviceId()
    }

    // ---------- 图片：本地 → 远程 ----------

    /**
     * 遍历本地 images/ 目录，逐文件上传。
     * 远程 images/{archive_id}/{uuid}.jpg — 上传前确保父目录存在。
     */
    private suspend fun uploadLocalImages(client: WebDavClient, remoteImgBase: String): Int {
        val root = localImagesDir
        if (!root.exists()) return 0
        val uploadedDirs = mutableSetOf<String>()
        var count = 0

        root.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val rel = file.relativeTo(root).path.replace('\\', '/')
            val remoteUrl = remoteImgBase + rel

            // 先确保父目录存在（坚果云 PUT 到不存在的父目录会返回 409）
            val parentRel = rel.substringBeforeLast('/')
            if (parentRel.isNotEmpty() && parentRel !in uploadedDirs) {
                ensureRemoteDirChain(client, remoteImgBase, parentRel)
                uploadedDirs.add(parentRel)
            }

            try {
                client.putFile(remoteUrl, file.readBytes())
                count++
            } catch (_: Exception) {
                // 单张图上传失败不阻断整体流程
            }
        }
        return count
    }

    /**
     * 逐级确保远程父目录存在（images/{archive_id}/...）。
     * 例如 parentRel = "abc-123" → 只需 images/abc-123/
     * parentRel = "abc-123/def" → images/abc-123/ 然后 images/abc-123/def/
     */
    private suspend fun ensureRemoteDirChain(client: WebDavClient, remoteImgBase: String, parentRel: String) {
        val segments = parentRel.split('/').filter { it.isNotBlank() }
        val sb = StringBuilder()
        for (seg in segments) {
            sb.append(seg).append('/')
            val url = remoteImgBase + sb.toString()
            try {
                client.mkcol(url)
            } catch (_: Exception) {}
        }
    }

    // ---------- 图片：远程 → 本地 ----------

    /** 图片下载结果统计 */
    private data class ImageDownload(val ok: Int, val failed: Int, val firstError: String?)

    /**
     * 递归下载远程 images/ 下所有文件到本地 images/。
     * 用"目录栈"避免挂起函数递归。单张失败不中断整体。
     */
    private suspend fun downloadRemoteImages(client: WebDavClient, remoteImgBase: String): ImageDownload {
        val localRoot = localImagesDir
        localRoot.mkdirs()

        // 目录栈：存远程 href（完整 URL），每次弹出一个目录做 PROPFIND
        val imgBaseNoSlash = remoteImgBase.trimEnd('/')
        val dirStack = ArrayDeque<String>()
        dirStack.addLast(remoteImgBase)

        var ok = 0
        var failed = 0
        var firstError: String? = null

        while (dirStack.isNotEmpty()) {
            // 集合资源 PROPFIND 需带尾斜杠，部分服务器（含坚果云）对无斜杠目录会重定向/拒绝
            val dirUrl = dirStack.removeLast().let { if (it.endsWith("/")) it else "$it/" }
            val dirNoSlash = dirUrl.trimEnd('/')
            val entries = client.propfind(dirUrl, depth = 1)
            // propfind 返回的条目带"是否目录"标记（以 resourcetype 判定，兼容无尾斜杠的集合）
            for (entry in entries) {
                val href = normalizeHref(entry.href, dirUrl)
                val trimmed = href.trimEnd('/')
                // 跳过当前目录自身与 images 根目录
                if (trimmed == dirNoSlash || trimmed == imgBaseNoSlash) continue

                if (entry.isDir) {
                    // 目录，入栈后续处理（目录不做 GET，避免 403）
                    dirStack.addLast(href)
                } else {
                    // 文件，计算相对路径后下载
                    val rel = trimmed.removePrefix("$imgBaseNoSlash/")
                        .replace('/', File.separatorChar)
                    // 前缀不匹配（rel 未变化）或为空则跳过，避免生成错误路径
                    if (rel.isBlank() || rel == trimmed) continue

                    try {
                        // 图片下载对 403/429（频率限制）重试一次
                        val bytes = client.getFile(href, retries = 1) ?: continue
                        val localFile = File(localRoot, rel)
                        localFile.parentFile?.mkdirs()
                        localFile.writeBytes(bytes)
                        ok++
                    } catch (e: Exception) {
                        failed++
                        if (firstError == null) firstError = e.message
                    }
                }
            }
        }
        return ImageDownload(ok, failed, firstError)
    }

    /** 图片同步结果摘要文案 */
    private fun imgSummary(img: ImageDownload): String =
        if (img.failed > 0) {
            "图片 ${img.ok} 张，失败 ${img.failed} 张" + (img.firstError?.let { "（$it）" } ?: "")
        } else {
            "图片 ${img.ok} 张"
        }

    /**
     * 坚果云 PROPFIND 返回的 href 有时是绝对 URL，有时是 /-开头的绝对路径，
     * 也可能是相对当前目录的相对路径。这里统一转成绝对 URL。
     * @param contextUrl 本次 PROPFIND 请求的目录 URL（用于补全 scheme+host / 解析相对路径）
     */
    private fun normalizeHref(raw: String, contextUrl: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        val schemeHost = Regex("""^(https?://[^/]+)""").find(contextUrl)?.groupValues?.get(1)
        if (trimmed.startsWith("/")) {
            // 以 / 开头的绝对路径：拼上 scheme + host
            return (schemeHost ?: "") + trimmed
        }
        // 相对路径：相对当前 PROPFIND 的目录 URL 解析（不能用根目录，否则会多一层前缀）
        val ctx = if (contextUrl.endsWith("/")) contextUrl else "$contextUrl/"
        return ctx + trimmed
    }

    // ---------- 工具 ----------

    private suspend fun requireConfig(): SyncConfig =
        loadConfig() ?: throw SyncException("尚未配置坚果云账号，请先在设置中填写")

    private fun formatSize(bytes: Int): String =
        if (bytes < 1024) "$bytes B" else "%.1f KB".format(bytes / 1024.0)
}
