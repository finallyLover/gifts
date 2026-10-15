// ============================================================
// 统计报表：全局人情概况 / 单场礼簿统计
// ============================================================
package com.example.afigts.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.afigts.data.Archive
import com.example.afigts.data.ArchiveStat
import com.example.afigts.data.GlobalStat
import com.example.afigts.data.GroupStat
import com.example.afigts.data.Repo
import com.example.afigts.ui.common.AppTopBar
import com.example.afigts.ui.common.InfoRow
import com.example.afigts.ui.common.LoadingBox
import com.example.afigts.ui.common.SectionCard
import com.example.afigts.util.fmtYuan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen() {
    var loading by remember { mutableStateOf(true) }
    var global by remember { mutableStateOf(GlobalStat()) }
    var archives by remember { mutableStateOf<List<Archive>>(emptyList()) }
    var selectedArchiveId by remember { mutableStateOf<String?>(null) }
    var archiveStat by remember { mutableStateOf<ArchiveStat?>(null) }

    suspend fun loadBase() {
        global = Repo.globalStat()
        archives = Repo.listArchives(includeArchived = true)
        loading = false
    }

    LaunchedEffect(Unit) { loadBase() }

    LaunchedEffect(selectedArchiveId) {
        archiveStat = selectedArchiveId?.let { Repo.archiveStat(it) }
    }

    Scaffold(topBar = { AppTopBar(title = "统计报表") }) { padding ->
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
            SectionCard(title = "全局人情概况") {
                InfoRow("收礼合计", "${fmtYuan(global.totalIn)}（${global.inCount} 笔）")
                InfoRow("送礼合计", "${fmtYuan(global.totalOut)}（${global.outCount} 笔）")
                InfoRow("其中礼品折算（收/送）", "${fmtYuan(global.giftIn)} / ${fmtYuan(global.giftOut)}")
                InfoRow("净差额（收 - 送）", fmtYuan(global.netDiff), emphasis = true)
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "单场礼簿统计") {
                if (archives.isEmpty()) {
                    Text("暂无档案", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        archives.forEach { a ->
                            FilterChip(
                                selected = selectedArchiveId == a.id,
                                onClick = {
                                    selectedArchiveId = if (selectedArchiveId == a.id) null else a.id
                                },
                                label = { Text(a.name) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                    val stat = archiveStat
                    if (selectedArchiveId != null && stat != null) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        InfoRow("记录笔数", "${stat.totalCount} 笔（已确认 ${stat.confirmedCount} / 待核对 ${stat.pendingCount}）")
                        InfoRow("礼金合计", fmtYuan(stat.totalAmount))
                        InfoRow("礼品折算合计", fmtYuan(stat.giftAmount))
                        InfoRow("最终合计", fmtYuan(stat.totalAmount + stat.giftAmount), emphasis = true)
                        if (stat.byRelation.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("按关系分布", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            stat.byRelation.forEach { g -> GroupRow(g) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("按礼金区间分布", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        stat.amountBrackets.forEach { g -> GroupRow(g) }
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点选上方档案查看单场统计",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GroupRow(g: GroupStat) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(g.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${g.count} 笔", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(fmtYuan(g.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
