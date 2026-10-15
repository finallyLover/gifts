// ============================================================
// 数据仓储层：把桌面端 Rust 命令中的全部业务逻辑用 Kotlin 复刻
// 所有方法均为 suspend，内部切换 IO 线程执行 SQLite 操作
// ============================================================
package com.example.afigts.data

import android.database.Cursor
import com.example.afigts.util.nowStr
import com.example.afigts.util.round2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

// ---------- Cursor 取值小工具 ----------
private fun Cursor.s(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""
private fun Cursor.sn(name: String): String? {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) null else getString(i)
}
private fun Cursor.d(name: String): Double {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) 0.0 else getDouble(i)
}
private fun Cursor.dn(name: String): Double? {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) null else getDouble(i)
}
private fun Cursor.l(name: String): Long {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) 0L else getLong(i)
}

private fun newId(): String = UUID.randomUUID().toString()

object Repo {

    // ============================================================
    // 模块1：礼簿档案
    // ============================================================

    /** 档案列表（默认不含已归档；支持名称模糊搜索） */
    suspend fun listArchives(keyword: String? = null, includeArchived: Boolean = false): List<Archive> =
        withContext(Dispatchers.IO) {
            val sql = StringBuilder("SELECT * FROM archives WHERE 1=1")
            val args = mutableListOf<String>()
            if (!includeArchived) sql.append(" AND status != 'archived'")
            if (!keyword.isNullOrBlank()) {
                sql.append(" AND name LIKE ?")
                args += "%${keyword.trim()}%"
            }
            sql.append(" ORDER BY created_at DESC")
            AppDb.db.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
                buildList { while (c.moveToNext()) add(readArchive(c)) }
            }
        }

    /** 各档案的记录条数（用于列表卡片展示） */
    suspend fun archiveRecordCounts(): Map<String, Long> = withContext(Dispatchers.IO) {
        AppDb.db.rawQuery(
            "SELECT archive_id, COUNT(*) AS n FROM gift_records GROUP BY archive_id", null
        ).use { c ->
            buildMap { while (c.moveToNext()) put(c.s("archive_id"), c.l("n")) }
        }
    }

    suspend fun createArchive(
        name: String,
        banquetType: String,
        eventDate: String?,
        hostName: String?,
        hostContact: String?,
        remark: String?
    ): Archive = withContext(Dispatchers.IO) {
        val id = newId()
        val now = nowStr()
        AppDb.db.execSQL(
            """INSERT INTO archives
               (id, name, banquet_type, event_date, host_name, host_contact, remark, status, created_at, updated_at)
               VALUES (?,?,?,?,?,?,?, 'active', ?, ?)""",
            arrayOf<Any?>(id, name, banquetType, eventDate, hostName, hostContact, remark, now, now)
        )
        Archive(id, name, banquetType, eventDate, hostName, hostContact, remark, "active", now, now, null)
    }

    suspend fun updateArchive(
        id: String,
        name: String,
        banquetType: String,
        eventDate: String?,
        hostName: String?,
        hostContact: String?,
        remark: String?
    ) = withContext(Dispatchers.IO) {
        AppDb.db.execSQL(
            """UPDATE archives SET name=?, banquet_type=?, event_date=?, host_name=?,
               host_contact=?, remark=?, updated_at=? WHERE id=?""",
            arrayOf<Any?>(name, banquetType, eventDate, hostName, hostContact, remark, nowStr(), id)
        )
        Unit
    }

    /** 归档 / 取消归档 */
    suspend fun setArchiveStatus(id: String, archived: Boolean) = withContext(Dispatchers.IO) {
        if (archived) {
            AppDb.db.execSQL(
                "UPDATE archives SET status='archived', archived_at=?, updated_at=? WHERE id=?",
                arrayOf<Any?>(nowStr(), nowStr(), id)
            )
        } else {
            AppDb.db.execSQL(
                "UPDATE archives SET status='active', archived_at=NULL, updated_at=? WHERE id=?",
                arrayOf<Any?>(nowStr(), id)
            )
        }
        Unit
    }

    /** 删除档案（外键级联删除其记录） */
    suspend fun deleteArchive(id: String) = withContext(Dispatchers.IO) {
        AppDb.db.execSQL("DELETE FROM archives WHERE id=?", arrayOf<Any?>(id))
        cleanupOrphanPersonsInner()
        Unit
    }

    private fun readArchive(c: Cursor) = Archive(
        id = c.s("id"),
        name = c.s("name"),
        banquetType = c.s("banquet_type"),
        eventDate = c.sn("event_date"),
        hostName = c.sn("host_name"),
        hostContact = c.sn("host_contact"),
        remark = c.sn("remark"),
        status = c.s("status"),
        createdAt = c.s("created_at"),
        updatedAt = c.s("updated_at"),
        archivedAt = c.sn("archived_at")
    )

    // ============================================================
    // 模块2：收礼记录
    // ============================================================

    /** 某个档案的全部记录（安卓端无图片页，直接列出所有行） */
    suspend fun listRecords(archiveId: String): List<GiftRecord> = withContext(Dispatchers.IO) {
        val list = AppDb.db.rawQuery(
            """SELECT gr.*, a.name AS archive_name
               FROM gift_records gr LEFT JOIN archives a ON gr.archive_id = a.id
               WHERE gr.archive_id=?""",
            arrayOf(archiveId)
        ).use { c -> buildList { while (c.moveToNext()) add(readRecord(c)) } }
        // 无页面记录排到最后（与桌面端一致）
        list.sortedWith(compareBy({ it.pageId != null }, { it.rowOrder }, { it.createdAt }))
    }

    /** 单条新增或更新（id 为空视为新增） */
    suspend fun saveRecord(rec: GiftRecord): GiftRecord = withContext(Dispatchers.IO) {
        val saved = upsertRecord(rec)
        cleanupOrphanPersonsInner()
        saved
    }

    /** 批量保存（整页/整册落库） */
    suspend fun saveRecords(list: List<GiftRecord>): List<GiftRecord> = withContext(Dispatchers.IO) {
        val db = AppDb.db
        db.beginTransaction()
        val result = try {
            list.mapIndexed { index, rec -> upsertRecord(rec.copy(rowOrder = index.toLong())) }
                .also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
        cleanupOrphanPersonsInner()
        result
    }

    /** 单条记录 upsert 实现（调用方需自行保证非负校验） */
    private fun upsertRecord(rec: GiftRecord): GiftRecord {
        val db = AppDb.db
        val amount = if (rec.amount < 0) 0.0 else rec.amount
        val giftAmount = if (rec.giftAmount < 0) 0.0 else rec.giftAmount
        val personId = ensurePersonInner(rec.guestName)
        val now = nowStr()
        val id = rec.id.ifBlank { newId() }

        val updated = db.update(
            "gift_records",
            android.content.ContentValues().apply {
                put("page_id", rec.pageId)
                put("person_id", personId)
                put("guest_name", rec.guestName)
                put("relation", rec.relation)
                put("amount", amount)
                put("gift", rec.gift)
                put("gift_amount", giftAmount)
                put("remark", rec.remark)
                put("operator", rec.operator)
                put("status", rec.status)
                put("row_order", rec.rowOrder)
                put("cell_x", rec.cellX)
                put("cell_y", rec.cellY)
                put("cell_w", rec.cellW)
                put("cell_h", rec.cellH)
                put("updated_at", now)
            },
            "id=?", arrayOf(id)
        )
        if (updated == 0) {
            db.execSQL(
                """INSERT INTO gift_records
                   (id, archive_id, page_id, person_id, guest_name, relation, amount, gift,
                    gift_amount, remark, operator, status, row_order, cell_x, cell_y, cell_w, cell_h,
                    created_at, updated_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf<Any?>(
                    id, rec.archiveId, rec.pageId, personId, rec.guestName, rec.relation, amount,
                    rec.gift, giftAmount, rec.remark, rec.operator, rec.status, rec.rowOrder,
                    rec.cellX, rec.cellY, rec.cellW, rec.cellH, now, now
                )
            )
        }
        return db.rawQuery("SELECT * FROM gift_records WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) readRecord(c) else rec.copy(id = id)
        }
    }

    suspend fun deleteRecords(ids: List<String>) = withContext(Dispatchers.IO) {
        val db = AppDb.db
        db.beginTransaction()
        try {
            ids.forEach { db.execSQL("DELETE FROM gift_records WHERE id=?", arrayOf<Any?>(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        cleanupOrphanPersonsInner()
        Unit
    }

    suspend fun setRecordsStatus(ids: List<String>, status: String) = withContext(Dispatchers.IO) {
        val db = AppDb.db
        db.beginTransaction()
        try {
            ids.forEach {
                db.execSQL(
                    "UPDATE gift_records SET status=?, updated_at=? WHERE id=?",
                    arrayOf<Any?>(status, nowStr(), it)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Unit
    }

    private fun readRecord(c: Cursor) = GiftRecord(
        id = c.s("id"),
        archiveId = c.s("archive_id"),
        pageId = c.sn("page_id"),
        personId = c.sn("person_id"),
        guestName = c.s("guest_name"),
        relation = c.sn("relation"),
        amount = c.d("amount"),
        gift = c.sn("gift"),
        giftAmount = c.d("gift_amount"),
        remark = c.sn("remark"),
        operator = c.sn("operator"),
        status = c.s("status"),
        rowOrder = c.l("row_order"),
        cellX = c.dn("cell_x"),
        cellY = c.dn("cell_y"),
        cellW = c.dn("cell_w"),
        cellH = c.dn("cell_h"),
        createdAt = c.s("created_at"),
        updatedAt = c.s("updated_at"),
        archiveName = c.sn("archive_name")
    )

    // ============================================================
    // 模块3：人员人情档案
    // ============================================================

    private const val PERSON_SUMMARY_SELECT = """
        SELECT p.*,
            COALESCE((SELECT SUM(amount + COALESCE(gift_amount,0)) FROM gift_records gr WHERE gr.person_id=p.id), 0) AS total_in,
            COALESCE((SELECT SUM(amount + COALESCE(gift_amount,0)) FROM return_gifts rg WHERE rg.person_id=p.id), 0) AS total_out,
            COALESCE((SELECT SUM(gift_amount) FROM gift_records gr WHERE gr.person_id=p.id), 0) AS gift_in,
            COALESCE((SELECT SUM(gift_amount) FROM return_gifts rg WHERE rg.person_id=p.id), 0) AS gift_out,
            (SELECT COUNT(*) FROM gift_records gr WHERE gr.person_id=p.id) AS in_count,
            (SELECT COUNT(*) FROM return_gifts rg WHERE rg.person_id=p.id) AS out_count
        FROM persons p WHERE 1=1
    """

    suspend fun listPersonSummaries(
        keyword: String? = null,
        returnStatus: String? = null
    ): List<PersonSummary> = withContext(Dispatchers.IO) {
        val sql = StringBuilder(PERSON_SUMMARY_SELECT)
        val args = mutableListOf<String>()
        if (!keyword.isNullOrBlank()) {
            sql.append(" AND (p.name LIKE ? OR p.pinyin LIKE ?)")
            args += "%${keyword.trim()}%"
            args += "%${keyword.trim()}%"
        }
        if (!returnStatus.isNullOrBlank()) {
            sql.append(" AND p.return_status=?")
            args += returnStatus
        }
        sql.append(" ORDER BY total_in DESC, p.created_at DESC")
        AppDb.db.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(readPersonSummary(c)) }
        }
    }

    suspend fun getPersonDetail(id: String): PersonDetail? = withContext(Dispatchers.IO) {
        val summary = AppDb.db.rawQuery("$PERSON_SUMMARY_SELECT AND p.id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) readPersonSummary(c) else null
        } ?: return@withContext null

        val incoming = AppDb.db.rawQuery(
            """SELECT gr.*, a.name AS archive_name
               FROM gift_records gr LEFT JOIN archives a ON gr.archive_id=a.id
               WHERE gr.person_id=? ORDER BY gr.created_at DESC""",
            arrayOf(id)
        ).use { c -> buildList { while (c.moveToNext()) add(readRecord(c)) } }

        val outgoing = AppDb.db.rawQuery(
            "SELECT * FROM return_gifts WHERE person_id=? ORDER BY created_at DESC",
            arrayOf(id)
        ).use { c -> buildList { while (c.moveToNext()) add(readReturnGift(c)) } }

        PersonDetail(summary, incoming, outgoing)
    }

    /** 新增回礼记录（自动把该人标记为已回礼） */
    suspend fun addReturnGift(
        personName: String,
        eventName: String?,
        eventDate: String?,
        amount: Double,
        gift: String?,
        giftAmount: Double,
        remark: String?
    ): ReturnGift? = withContext(Dispatchers.IO) {
        val name = personName.trim()
        if (name.isEmpty()) return@withContext null
        val db = AppDb.db
        val personId = ensurePersonInner(name)
        val id = newId()
        val now = nowStr()
        db.execSQL(
            """INSERT INTO return_gifts
               (id, person_id, person_name, event_name, event_date, amount, gift, gift_amount, remark, operator, created_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            arrayOf<Any?>(
                id, personId, name, eventName, eventDate,
                if (amount < 0) 0.0 else amount, gift,
                if (giftAmount < 0) 0.0 else giftAmount, remark, null, now
            )
        )
        db.execSQL(
            "UPDATE persons SET return_status='returned', updated_at=? WHERE id=?",
            arrayOf<Any?>(now, personId)
        )
        db.rawQuery("SELECT * FROM return_gifts WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) readReturnGift(c) else null
        }
    }

    suspend fun updateReturnGift(
        id: String,
        eventName: String?,
        eventDate: String?,
        amount: Double,
        gift: String?,
        giftAmount: Double,
        remark: String?
    ) = withContext(Dispatchers.IO) {
        AppDb.db.execSQL(
            """UPDATE return_gifts SET event_name=?, event_date=?, amount=?, gift=?,
               gift_amount=?, remark=? WHERE id=?""",
            arrayOf<Any?>(
                eventName, eventDate, if (amount < 0) 0.0 else amount, gift,
                if (giftAmount < 0) 0.0 else giftAmount, remark, id
            )
        )
        Unit
    }

    suspend fun deleteReturnGift(id: String) = withContext(Dispatchers.IO) {
        AppDb.db.execSQL("DELETE FROM return_gifts WHERE id=?", arrayOf<Any?>(id))
        cleanupOrphanPersonsInner()
        Unit
    }

    /** 手动切换回礼状态（pending / returned） */
    suspend fun setPersonStatus(personId: String, returnStatus: String) = withContext(Dispatchers.IO) {
        AppDb.db.execSQL(
            "UPDATE persons SET return_status=?, updated_at=? WHERE id=?",
            arrayOf<Any?>(returnStatus, nowStr(), personId)
        )
        Unit
    }

    private fun readReturnGift(c: Cursor) = ReturnGift(
        id = c.s("id"),
        personId = c.sn("person_id"),
        personName = c.s("person_name"),
        eventName = c.sn("event_name"),
        eventDate = c.sn("event_date"),
        amount = c.d("amount"),
        gift = c.sn("gift"),
        giftAmount = c.d("gift_amount"),
        remark = c.sn("remark"),
        operator = c.sn("operator"),
        createdAt = c.s("created_at")
    )

    private fun readPersonSummary(c: Cursor): PersonSummary {
        val totalIn = c.d("total_in")
        val totalOut = c.d("total_out")
        return PersonSummary(
            id = c.s("id"),
            name = c.s("name"),
            pinyin = c.sn("pinyin"),
            remark = c.sn("remark"),
            returnStatus = c.s("return_status"),
            totalIn = totalIn,
            totalOut = totalOut,
            giftIn = c.d("gift_in"),
            giftOut = c.d("gift_out"),
            diff = round2(totalIn - totalOut),
            inCount = c.l("in_count"),
            outCount = c.l("out_count"),
            createdAt = c.s("created_at"),
            updatedAt = c.s("updated_at")
        )
    }

    /** 按姓名获取/新建人员档案，返回 person_id（姓名为空返回 null） */
    private fun ensurePersonInner(rawName: String): String? {
        val name = rawName.trim()
        if (name.isEmpty()) return null
        val db = AppDb.db
        db.rawQuery("SELECT id FROM persons WHERE name=?", arrayOf(name)).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        val id = newId()
        val now = nowStr()
        db.execSQL(
            "INSERT INTO persons (id, name, return_status, created_at, updated_at) VALUES (?,?, 'pending', ?, ?)",
            arrayOf<Any?>(id, name, now, now)
        )
        return id
    }

    /** 清理孤儿人员档案（既无收礼也无回礼记录的人） */
    suspend fun cleanupOrphanPersons() = withContext(Dispatchers.IO) { cleanupOrphanPersonsInner() }

    private fun cleanupOrphanPersonsInner() {
        AppDb.db.execSQL(
            """DELETE FROM persons
               WHERE NOT EXISTS (SELECT 1 FROM gift_records gr WHERE gr.person_id = persons.id)
                 AND NOT EXISTS (SELECT 1 FROM return_gifts rg WHERE rg.person_id = persons.id)"""
        )
    }

    // ============================================================
    // 模块4：全局检索
    // ============================================================

    suspend fun searchAll(keyword: String): SearchResult = withContext(Dispatchers.IO) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return@withContext SearchResult()
        val like = "%$kw%"

        val persons = AppDb.db.rawQuery(
            "SELECT id, name FROM persons WHERE name LIKE ?", arrayOf(like)
        ).use { c -> buildList { while (c.moveToNext()) add(PersonMatch(c.s("id"), c.s("name"))) } }

        val incoming = AppDb.db.rawQuery(
            """SELECT gr.*, a.name AS archive_name
               FROM gift_records gr LEFT JOIN archives a ON gr.archive_id=a.id
               WHERE gr.guest_name LIKE ? ORDER BY gr.created_at DESC""",
            arrayOf(like)
        ).use { c -> buildList { while (c.moveToNext()) add(readRecord(c)) } }

        val outgoing = AppDb.db.rawQuery(
            "SELECT * FROM return_gifts WHERE person_name LIKE ? ORDER BY created_at DESC", arrayOf(like)
        ).use { c -> buildList { while (c.moveToNext()) add(readReturnGift(c)) } }

        SearchResult(persons, incoming, outgoing)
    }

    // ============================================================
    // 模块5：统计报表
    // ============================================================

    suspend fun archiveStat(archiveId: String): ArchiveStat = withContext(Dispatchers.IO) {
        val db = AppDb.db
        var totalCount = 0L
        var totalAmount = 0.0
        var giftAmount = 0.0
        var confirmed = 0L
        var pending = 0L
        db.rawQuery(
            """SELECT COUNT(*) AS n,
                      COALESCE(SUM(amount + COALESCE(gift_amount,0)), 0) AS amt,
                      COALESCE(SUM(gift_amount), 0) AS gamt,
                      SUM(CASE WHEN status='confirmed' THEN 1 ELSE 0 END) AS conf,
                      SUM(CASE WHEN status='pending' THEN 1 ELSE 0 END) AS pend
               FROM gift_records WHERE archive_id=?""",
            arrayOf(archiveId)
        ).use { c ->
            if (c.moveToFirst()) {
                totalCount = c.l("n")
                totalAmount = c.d("amt")
                giftAmount = c.d("gamt")
                confirmed = c.l("conf")
                pending = c.l("pend")
            }
        }

        val byRelation = db.rawQuery(
            """SELECT COALESCE(NULLIF(relation,''),'未填写') AS label,
                      COUNT(*) AS n, SUM(amount + COALESCE(gift_amount,0)) AS amt
               FROM gift_records WHERE archive_id=?
               GROUP BY relation ORDER BY amt DESC""",
            arrayOf(archiveId)
        ).use { c -> buildList { while (c.moveToNext()) add(readGroup(c)) } }

        val bracketsMap = linkedMapOf(
            "200以下" to GroupStat("200以下", 0, 0.0),
            "200-499" to GroupStat("200-499", 0, 0.0),
            "500-999" to GroupStat("500-999", 0, 0.0),
            "1000-1999" to GroupStat("1000-1999", 0, 0.0),
            "2000以上" to GroupStat("2000以上", 0, 0.0)
        )
        db.rawQuery(
            """SELECT bucket AS label, COUNT(*) AS n, SUM(amount) AS amt FROM (
                   SELECT amount,
                       CASE
                           WHEN amount < 200 THEN '200以下'
                           WHEN amount < 500 THEN '200-499'
                           WHEN amount < 1000 THEN '500-999'
                           WHEN amount < 2000 THEN '1000-1999'
                           ELSE '2000以上'
                       END AS bucket
                   FROM gift_records WHERE archive_id=?
               ) GROUP BY bucket""",
            arrayOf(archiveId)
        ).use { c -> while (c.moveToNext()) { val g = readGroup(c); bracketsMap[g.label] = g } }

        ArchiveStat(
            totalCount = totalCount,
            totalAmount = round2(totalAmount),
            giftAmount = round2(giftAmount),
            confirmedCount = confirmed,
            pendingCount = pending,
            byRelation = byRelation,
            amountBrackets = bracketsMap.values.toList()
        )
    }

    private fun readGroup(c: Cursor) = GroupStat(
        label = c.s("label"),
        count = c.l("n"),
        amount = round2(c.d("amt"))
    )

    suspend fun globalStat(): GlobalStat = withContext(Dispatchers.IO) {
        val db = AppDb.db
        var inCount = 0L; var totalIn = 0.0; var giftIn = 0.0
        db.rawQuery(
            "SELECT COUNT(*) AS n, COALESCE(SUM(amount + COALESCE(gift_amount,0)),0) AS amt, COALESCE(SUM(gift_amount),0) AS gamt FROM gift_records",
            null
        ).use { c -> if (c.moveToFirst()) { inCount = c.l("n"); totalIn = c.d("amt"); giftIn = c.d("gamt") } }

        var outCount = 0L; var totalOut = 0.0; var giftOut = 0.0
        db.rawQuery(
            "SELECT COUNT(*) AS n, COALESCE(SUM(amount + COALESCE(gift_amount,0)),0) AS amt, COALESCE(SUM(gift_amount),0) AS gamt FROM return_gifts",
            null
        ).use { c -> if (c.moveToFirst()) { outCount = c.l("n"); totalOut = c.d("amt"); giftOut = c.d("gamt") } }

        val pendingPersons = db.rawQuery(
            """SELECT p.id AS id, p.name AS name,
                      (SELECT COALESCE(SUM(amount + COALESCE(gift_amount,0)),0) FROM gift_records gr WHERE gr.person_id=p.id) AS total_in,
                      (SELECT MAX(created_at) FROM gift_records gr WHERE gr.person_id=p.id) AS last_date
               FROM persons p
               WHERE p.return_status='pending'
                 AND EXISTS (SELECT 1 FROM gift_records gr WHERE gr.person_id=p.id)
               ORDER BY total_in DESC""",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(PendingPerson(c.s("id"), c.s("name"), round2(c.d("total_in")), c.sn("last_date")))
            }
        }

        GlobalStat(
            totalIn = round2(totalIn),
            totalOut = round2(totalOut),
            giftIn = round2(giftIn),
            giftOut = round2(giftOut),
            netDiff = round2(totalIn - totalOut),
            inCount = inCount,
            outCount = outCount,
            pendingPersons = pendingPersons
        )
    }

    /** 人情对照表（每人 收/支/差额） */
    suspend fun comparisonReport(keyword: String? = null): List<PersonSummary> = withContext(Dispatchers.IO) {
        val sql = StringBuilder(PERSON_SUMMARY_SELECT)
        val args = mutableListOf<String>()
        if (!keyword.isNullOrBlank()) {
            sql.append(" AND p.name LIKE ?")
            args += "%${keyword.trim()}%"
        }
        sql.append(" ORDER BY total_in - total_out DESC, total_in DESC")
        AppDb.db.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(readPersonSummary(c)) }
        }
    }

    // ============================================================
    // 模块7：设置（键值）与应用信息
    // ============================================================

    suspend fun getSetting(key: String): String? = withContext(Dispatchers.IO) {
        AppDb.db.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }

    suspend fun setSetting(key: String, value: String) = withContext(Dispatchers.IO) {
        AppDb.db.execSQL(
            "INSERT OR REPLACE INTO settings(key, value) VALUES(?,?)",
            arrayOf<Any?>(key, value)
        )
        Unit
    }

    /** 数据库文件大小（字节） */
    fun dbSizeBytes(): Long = AppDb.dbFile.length()

    /** 本地图片文件总数（应用信息展示）：遍历 files/images/ 下的所有文件 */
    suspend fun imageCount(): Long = withContext(Dispatchers.IO) {
        val root = AppDb.imageBaseDir
        if (!root.exists()) return@withContext 0L
        var n = 0L
        root.walkTopDown().forEach { if (it.isFile) n++ }
        n
    }

    /** 记录总数 / 档案总数（应用信息展示） */
    suspend fun counts(): Pair<Long, Long> = withContext(Dispatchers.IO) {
        var records = 0L
        AppDb.db.rawQuery(
            "SELECT (SELECT COUNT(*) FROM gift_records) + (SELECT COUNT(*) FROM return_gifts) AS n", null
        ).use { c -> if (c.moveToFirst()) records = c.l("n") }
        var archives = 0L
        AppDb.db.rawQuery("SELECT COUNT(*) AS n FROM archives", null).use { c ->
            if (c.moveToFirst()) archives = c.l("n")
        }
        archives to records
    }

    /**
     * 根据 page_id 查询该页原图的相对路径（ledger_pages.file_path）。
     * 返回的是类似 "images/xxx/yyy.jpg" 的相对路径，UI 层需拼 Android base 目录。
     */
    suspend fun getPageFilePath(pageId: String): String? = withContext(Dispatchers.IO) {
        AppDb.db.rawQuery(
            "SELECT file_path FROM ledger_pages WHERE id=?", arrayOf(pageId)
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }

    /**
     * 根据 person_id 找到该人最近一条有关联图片页（page_id 非空）的收礼记录。
     * 用于检索查询中"点击人员查看该人最近的原图"。
     */
    suspend fun getLatestRecordWithPage(personId: String): GiftRecord? = withContext(Dispatchers.IO) {
        AppDb.db.rawQuery(
            """SELECT gr.*, a.name AS archive_name
               FROM gift_records gr LEFT JOIN archives a ON gr.archive_id=a.id
               WHERE gr.person_id=? AND gr.page_id IS NOT NULL
               ORDER BY gr.created_at DESC LIMIT 1""",
            arrayOf(personId)
        ).use { c ->
            if (c.moveToFirst()) readRecord(c) else null
        }
    }
}
