// ============================================================
// 数据模型（与桌面端 src-tauri/src/models.rs 一一对应）
// 字段命名用驼峰，数据库列名保持下划线以兼容同一份 gifts.db
// ============================================================
package com.example.afigts.data

/** 模块1：礼簿档案 */
data class Archive(
    val id: String,
    val name: String,
    val banquetType: String,
    val eventDate: String?,
    val hostName: String?,
    val hostContact: String?,
    val remark: String?,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val archivedAt: String?
)

/** 模块2：收礼记录（礼簿录入） */
data class GiftRecord(
    val id: String = "",
    val archiveId: String,
    val pageId: String? = null,
    val personId: String? = null,
    val guestName: String = "",
    val relation: String? = null,
    val amount: Double = 0.0,
    val gift: String? = null,
    val giftAmount: Double = 0.0,
    val remark: String? = null,
    val operator: String? = null,
    val status: String = "pending",
    val rowOrder: Long = 0,
    val cellX: Double? = null,
    val cellY: Double? = null,
    val cellW: Double? = null,
    val cellH: Double? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val archiveName: String? = null
) {
    /** 合计金额：礼金 + 礼品折算 */
    val total: Double get() = amount + giftAmount
}

/** 模块3：回礼记录（我随别人的礼） */
data class ReturnGift(
    val id: String = "",
    val personId: String? = null,
    val personName: String = "",
    val eventName: String? = null,
    val eventDate: String? = null,
    val amount: Double = 0.0,
    val gift: String? = null,
    val giftAmount: Double = 0.0,
    val remark: String? = null,
    val operator: String? = null,
    val createdAt: String = ""
) {
    val total: Double get() = amount + giftAmount
}

/** 模块3：人员汇总（收/支双向聚合） */
data class PersonSummary(
    val id: String,
    val name: String,
    val pinyin: String? = null,
    val remark: String? = null,
    val returnStatus: String = "pending",
    val totalIn: Double = 0.0,
    val totalOut: Double = 0.0,
    val giftIn: Double = 0.0,
    val giftOut: Double = 0.0,
    val diff: Double = 0.0,
    val inCount: Long = 0,
    val outCount: Long = 0,
    val createdAt: String = "",
    val updatedAt: String = ""
)

/** 单人详情 */
data class PersonDetail(
    val summary: PersonSummary,
    val incoming: List<GiftRecord>,
    val outgoing: List<ReturnGift>
)

/** 分组统计项 */
data class GroupStat(val label: String, val count: Long, val amount: Double)

/** 单场礼簿统计 */
data class ArchiveStat(
    val totalCount: Long = 0,
    val totalAmount: Double = 0.0,
    val giftAmount: Double = 0.0,
    val confirmedCount: Long = 0,
    val pendingCount: Long = 0,
    val byRelation: List<GroupStat> = emptyList(),
    val amountBrackets: List<GroupStat> = emptyList()
)

/** 待回礼人员 */
data class PendingPerson(
    val id: String,
    val name: String,
    val totalIn: Double,
    val lastDate: String?
)

/** 全局人情统计 */
data class GlobalStat(
    val totalIn: Double = 0.0,
    val totalOut: Double = 0.0,
    val giftIn: Double = 0.0,
    val giftOut: Double = 0.0,
    val netDiff: Double = 0.0,
    val inCount: Long = 0,
    val outCount: Long = 0,
    val pendingPersons: List<PendingPerson> = emptyList()
)

/** 检索：人员命中 */
data class PersonMatch(val id: String, val name: String)

/** 检索结果 */
data class SearchResult(
    val persons: List<PersonMatch> = emptyList(),
    val incoming: List<GiftRecord> = emptyList(),
    val outgoing: List<ReturnGift> = emptyList()
)

/** 坚果云同步配置 */
data class SyncConfig(
    val account: String,
    val appPassword: String,
    val davUrl: String,
    val remoteDir: String
)

/** 同步状态信息 */
data class SyncInfo(
    val hasConfig: Boolean,
    val deviceId: String,
    val lastSyncAt: String?,
    val lastPushAt: String?,
    val lastPullAt: String?
)

/** 同步结果 */
data class SyncResult(val success: Boolean, val message: String, val at: String)
