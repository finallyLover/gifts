// ============================================================
// 新建 / 编辑礼簿档案（手机端整屏表单）
// ============================================================
package com.example.afigts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.afigts.data.Repo
import com.example.afigts.ui.common.AppTopBar
import com.example.afigts.ui.common.Field
import com.example.afigts.ui.common.LoadingBox
import com.example.afigts.ui.common.SectionCard
import kotlinx.coroutines.launch

private val BANQUET_TYPES = listOf("婚礼", "寿宴", "乔迁", "满月", "升学", "其他")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArchiveEditScreen(archiveId: String?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(archiveId != null) }
    var name by remember { mutableStateOf("") }
    var banquetType by remember { mutableStateOf("婚礼") }
    var eventDate by remember { mutableStateOf("") }
    var hostName by remember { mutableStateOf("") }
    var hostContact by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(archiveId) {
        if (archiveId != null) {
            Repo.listArchives(includeArchived = true).firstOrNull { it.id == archiveId }?.let { a ->
                name = a.name
                banquetType = a.banquetType
                eventDate = a.eventDate ?: ""
                hostName = a.hostName ?: ""
                hostContact = a.hostContact ?: ""
                remark = a.remark ?: ""
            }
            loading = false
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = if (archiveId == null) "新建档案" else "编辑档案",
                onBack = onDone,
                actions = {
                    TextButton(
                        enabled = !saving && name.isNotBlank(),
                        onClick = {
                            saving = true
                            error = null
                            scope.launch {
                                try {
                                    val date = eventDate.trim().ifBlank { null }
                                    val host = hostName.trim().ifBlank { null }
                                    val contact = hostContact.trim().ifBlank { null }
                                    val rmk = remark.trim().ifBlank { null }
                                    if (archiveId == null) {
                                        Repo.createArchive(name.trim(), banquetType, date, host, contact, rmk)
                                    } else {
                                        Repo.updateArchive(archiveId, name.trim(), banquetType, date, host, contact, rmk)
                                    }
                                    onDone()
                                } catch (e: Exception) {
                                    error = e.message ?: "保存失败"
                                    saving = false
                                }
                            }
                        }
                    ) { Text(if (saving) "保存中…" else "保存") }
                }
            )
        }
    ) { padding ->
        if (loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            SectionCard(title = "基本信息") {
                Field("档案名称（必填）", name, { name = it }, placeholder = "如：张三婚礼礼簿")
                Spacer(Modifier.height(10.dp))
                Text("宴席类型", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BANQUET_TYPES.forEach { type ->
                        FilterChip(
                            selected = banquetType == type,
                            onClick = { banquetType = type },
                            label = { Text(type) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Field(
                    "举办日期", eventDate, { eventDate = it },
                    placeholder = "2026-10-15", keyboardType = KeyboardType.Text
                )
            }
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "主人信息") {
                Field("主人姓名", hostName, { hostName = it })
                Spacer(Modifier.height(10.dp))
                Field("联系方式", hostContact, { hostContact = it }, keyboardType = KeyboardType.Phone)
                Spacer(Modifier.height(10.dp))
                Field("备注", remark, { remark = it }, singleLine = false)
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            val date = eventDate.trim().ifBlank { null }
                            val host = hostName.trim().ifBlank { null }
                            val contact = hostContact.trim().ifBlank { null }
                            val rmk = remark.trim().ifBlank { null }
                            if (archiveId == null) {
                                Repo.createArchive(name.trim(), banquetType, date, host, contact, rmk)
                            } else {
                                Repo.updateArchive(archiveId, name.trim(), banquetType, date, host, contact, rmk)
                            }
                            onDone()
                        } catch (e: Exception) {
                            error = e.message ?: "保存失败"
                            saving = false
                        }
                    }
                },
                enabled = !saving && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存档案") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
