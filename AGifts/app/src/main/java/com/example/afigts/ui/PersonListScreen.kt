// ============================================================
// 人情档案：人员列表（收 / 支 / 差额 + 回礼状态筛选）
// ============================================================
package com.example.afigts.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.afigts.data.PersonSummary
import com.example.afigts.data.Repo
import com.example.afigts.ui.common.AppTopBar
import com.example.afigts.ui.common.ChipRow
import com.example.afigts.ui.common.EmptyHint
import com.example.afigts.ui.common.LoadingBox
import com.example.afigts.ui.common.ReturnStatusChip
import com.example.afigts.util.fmtYuan

private val FILTERS = listOf("全部", "待回礼", "已回礼")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonListScreen(onOpenPerson: (String) -> Unit) {
    var keyword by remember { mutableStateOf("") }
    var searchTick by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(0) }
    var list by remember { mutableStateOf<List<PersonSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // 输入即搜（keyword）+ 点搜索按钮（searchTick）+ 切换筛选（filter）都会重新查询
    LaunchedEffect(keyword, filter, searchTick) {
        val status = when (filter) {
            1 -> "pending"
            2 -> "returned"
            else -> null
        }
        list = Repo.listPersonSummaries(keyword, status)
        loading = false
    }

    Scaffold(topBar = { AppTopBar(title = "人情档案") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = { Text("搜索姓名") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { searchTick++ }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )
            ChipRow(FILTERS, filter, { filter = it })
            Spacer(Modifier.height(6.dp))
            when {
                loading -> LoadingBox()
                list.isEmpty() -> EmptyHint("暂无人员档案，录入礼簿后自动生成")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(list, key = { it.id }) { p ->
                        PersonCard(p, onClick = { onOpenPerson(p.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonCard(p: PersonSummary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                ReturnStatusChip(p.returnStatus == "returned")
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Text("收 ${fmtYuan(p.totalIn)}（${p.inCount}笔）", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
                Text("支 ${fmtYuan(p.totalOut)}（${p.outCount}笔）", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "差额 ${fmtYuan(p.diff)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (p.diff >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}
