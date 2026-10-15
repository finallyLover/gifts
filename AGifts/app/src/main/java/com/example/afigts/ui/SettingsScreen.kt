// ============================================================
// 设置与备份：坚果云 WebDAV 配置 + 同步操作 + 应用信息
//
// 与电脑端 SettingsView.vue 结构对齐：
//   - 未配置 / 点击修改：显示配置表单（账号/应用密码/URL/远程目录 + 保存/测试/取消）
//   - 已配置：显示同步状态 + 推送/拉取/智能同步按钮
//
// 安卓端同步只传输数据库单文件（安卓不做图片录入）。
// 电脑端的 images/ 图片仍由电脑端自身推送/拉取，两端互不影响。
// ============================================================
package com.example.afigts.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.afigts.data.SyncConfig
import com.example.afigts.data.SyncInfo
import com.example.afigts.data.Repo
import com.example.afigts.sync.SyncManager
import com.example.afigts.ui.common.AppTopBar
import com.example.afigts.ui.common.ConfirmDialog
import com.example.afigts.ui.common.Field
import com.example.afigts.ui.common.InfoRow
import com.example.afigts.ui.common.SectionCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf("") }
    var appPassword by remember { mutableStateOf("") }
    var davUrl by remember { mutableStateOf(SyncManager.DEFAULT_DAV_URL) }
    var remoteDir by remember { mutableStateOf(SyncManager.DEFAULT_REMOTE_DIR) }
    var info by remember { mutableStateOf<SyncInfo?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var confirmPull by remember { mutableStateOf(false) }
    var confirmPush by remember { mutableStateOf(false) }
    var dbPath by remember { mutableStateOf("") }
    var dbSize by remember { mutableStateOf(0L) }
    var archiveCount by remember { mutableStateOf(0L) }
    var recordCount by remember { mutableStateOf(0L) }
    var imageCount by remember { mutableStateOf(0L) }
    var showForm by remember { mutableStateOf(false) }

    suspend fun reload() {
        SyncManager.loadConfig()?.let { cfg ->
            account = cfg.account
            appPassword = cfg.appPassword
            davUrl = cfg.davUrl
            remoteDir = cfg.remoteDir
        }
        info = SyncManager.syncInfo()
        dbPath = com.example.afigts.data.AppDb.dbFile.absolutePath
        dbSize = Repo.dbSizeBytes()
        val (a, r) = Repo.counts()
        archiveCount = a
        recordCount = r
        imageCount = Repo.imageCount()
    }

    LaunchedEffect(Unit) { reload() }

    fun currentConfig() = SyncConfig(
        account = account.trim(),
        appPassword = appPassword.trim(),
        davUrl = davUrl.trim().ifBlank { SyncManager.DEFAULT_DAV_URL },
        remoteDir = remoteDir.trim().ifBlank { SyncManager.DEFAULT_REMOTE_DIR }
    )

    fun run(label: String, block: suspend () -> String) {
        busy = label
        message = null
        scope.launch {
            try {
                val ok = block()
                isError = false
                message = ok
            } catch (e: Exception) {
                isError = true
                message = "${label}失败：${e.message ?: "未知错误"}"
            } finally {
                busy = null
                reload()
            }
        }
    }

    Scaffold(topBar = { AppTopBar(title = "设置与备份") }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            // ========== 坚果云同步（与电脑端同一卡片内切换表单/按钮） ==========
            SectionCard(title = "坚果云同步（WebDAV）") {
                val hasConfig = info?.hasConfig == true
                val showCfgForm = showForm || !hasConfig

                if (showCfgForm) {
                    // ---- 配置表单 ----
                    Field("坚果云账号（邮箱）", account, { account = it }, keyboardType = KeyboardType.Email)
                    Spacer(Modifier.height(10.dp))
                    Field(
                        "应用密码（非登录密码）", appPassword, { appPassword = it },
                        keyboardType = KeyboardType.Password
                    )
                    Spacer(Modifier.height(10.dp))
                    Field("WebDAV 地址", davUrl, { davUrl = it })
                    Spacer(Modifier.height(10.dp))
                    Field("远程目录名", remoteDir, { remoteDir = it })
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "应用密码需在坚果云「安全选项 → 添加应用」中生成；远程目录会自动创建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Button(
                            enabled = busy == null && account.isNotBlank() && appPassword.isNotBlank(),
                            onClick = {
                                run("保存配置") {
                                    SyncManager.saveConfig(currentConfig())
                                    // 保存成功后切换到只读 + 按钮视图
                                    showForm = false
                                    "配置已保存"
                                }
                            }
                        ) { Text("保存配置") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = busy == null && account.isNotBlank() && appPassword.isNotBlank(),
                            onClick = { run("测试连接") { SyncManager.testConnection(currentConfig()) } }
                        ) { Text("测试连接") }
                        if (hasConfig) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                enabled = busy == null,
                                onClick = { showForm = false }
                            ) { Text("取消") }
                        }
                    }
                } else {
                    // ---- 已配置：只读信息 + 同步按钮（与电脑端一致） ----
                    info?.let { i ->
                        InfoRow("配置状态", if (i.hasConfig) "已配置" else "未配置")
                        InfoRow("本机设备 ID", i.deviceId)
                        InfoRow("上次同步", i.lastSyncAt ?: "从未同步")
                        InfoRow("上次推送", i.lastPushAt ?: "—")
                        InfoRow("上次拉取", i.lastPullAt ?: "—")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "「拉取」会用云端数据覆盖本机数据库，请谨慎操作。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Button(
                            enabled = busy == null,
                            onClick = { confirmPush = true }
                        ) { Text("推送") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = busy == null,
                            onClick = { confirmPull = true }
                        ) { Text("拉取") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = busy == null,
                            onClick = { run("智能同步") { SyncManager.smartSync().message } }
                        ) { Text("同步") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = busy == null,
                            onClick = { showForm = true }
                        ) { Text("编辑") }
                    }
                }

                busy?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("$it 进行中…", style = MaterialTheme.typography.bodySmall)
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ========== 应用信息 ==========
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "应用信息") {
                InfoRow("档案数量", "$archiveCount 本")
                InfoRow("记录条数", "$recordCount 条")
                InfoRow("图片张数", "$imageCount 张")
                InfoRow(
                    "数据库大小",
                    if (dbSize < 1024) "$dbSize B" else "%.1f KB".format(dbSize / 1024.0)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "数据库位置：$dbPath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "本地优先：所有数据保存在本机，联网同步完全由你手动触发。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmPush) {
        ConfirmDialog(
            title = "推送数据",
            message = "将本机数据库上传到坚果云（覆盖云端同名文件）。确定继续？",
            confirmText = "推送",
            onConfirm = {
                confirmPush = false
                run("推送") { SyncManager.push().message }
            },
            onDismiss = { confirmPush = false }
        )
    }

    if (confirmPull) {
        ConfirmDialog(
            title = "拉取数据",
            message = "将用坚果云上的数据库覆盖本机数据库，本机现有数据将被替换（旧库会备份为 gifts.db.bak）。确定继续？",
            confirmText = "拉取覆盖",
            onConfirm = {
                confirmPull = false
                run("拉取") { SyncManager.pull().message }
            },
            onDismiss = { confirmPull = false }
        )
    }
}
