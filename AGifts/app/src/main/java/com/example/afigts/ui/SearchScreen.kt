// ============================================================
// 检索查询：人员 / 收礼记录 / 回礼记录 三组结果
//
// 与电脑端 SearchView.vue 对齐：
//   - 收礼记录 → 点击查看该记录所在页的原图（只读）
//   - 人员卡只展示姓名，不再跳转"人情明细"
// ============================================================
package com.example.afigts.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.afigts.data.GiftRecord
import com.example.afigts.data.PersonMatch
import com.example.afigts.data.ReturnGift
import com.example.afigts.data.SearchResult
import com.example.afigts.data.Repo
import com.example.afigts.data.AppDb
import com.example.afigts.ui.common.AppTopBar
import com.example.afigts.ui.common.EmptyHint
import com.example.afigts.util.fmtYuan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen() {
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var searchTick by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf(SearchResult()) }
    var searched by remember { mutableStateOf(false) }

    // 图片预览状态
    var viewerBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var viewerTitle by remember { mutableStateOf("") }
    var viewerLoading by remember { mutableStateOf(false) }
    var viewerError by remember { mutableStateOf<String?>(null) }

    // 输入即搜（keyword）+ 点搜索按钮（searchTick）都会重新检索
    LaunchedEffect(keyword, searchTick) {
        val kw = keyword.trim()
        if (kw.isEmpty()) {
            result = SearchResult()
            searched = false
        } else {
            result = Repo.searchAll(kw)
            searched = true
        }
    }

    /** 查看收礼记录所在页的原图（内部通用） */
    fun viewOriginal(r: GiftRecord) {
        val pid = r.pageId
        if (pid == null) return
        viewerTitle = "${r.archiveName ?: "礼簿"} · ${r.guestName}"
        viewerBitmap = null
        viewerError = null
        viewerLoading = true
        scope.launch {
            try {
                val relPath = withContext(Dispatchers.IO) { Repo.getPageFilePath(pid) }
                    ?: throw IllegalStateException("该记录未关联图片页")
                val absFile = File(AppDb.imageBaseDir, relPath)
                if (!absFile.exists()) {
                    throw IllegalStateException("原图不存在（${absFile.absolutePath}）")
                }
                val bmp = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(absFile.absolutePath)
                }
                if (bmp == null) throw IllegalStateException("图片解码失败")
                viewerBitmap = bmp
            } catch (e: Exception) {
                viewerError = e.message ?: "加载原图失败"
            } finally {
                viewerLoading = false
            }
        }
    }

    /**
     * 点击人员卡 → 查该人最近一条带图片页的收礼记录 → 查看原图
     * （用户明确要求：查看原图功能加在"成员"上，不是收礼记录上）
     */
    fun openPersonOriginal(personId: String, personName: String) {
        viewerTitle = personName
        viewerBitmap = null
        viewerError = null
        viewerLoading = true
        scope.launch {
            try {
                val rec = withContext(Dispatchers.IO) { Repo.getLatestRecordWithPage(personId) }
                    ?: throw IllegalStateException("「$personName」暂无关联图片页的收礼记录")
                viewOriginal(rec)
            } catch (e: Exception) {
                viewerError = e.message ?: "加载原图失败"
                viewerLoading = false
            }
        }
    }

    /** 关闭全屏查看器并释放位图 */
    fun closeViewer() {
        viewerBitmap?.recycle()
        viewerBitmap = null
        viewerError = null
        viewerLoading = false
    }

    // 用 Box 叠放：底层是检索页，上层是全屏原图查看器（直接打开，无弹窗窗口）
    Box(Modifier.fillMaxSize()) {
    Scaffold(topBar = { AppTopBar(title = "检索查询") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = { Text("输入姓名 / 关键词") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { searchTick++ }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )
            if (!searched) {
                EmptyHint("输入关键词后自动检索人员与往来记录")
            } else if (result.persons.isEmpty() && result.incoming.isEmpty() && result.outgoing.isEmpty()) {
                EmptyHint("没有找到与「${keyword.trim()}」相关的内容")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (result.persons.isNotEmpty()) {
                        item { GroupTitle("人员（${result.persons.size}）") }
                        items(result.persons, key = { "p-${it.id}" }) { p ->
                            PersonResultCard(p) { openPersonOriginal(p.id, p.name) }
                        }
                    }
                    if (result.incoming.isNotEmpty()) {
                        item { GroupTitle("收礼记录（${result.incoming.size}）") }
                        items(result.incoming, key = { "i-${it.id}" }) { r ->
                            IncomingResultCard(r)
                        }
                    }
                    if (result.outgoing.isNotEmpty()) {
                        item { GroupTitle("回礼记录（${result.outgoing.size}）") }
                        items(result.outgoing, key = { "o-${it.id}" }) { r ->
                            OutgoingResultCard(r)
                        }
                    }
                }
            }
        }
    }

        // ========= 全屏原图查看器（直接打开，无弹窗窗口）=========
        if (viewerBitmap != null || viewerLoading || viewerError != null) {
            // 返回键直接关闭查看器
            BackHandler { closeViewer() }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                when {
                    viewerLoading -> Text(
                        "加载中…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    viewerError != null -> Text(
                        viewerError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    )
                    viewerBitmap != null -> ZoomableImage(bitmap = viewerBitmap!!)
                }
                // 顶部：标题 + 关闭按钮
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        viewerTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    IconButton(onClick = { closeViewer() }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * 可双指缩放 / 拖动的图片预览组件。
 * 用 transformable 手势监听缩放和偏移。
 */
@Composable
private fun ZoomableImage(bitmap: android.graphics.Bitmap) {
    val scale = remember { mutableStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "原图",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offset.value.x
                    translationY = offset.value.y
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale.value = (scale.value * zoom).coerceIn(0.5f, 5f)
                        offset.value = Offset(
                            offset.value.x + pan.x,
                            offset.value.y + pan.y
                        )
                    }
                }
        )
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/**
 * 人员结果卡：点击查看该人最近一条带图收礼记录的原图
 */
@Composable
private fun PersonResultCard(p: PersonMatch, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "查看原图",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 收礼记录卡：纯展示，无点击
 * （用户明确要求"查看原图"功能加在人员卡上）
 */
@Composable
private fun IncomingResultCard(r: GiftRecord) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.guestName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(fmtYuan(r.total), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(
                    r.archiveName ?: "（未命名档案）",
                    r.relation?.takeIf { it.isNotBlank() },
                    r.createdAt.take(10)
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OutgoingResultCard(r: ReturnGift) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.personName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(fmtYuan(r.total), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(
                    r.eventName?.takeIf { it.isNotBlank() },
                    r.eventDate?.takeIf { it.isNotBlank() },
                    r.createdAt.take(10)
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
