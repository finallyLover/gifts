// ============================================================
// 礼簿档案列表（手机端：搜索 + 卡片列表 + 新建）
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.afigts.data.Archive
import com.example.afigts.data.Repo
import com.example.afigts.ui.common.AppTopBar
import com.example.afigts.ui.common.ConfirmDialog
import com.example.afigts.ui.common.EmptyHint
import com.example.afigts.ui.common.LoadingBox
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveListScreen(
    onOpenArchive: (String) -> Unit,
    onNewArchive: () -> Unit,
    onEditArchive: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var archives by remember { mutableStateOf<List<Archive>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Archive?>(null) }
    var archiveToggleTarget by remember { mutableStateOf<Archive?>(null) }

    suspend fun reload() {
        archives = Repo.listArchives(null, false)
        counts = Repo.archiveRecordCounts()
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = { AppTopBar(title = "礼簿档案") },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewArchive) {
                Icon(Icons.Default.Add, contentDescription = "新建档案")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> LoadingBox()
                archives.isEmpty() -> EmptyHint("暂无档案，点击右下角 + 新建")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(
                            "共 ${archives.size} 本档案",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }
                    items(archives, key = { it.id }) { a ->
                        ArchiveCard(
                            archive = a,
                            recordCount = counts[a.id] ?: 0L,
                            menuOpen = menuFor == a.id,
                            onOpen = { onOpenArchive(a.id) },
                            onMenuOpenChange = { open -> menuFor = if (open) a.id else null },
                            onEdit = { menuFor = null; onEditArchive(a.id) },
                            onToggleArchive = { menuFor = null; archiveToggleTarget = a },
                            onDelete = { menuFor = null; deleteTarget = a }
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "删除档案",
            message = "确定删除「${target.name}」吗？该档案下的所有记录会一并删除，且不可恢复。",
            confirmText = "删除",
            onConfirm = {
                deleteTarget = null
                scope.launch { Repo.deleteArchive(target.id); reload() }
            },
            onDismiss = { deleteTarget = null }
        )
    }

    archiveToggleTarget?.let { target ->
        val toArchive = target.status != "archived"
        ConfirmDialog(
            title = if (toArchive) "归档档案" else "取消归档",
            message = if (toArchive) "归档后该档案不再显示在默认列表中，可随时取消归档。"
            else "「${target.name}」将重新显示在档案列表中。",
            onConfirm = {
                archiveToggleTarget = null
                scope.launch { Repo.setArchiveStatus(target.id, toArchive); reload() }
            },
            onDismiss = { archiveToggleTarget = null }
        )
    }
}

@Composable
private fun ArchiveCard(
    archive: Archive,
    recordCount: Long,
    menuOpen: Boolean,
    onOpen: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit
) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        archive.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (archive.status == "archived") {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                "已归档",
                                Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(
                        archive.banquetType,
                        archive.eventDate?.takeIf { it.isNotBlank() },
                        archive.hostName?.takeIf { it.isNotBlank() }?.let { "主人：$it" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "记录 $recordCount 笔",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { onMenuOpenChange(true) }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                    DropdownMenuItem(text = { Text("编辑档案") }, onClick = onEdit)
                    DropdownMenuItem(
                        text = { Text(if (archive.status == "archived") "取消归档" else "归档") },
                        onClick = onToggleArchive
                    )
                    DropdownMenuItem(text = { Text("删除档案") }, onClick = onDelete)
                }
            }
        }
    }
}
