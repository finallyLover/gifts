// ============================================================
// 录入页（手机端：无图片，卡片式逐行录入）
//
// 与原桌面端的差异（有意为之）：
//   - 不做图片录入（手机拍纸质礼簿并分割表格体验很差）
//   - 表格改为纵向卡片列表，点卡片弹出编辑弹窗，避免窄屏挤压
//   - 其余业务规则一致：金额非负、自动关联人员档案、未保存提醒
// ============================================================
package com.example.afigts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.afigts.data.Archive
import com.example.afigts.data.GiftRecord
import com.example.afigts.data.Repo
import com.example.afigts.ui.common.AppTopBar
import com.example.afigts.ui.common.ConfirmDialog
import com.example.afigts.ui.common.EmptyHint
import com.example.afigts.ui.common.Field
import com.example.afigts.ui.common.LoadingBox
import com.example.afigts.ui.common.SectionCard
import com.example.afigts.ui.common.StatusChip
import com.example.afigts.util.fmtYuan
import com.example.afigts.util.parseAmount
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(archiveId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var archive by remember { mutableStateOf<Archive?>(null) }
    var loading by remember { mutableStateOf(true) }
    val records = remember { mutableStateListOf<GiftRecord>() }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var askLeave by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var keyword by remember { mutableStateOf("") }

    suspend fun load() {
        archive = Repo.listArchives(includeArchived = true).firstOrNull { it.id == archiveId }
        records.clear()
        records.addAll(Repo.listRecords(archiveId))
        dirty = false
        loading = false
    }

    LaunchedEffect(archiveId) { load() }

    // 搜索过滤：按来宾姓名筛选，保留原下标以便编辑/删除定位到真实行
    val kw = keyword.trim()
    val visible: List<Pair<Int, GiftRecord>> = if (kw.isEmpty()) {
        records.mapIndexed { i, r -> i to r }
    } else {
        records.mapIndexedNotNull { i, r ->
            if (r.guestName.contains(kw, ignoreCase = true)) i to r else null
        }
    }

    // 合计：随搜索联动，统计当前显示的记录（未搜索时即整册合计）
    val count = visible.size
    val giftSum = visible.sumOf { it.second.amount }
    val giftAmountSum = visible.sumOf { it.second.giftAmount }
    val totalSum = giftSum + giftAmountSum

    suspend fun persist(): Boolean {
        saving = true
        return try {
            val saved = Repo.saveRecords(records.toList())
            records.clear()
            records.addAll(saved)
            dirty = false
            toast = "已保存 ${saved.size} 条记录"
            true
        } catch (e: Exception) {
            toast = "保存失败：${e.message}"
            false
        } finally {
            saving = false
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = archive?.name ?: "录入",
                onBack = { if (dirty) askLeave = true else onBack() },
                actions = {
                    TextButton(
                        enabled = count > 0 && !saving,
                        onClick = {
                            scope.launch {
                                records.indices.forEach { i ->
                                    records[i] = records[i].copy(status = "confirmed")
                                }
                                dirty = true
                                persist()
                            }
                        }
                    ) { Text("全部确认") }
                    TextButton(
                        enabled = !saving,
                        onClick = { scope.launch { persist() } }
                    ) { Text(if (saving) "保存中…" else "保存") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                records.add(
                    GiftRecord(archiveId = archiveId, rowOrder = records.size.toLong())
                )
                dirty = true
                // 新增后自动打开编辑弹窗（用户反馈：点 + 后没反应）
                editingIndex = records.lastIndex
            }) { Icon(Icons.Default.Add, contentDescription = "新增一行") }
        }
    ) { padding ->
        if (loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 合计栏：本礼簿金额统计，随搜索联动（未搜索时即整册合计）
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (kw.isEmpty()) "共 $count 笔" else "筛选出 $count 笔",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "合计 ${fmtYuan(totalSum)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "礼金 ${fmtYuan(giftSum)} · 礼品 ${fmtYuan(giftAmountSum)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 搜索框：按来宾姓名过滤，便于快速定位并编辑
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text("搜索来宾姓名") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (keyword.isNotEmpty()) {
                        IconButton(onClick = { keyword = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "清空")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )
            toast?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            when {
                records.isEmpty() -> EmptyHint("暂无记录，点击右下角 + 逐行录入")
                visible.isEmpty() -> EmptyHint("未找到「$kw」相关的来宾记录")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = visible, key = { it.first }) { (origIndex, r) ->
                        RecordCard(
                            index = origIndex + 1,
                            record = r,
                            onClick = { editingIndex = origIndex },
                            onToggleStatus = {
                                records[origIndex] = r.copy(
                                    status = if (r.status == "confirmed") "pending" else "confirmed"
                                )
                                dirty = true
                            },
                            onDelete = { deleteIndex = origIndex }
                        )
                    }
                }
            }
        }
    }

    // 编辑弹窗
    editingIndex?.let { idx ->
        RecordEditDialog(
            record = records[idx],
            onDismiss = { editingIndex = null },
            onSave = {
                records[idx] = it
                dirty = true
                editingIndex = null
            }
        )
    }

    // 删除确认
    deleteIndex?.let { idx ->
        val r = records[idx]
        ConfirmDialog(
            title = "删除记录",
            message = "确定删除「${r.guestName.ifBlank { "未填写姓名" }}」这条记录吗？",
            confirmText = "删除",
            onConfirm = {
                deleteIndex = null
                scope.launch {
                    if (r.id.isNotBlank()) Repo.deleteRecords(listOf(r.id))
                    records.removeAt(idx)
                    dirty = true
                }
            },
            onDismiss = { deleteIndex = null }
        )
    }

    // 未保存提醒
    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text("未保存提醒") },
            text = { Text("当前有未保存的修改，是否先保存？") },
            confirmButton = {
                TextButton(onClick = {
                    askLeave = false
                    scope.launch { if (persist()) onBack() }
                }) { Text("保存并返回") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askLeave = false }) { Text("取消") }
                    TextButton(onClick = { askLeave = false; onBack() }) { Text("放弃修改") }
                }
            }
        )
    }
}

/** 单条记录卡片（手机端纵向布局） */
@Composable
private fun RecordCard(
    index: Int,
    record: GiftRecord,
    onClick: () -> Unit,
    onToggleStatus: () -> Unit,
    onDelete: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)) {
            Text(
                "$index",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(22.dp)
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        record.guestName.ifBlank { "（未填写姓名）" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!record.relation.isNullOrBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                record.relation!!,
                                Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    StatusChip(record.status == "confirmed")
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Text(
                        "礼金 ${fmtYuan(record.amount)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (record.giftAmount > 0) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "礼品 ${fmtYuan(record.giftAmount)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (!record.gift.isNullOrBlank() || !record.remark.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOfNotNull(
                            record.gift?.takeIf { it.isNotBlank() }?.let { "礼品：$it" },
                            record.remark?.takeIf { it.isNotBlank() }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onToggleStatus) {
                Icon(
                    Icons.Default.Done,
                    contentDescription = "切换核对状态",
                    tint = if (record.status == "confirmed") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}

/** 记录编辑弹窗 */
@Composable
private fun RecordEditDialog(
    record: GiftRecord,
    onDismiss: () -> Unit,
    onSave: (GiftRecord) -> Unit
) {
    var guestName by remember { mutableStateOf(record.guestName) }
    var relation by remember { mutableStateOf(record.relation ?: "") }
    var amount by remember { mutableStateOf(if (record.amount == 0.0) "" else record.amount.toString()) }
    var gift by remember { mutableStateOf(record.gift ?: "") }
    var giftAmount by remember {
        mutableStateOf(if (record.giftAmount == 0.0) "" else record.giftAmount.toString())
    }
    var remark by remember { mutableStateOf(record.remark ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (record.id.isBlank()) "新增记录" else "编辑记录") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Field("来宾姓名", guestName, { guestName = it }, placeholder = "如：张伟")
                Spacer(Modifier.height(10.dp))
                Field("关系", relation, { relation = it }, placeholder = "亲戚 / 朋友 / 同事…")
                Spacer(Modifier.height(10.dp))
                Field(
                    "礼金金额（元）", amount, { amount = it },
                    keyboardType = KeyboardType.Decimal, placeholder = "0"
                )
                Spacer(Modifier.height(10.dp))
                Field("礼品名称", gift, { gift = it }, placeholder = "如：两瓶酒")
                Spacer(Modifier.height(10.dp))
                Field(
                    "礼品折算金额（元）", giftAmount, { giftAmount = it },
                    keyboardType = KeyboardType.Decimal,
                    placeholder = "留空表示实物礼品、不计金额"
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    label = { Text("备注") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    record.copy(
                        guestName = guestName.trim(),
                        relation = relation.trim().ifBlank { null },
                        amount = parseAmount(amount),
                        gift = gift.trim().ifBlank { null },
                        giftAmount = parseAmount(giftAmount),
                        remark = remark.trim().ifBlank { null }
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
