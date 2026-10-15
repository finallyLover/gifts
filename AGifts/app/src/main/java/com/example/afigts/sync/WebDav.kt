// ============================================================
// 坚果云 WebDAV 客户端（OkHttp 实现）
//
// 方法：
//   PROPFIND Depth=0  → testConnection
//   PROPFIND Depth=1  → propfindList（列目录，返回 href 列表）
//   MKCOL             → mkcol（建目录，已存在 405 视为成功）
//   PUT               → putFile（上传覆盖）
//   GET               → getFile（下载，404 返回 null）
// ============================================================
package com.example.afigts.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

class WebDavClient(private val account: String, private val appPassword: String) {

    private val auth: String = Credentials.basic(account, appPassword, Charsets.UTF_8)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)   // 图片下载可能慢
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    private val emptyBody = ByteArray(0).toRequestBody("application/octet-stream".toMediaType())
    private val xmlBody = "<propfind xmlns=\"DAV:\"><prop><resourcetype/></prop></propfind>"
        .toRequestBody("application/xml; charset=utf-8".toMediaType())

    /** 测试连接：对远程目录发 PROPFIND，207/200 或 404（目录不存在但账户有效）均视为成功 */
    suspend fun testConnection(remoteBase: String) = withContext(Dispatchers.IO) {
        val url = remoteBase.trimEnd('/')
        val req = Request.Builder().url(url)
            .header("Authorization", auth)
            .header("Depth", "0")
            .method("PROPFIND", xmlBody)
            .build()
        client.newCall(req).execute().use { resp ->
            when (resp.code) {
                in 200..299, 404 -> Unit
                401 -> throw SyncException("账户或应用密码错误，请检查")
                else -> throw SyncException("连接失败 (HTTP ${resp.code})")
            }
        }
    }

    /**
     * PROPFIND Depth=N 列目录，返回条目列表（href + 是否目录）。
     * 返回空列表表示目录不存在（404）。
     */
    suspend fun propfind(url: String, depth: Int = 1): List<DavEntry> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("Authorization", auth)
            .header("Depth", depth.toString())
            .method("PROPFIND", xmlBody)
            .build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> emptyList()
                resp.code !in 200..299 ->
                    throw SyncException("列出远程目录失败 (HTTP ${resp.code})")
                else -> parsePropfindEntries(resp.body?.string().orEmpty())
            }
        }
    }

    /**
     * 解析 PROPFIND XML：返回每条 <response> 的 href 与是否为目录。
     *
     * 目录判定以 <resourcetype> 中是否含 <collection/> 为准（兼容 <D:collection/>）——
     * 因为坚果云返回的集合 href **不一定带尾斜杠**，仅凭 href 结尾判断会把目录误判成文件，
     * 进而 GET 目录导致 403。
     */
    private fun parsePropfindEntries(xml: String): List<DavEntry> {
        val entries = mutableListOf<DavEntry>()
        // 坚果云返回带命名空间前缀的 <D:href> / <D:response>，需兼容前缀
        val hrefRegex = Regex("<(?:\\w+:)?href>(.*?)</(?:\\w+:)?href>", RegexOption.IGNORE_CASE)
        val respRegex = Regex(
            "<(?:\\w+:)?response[^>]*>(.*?)</(?:\\w+:)?response>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        respRegex.findAll(xml).forEach { m ->
            val block = m.groupValues[1]
            val href = hrefRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            if (href.isEmpty()) return@forEach
            // resourcetype 内含 collection(含前缀) → 目录；同时保留尾斜杠兜底
            val isDir = block.contains("collection", ignoreCase = true) || href.endsWith("/")
            entries.add(DavEntry(href, isDir))
        }
        // 兜底：若没解析出任何 response 块，退化为纯 href 解析
        if (entries.isEmpty()) {
            hrefRegex.findAll(xml).forEach { m ->
                val h = m.groupValues[1].trim()
                if (h.isNotEmpty()) entries.add(DavEntry(h, h.endsWith("/")))
            }
        }
        return entries
    }

    /** 创建目录（已存在时 405 视为成功；按 RFC 4918，409 = 父目录不存在） */
    suspend fun mkcol(dirUrl: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(dirUrl)
            .header("Authorization", auth)
            .method("MKCOL", emptyBody)
            .build()
        client.newCall(req).execute().use { resp ->
            when (resp.code) {
                in 200..299, 405 -> Unit
                409 -> throw SyncException("创建远程目录失败：父目录不存在 ($dirUrl)")
                401 -> throw SyncException("账户或应用密码错误，请检查")
                else -> throw SyncException("创建远程目录失败 (HTTP ${resp.code})")
            }
        }
    }

    /** 上传文件（覆盖） */
    suspend fun putFile(url: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("Authorization", auth)
            .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp: Response ->
            when (resp.code) {
                in 200..299 -> Unit
                401 -> throw SyncException("账户或应用密码错误，请检查")
                else -> throw SyncException("上传失败 (HTTP ${resp.code})")
            }
        }
    }

    /**
     * 下载文件；返回 null 表示远程不存在。
     * @param retries 403/429（坚果云常见于请求频率限制）时的重试次数，退避递增
     */
    suspend fun getFile(url: String, retries: Int = 0): ByteArray? = withContext(Dispatchers.IO) {
        var lastCode = 0
        repeat(retries + 1) { attempt ->
            val req = Request.Builder().url(url)
                .header("Authorization", auth)
                .get()
                .build()
            client.newCall(req).execute().use { resp: Response ->
                when {
                    resp.code == 404 -> return@withContext null
                    resp.code in 200..299 -> return@withContext resp.body?.bytes() ?: ByteArray(0)
                    resp.code == 401 -> throw SyncException("账户或应用密码错误，请检查")
                    resp.code == 403 || resp.code == 429 -> lastCode = resp.code
                    else -> throw SyncException("下载失败 (HTTP ${resp.code}) $url")
                }
            }
            if (attempt < retries) Thread.sleep(1500L * (attempt + 1))
        }
        throw SyncException("下载失败 (HTTP $lastCode) $url")
    }
}

/** PROPFIND 单条结果：href（原始）+ 是否为集合（目录） */
data class DavEntry(val href: String, val isDir: Boolean)

/** 同步相关异常（统一文案，便于 UI 直接展示） */
class SyncException(message: String) : Exception(message)
