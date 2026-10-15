// ============================================================
// 人情档案：人员详情（收礼明细 / 回礼明细 / 回礼状态管理）
// ============================================================
package com.example.afigts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.afigts.data.GiftRecord
import com.example.afigts.data.PersonDetail
import com.example.afigts.data.ReturnGift
import com.example.afigts.data.Repo
import com.example.afigts.ui.common.AppTopBar
import com.example.afigts.ui.common.ConfirmDialog
import com.example.afigts.ui.common.EmptyHint
import com.example.afigts.ui.common.Field
import com.example.afigts.ui.common.InfoRow
import com.example.afigts.ui.common.LoadingBox
import com.example.afigts.ui.common.ReturnStatusChip
import com.example.afigts.ui.common.SectionCard
import com.example.afigts.util.fmtYuan
import com.example.afigts.util.parseAmount
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(personId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<PersonDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showAddReturn by remember { mutableStateOf(false) }
    var editingReturn by remember { mutableStateOf<ReturnGift?>(null) }
    var deletingReturn by remember { mutableStateOf<ReturnGift?>(null) }

    suspend fun load() {
        detail = Repo.getPersonDetail(personId)
        loading = false
    }

    LaunchedEffect(personId) { load() }

    Scaffold(
        topBar = {
            AppTopBar(title = detail?.summary?.name ?: "人员详情", onBack = onBack)
        }
    ) { padding ->
        val d = detail
        if (loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        if (d == null) {
            EmptyHint("人员不存在或已被清理", Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            SectionCard(title = "人情往来概况") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.summary.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    ReturnStatusChip(d.summary.returnStatus == "returned")
                }
                Spacer(Modifier.height(8.dp))
                InfoRow("收礼合计", "${fmtYuan(d.summary.totalIn)}（${d.summary.inCount} 笔）")
                InfoRow("送礼合计", "${fmtYuan(d.summary.totalOut)}（${d.summary.outCount} 笔）")
                InfoRow(
                    "差额",
                    fmtYuan(d.summary.diff),
                    emphasis = true
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                Repo.setPersonStatus(
                                    personId,
                                    if (d.summary.returnStatus == "returned") "pending" else "returned"
                                )
                                load()
                            }
                        }
                    ) {
                        Text(if (d.summary.returnStatus == "returned") "标记为待回礼" else "标记为已回礼")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { showAddReturn = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("新增回礼")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "收礼记录（ta 随我的礼）") {
                if (d.incoming.isEmpty()) {
                    Text("暂无收礼记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    d.incoming.forEachIndexed { i, r ->
                        IncomingRow(r)
                        if (i != d.incoming.lastIndex) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "回礼记录（我随 ta 的礼）") {
                if (d.outgoing.isEmpty()) {
                    Text("暂无回礼记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    d.outgoing.forEachIndexed { i, r ->
                        OutgoingRow(
                            r,
                            onEdit = { editingReturn = r },
                            onDelete = { deletingReturn = r }
                        )
                        if (i != d.outgoing.lastIndex) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    val name = detail?.summary?.name.orEmpty()

    if (showAddReturn && name.isNotBlank()) {
        ReturnGiftDialog(
            title = "新增回礼 · $name",
            initial = null,
            onDismiss = { showAddReturn = false },
            onConfirm = { eventName, eventDate, amount, gift, giftAmount, remark ->
                showAddReturn = false
                scope.launch {
                    Repo.addReturnGift(name, eventName, eventDate, amount, gift, giftAmount, remark)
                    load()
                }
            }
        )
    }

    editingReturn?.let { editing ->
        ReturnGiftDialog(
            title = "编辑回礼 · $name",
            initial = editing,
            onDismiss = { editingReturn = null },
            onConfirm = { eventName, eventDate, amount, gift, giftAmount, remark ->
                editingReturn = null
                scope.launch {
                    Repo.updateReturnGift(editing.id, eventName, eventDate, amount, gift, giftAmount, remark)
                    load()
                }
            }
        )
    }

    deletingReturn?.let { target ->
        ConfirmDialog(
            title = "删除回礼记录",
            message = "确定删除这条回礼记录吗？",
            confirmText = "删除",
            onConfirm = {
                deletingReturn = null
                scope.launch { Repo.deleteReturnGift(target.id); load() }
            },
            onDismiss = { deletingReturn = null }
        )
    }
}

@Composable
private fun IncomingRow(r: GiftRecord) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                r.archiveName ?: "（未命名档案）",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(fmtYuan(r.total), fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            listOfNotNull(
                r.relation?.takeIf { it.isNotBlank() },
                r.gift?.takeIf { it.isNotBlank() }?.let { "礼品：$it" },
                r.createdAt.take(10)
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OutgoingRow(r: ReturnGift, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.eventName?.takeIf { it.isNotBlank() } ?: "回礼", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    r.eventDate?.takeIf { it.isNotBlank() },
                    r.gift?.takeIf { it.isNotBlank() }?.let { "礼品：$it" },
                    r.remark?.takeIf { it.isNotBlank() }
                ).joinToString(" · ").ifBlank { r.createdAt.take(10) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(fmtYuan(r.total), fontWeight = FontWeight.SemiBold)
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "编辑") }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除") }
    }
}

/** 回礼记录编辑弹窗（新增 / 编辑共用） */
@Composable
private fun ReturnGiftDialog(
    title: String,
    initial: ReturnGift?,
    onDismiss: () -> Unit,
    onConfirm: (
        eventName: String?, eventDate: String?, amount: Double,
        gift: String?, giftAmount: Double, remark: String?
    ) -> Unit
) {
    var eventName by remember { mutableStateOf(initial?.eventName ?: "") }
    var eventDate by remember { mutableStateOf(initial?.eventDate ?: "") }
    var amount by remember {
        mutableStateOf(if (initial == null || initial.amount == 0.0) "" else initial.amount.toString())
    }
    var gift by remember { mutableStateOf(initial?.gift ?: "") }
    var giftAmount by remember {
        mutableStateOf(if (initial == null || initial.giftAmount == 0.0) "" else initial.giftAmount.toString())
    }
    var remark by remember { mutableStateOf(initial?.remark ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Field("事由", eventName, { eventName = it }, placeholder = "如：ta 家乔迁")
                Spacer(Modifier.height(10.dp))
                Field("日期", eventDate, { eventDate = it }, placeholder = "2026-10-15")
                Spacer(Modifier.height(10.dp))
                Field(
                    "礼金金额（元）", amount, { amount = it },
                    keyboardType = KeyboardType.Decimal, placeholder = "0"
                )
                Spacer(Modifier.height(10.dp))
                Field("礼品名称", gift, { gift = it })
                Spacer(Modifier.height(10.dp))
                Field(
                    "礼品折算金额（元）", giftAmount, { giftAmount = it },
                    keyboardType = KeyboardType.Decimal
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
                onConfirm(
                    eventName.trim().ifBlank { null },
                    eventDate.trim().ifBlank { null },
                    parseAmount(amount),
                    gift.trim().ifBlank { null },
                    parseAmount(giftAmount),
                    remark.trim().ifBlank { null }
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
