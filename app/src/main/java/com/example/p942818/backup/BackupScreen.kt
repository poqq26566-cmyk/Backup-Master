package com.example.p942818.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.p942818.backup.BackupEngine.BackupHistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardColors = listOf(
    Color(0xFF667EEA), Color(0xFF764BA2), Color(0xFFF093FB),
    Color(0xFFF5576C), Color(0xFF4FACFE), Color(0xFF00C9FF)
)

data class BackupTypeItem(val type: BackupType, var selected: Boolean = true)

// ========== 页面模式 ==========
private enum class PageMode { HOME, APP_PICKER, BACKUP_DETAIL, RESTORE_PICK, RESTORE_CONFIRM }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupMainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf(BackupEngine.backupTypes.map { BackupTypeItem(it) }) }
    var isBusy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressText by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<List<BackupHistoryItem>>(emptyList()) }
    var showAllHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(PageMode.HOME) }
    var selectedBackupDir by remember { mutableStateOf<File?>(null) }
    var restoreResults by remember { mutableStateOf<List<RestoreResultItem>>(emptyList()) }
    var allApps by remember { mutableStateOf<List<ApkBackup.InstalledApp>>(emptyList()) }
    var selectedApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var appSearch by remember { mutableStateOf("") }

    BackHandler(enabled = mode != PageMode.HOME) {
        mode = PageMode.HOME
        restoreResults = emptyList()
    }
    var appLoading by remember { mutableStateOf(false) }
    var backupContainApk by remember { mutableStateOf(false) }

    // Shizuku 授权状态
    var shizukuDialog by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    fun reloadHistory() {
        history = BackupEngine.getBackupHistory(context).map { dir ->
            BackupHistoryItem(dir.name, dir, dir.lastModified(),
                BackupEngine.getBackupDirSize(dir), BackupEngine.getBackupSummary(dir))
        }
    }
    LaunchedEffect(Unit) { reloadHistory() }

    // ===== 执行备份 =====
    fun doBackup(sel: List<BackupType>, apkApps: List<ApkBackup.InstalledApp> = emptyList()) {
        scope.launch {
            isBusy = true; progress = 0f; progressText = "准备中..."
            try {
                val backupDir = withContext(Dispatchers.IO) { BackupEngine.createTimestampBackupDir(context) }
                val total = sel.size
                sel.forEachIndexed { idx, type ->
                    progress = idx.toFloat() / total; progressText = "备份: ${type.label}"
                    val result = withContext(Dispatchers.IO) {
                        if (type == BackupType.APK && apkApps.isNotEmpty()) {
                            val results = ApkBackup.backupSelectedApps(context, apkApps, backupDir) { i, n, name ->
                                progressText = "备份APK: [$i/$n] $name"
                            }
                            BackupResult(BackupType.APK, success = results.none { !it.success },
                                fileSize = results.filter { it.success }.sumOf { it.fileSize },
                                itemCount = results.size)
                        } else {
                            BackupEngine.executeBackup(context, type, backupDir)
                        }
                    }
                    progressText = "✅ ${type.label} 完成"
                    progress = (idx + 1f) / total
                }
                Toast.makeText(context, "🎉 备份完成！", Toast.LENGTH_SHORT).show()
                reloadHistory()
            } catch (e: Exception) {
                Toast.makeText(context, "❌ 备份失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally { isBusy = false; progress = 0f; progressText = "" }
        }
    }

    // ===== 执行恢复 =====
    fun doRestore(dir: File) {
        scope.launch {
            isBusy = true; progress = 0f; progressText = "准备恢复..."
            try {
                val files = BackupEngine.getBackupFilesByType(dir)
                val total = files.values.sumOf { it.size }
                var done = 0
                val results = mutableListOf<RestoreResultItem>()

                for ((type, fileList) in files) {
                    for (file in fileList) {
                        progress = done.toFloat() / total.coerceAtLeast(1)
                        progressText = "恢复: ${type.label} ← ${file.name}"
                        val result = withContext(Dispatchers.IO) {
                            BackupEngine.executeRestore(context, type, file)
                        }
                        results.add(RestoreResultItem(type, file.name, result.success, result.errorMessage))
                        done++
                    }
                }
                restoreResults = results
                mode = PageMode.RESTORE_CONFIRM
                Toast.makeText(context, "✅ 恢复完成！", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "❌ 恢复失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally { isBusy = false; progress = 0f; progressText = "" }
        }
    }

    val selectedCount = items.count { it.selected }
    val allSelected = items.all { it.selected }

    // ========== 顶部栏 ==========
    val topBarTitle = when (mode) {
        PageMode.HOME -> "备份大师"
        PageMode.APP_PICKER -> "选择要备份的应用"
        PageMode.BACKUP_DETAIL -> selectedBackupDir?.name ?: "备份详情"
        PageMode.RESTORE_PICK -> "选择恢复源"
        PageMode.RESTORE_CONFIRM -> "恢复结果"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(topBarTitle, fontWeight = FontWeight.Bold)
                        if (mode == PageMode.HOME) {
                            Text(ShizukuHelper.getPrivilegeDescription(),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                navigationIcon = {
                    if (mode != PageMode.HOME) {
                        IconButton(onClick = { mode = PageMode.HOME; restoreResults = emptyList() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    }
                },
                actions = {
                    when (mode) {
                        PageMode.HOME -> {
                            TextButton(onClick = { val sel = !allSelected; items = items.map { it.copy(selected = sel) } }) {
                                Text(if (allSelected) "取消全选" else "全选")
                            }
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Filled.Settings, "设置")
                            }
                        }
                        else -> {}
                    }
                }
            )
        },
        floatingActionButton = {
            when (mode) {
                PageMode.HOME -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // 恢复按钮
                        FloatingActionButton(
                            onClick = { mode = PageMode.RESTORE_PICK; reloadHistory() },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) { Icon(Icons.Filled.Restore, "恢复", modifier = Modifier.size(24.dp)) }

                        // 备份按钮
                        ExtendedFloatingActionButton(
                            onClick = {
                                if (isBusy) return@ExtendedFloatingActionButton
                                val sel = items.filter { it.selected }.map { it.type }
                                if (sel.isEmpty()) { Toast.makeText(context, "请选择备份项", Toast.LENGTH_SHORT).show(); return@ExtendedFloatingActionButton }
                                val missing = mutableListOf<String>()
                                sel.forEach { type -> PermissionManager.requiredPermissions[type]?.forEach { perm ->
                                    if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) missing.add(perm)
                                } }
                                if (missing.isNotEmpty()) { permLauncher.launch(missing.toTypedArray()); Toast.makeText(context, "请授予权限", Toast.LENGTH_SHORT).show(); return@ExtendedFloatingActionButton }
                                // 如果选了APK，先去应用选择页
                                if (sel.contains(BackupType.APK)) {
                                    backupContainApk = true
                                    appLoading = true; appSearch = ""
                                    scope.launch(Dispatchers.IO) {
                                        allApps = ApkBackup.getInstalledApps(context, false)
                                        appLoading = false
                                    }
                                    mode = PageMode.APP_PICKER
                                    selectedApps = emptySet()
                                    return@ExtendedFloatingActionButton
                                }
                                doBackup(sel)
                            },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            icon = { Icon(Icons.Filled.Backup, null) },
                            text = { Text(if (isBusy) "备份中..." else "开始备份($selectedCount)") }
                        )
                    }
                }
                PageMode.RESTORE_PICK -> {}
                PageMode.APP_PICKER -> {}
                PageMode.BACKUP_DETAIL -> {
                    val dir = selectedBackupDir
                    if (dir != null) {
                        ExtendedFloatingActionButton(
                            onClick = { doRestore(dir) },
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            icon = { Icon(Icons.Filled.RestorePage, null) },
                            text = { Text("恢复此备份") }
                        )
                    }
                }
                PageMode.RESTORE_CONFIRM -> {}
            }
        }
    ) { pad ->
        when (mode) {
            PageMode.HOME -> HomeContent(pad, context, items, isBusy, progress, progressText, history, showAllHistory,
                onToggleAll = { showAllHistory = it },
                onToggleItem = { t -> items = items.map { if (it.type == t) it.copy(selected = !it.selected) else it } },
                onBackupDetail = { dir -> selectedBackupDir = dir; mode = PageMode.BACKUP_DETAIL },
                onDeleteHistory = { dir -> scope.launch { withContext(Dispatchers.IO) { BackupEngine.deleteBackup(dir) }; reloadHistory() } },
                onShizukuRequest = { shizukuDialog = true }
            )
            PageMode.APP_PICKER -> AppPickerScreen(pad, allApps, selectedApps, appSearch, appLoading,
                onSearchChange = { appSearch = it },
                onToggleApp = { pkg ->
                    selectedApps = if (selectedApps.contains(pkg)) selectedApps - pkg else selectedApps + pkg
                },
                onConfirm = {
                    val apkApps = allApps.filter { selectedApps.contains(it.packageName) }
                    val otherTypes = items.filter { it.selected && it.type != BackupType.APK }.map { it.type }
                    val sel = otherTypes + BackupType.APK
                    mode = PageMode.HOME
                    doBackup(sel, apkApps)
                },
                onCancel = { mode = PageMode.HOME }
            )
            PageMode.BACKUP_DETAIL -> BackupDetailContent(pad, selectedBackupDir, context)
            PageMode.RESTORE_PICK -> RestorePickContent(pad, history, onPick = { dir -> selectedBackupDir = dir; mode = PageMode.BACKUP_DETAIL })
            PageMode.RESTORE_CONFIRM -> RestoreResultContent(pad, restoreResults)
        }
    }

    // ====== Shizuku 授权弹窗 ======
    if (shizukuDialog) {
        ShizukuAuthDialog(
            onDismiss = { shizukuDialog = false },
            context = context
        )
    }

    // ====== 设置弹窗 ======
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Settings, null, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp)); Text("设置")
            }},
            text = {
                Column {
                    Text("权限状态", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("• ${ShizukuHelper.getPrivilegeDescription()}")
                    Spacer(Modifier.height(4.dp))
                    Text("• ${if (ShizukuHelper.isShizukuInstalled()) "Shizuku 已安装" else "Shizuku 未安装（不影响 Root）"}")
                    Spacer(Modifier.height(12.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(0.5f))) {
                        Column(Modifier.padding(12.dp)) {
                            Text("💡 Shizuku 提权", fontWeight = FontWeight.SemiBold)
                            Text("备份 WiFi 密码、桌面布局、静默安装/恢复需要 Shizuku/Root 提权。点击下方按钮可请求授权。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false; shizukuDialog = true }) { Text("Shizuku 授权") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("关闭") }}
        )
    }
}

// ====== Shizuku 授权弹窗 ======
@Composable
private fun ShizukuAuthDialog(onDismiss: () -> Unit, context: Context) {
    var status by remember { mutableStateOf("") }
    var granted by remember { mutableStateOf(ShizukuHelper.isShizukuGranted()) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, null, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp)); Text("Shizuku 授权")
        }},
        text = {
            Column {
                if (granted) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(0.15f))) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("✅ Shizuku 已授权", fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                        }
                    }
                } else if (ShizukuHelper.isShizukuInstalled()) {
                    Text("Shizuku 服务运行中，点击下方按钮请求授权。")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        scope.launch(Dispatchers.IO) {
                            ShizukuHelper.requestShizukuPermission()
                            delay(500)
                            granted = ShizukuHelper.isShizukuGranted()
                        }
                    }) { Text("请求 Shizuku 授权") }
                } else {
                    Text("未检测到 Shizuku 服务。请先安装 Shizuku 应用并启动服务。")
                    Spacer(Modifier.height(8.dp))
                    Text("• 下载 Shizuku 应用", fontWeight = FontWeight.SemiBold)
                    Text("• 启动 Shizuku 服务（无线调试/ADB/Root）")
                    Text("• 返回此页面重新授权", fontWeight = FontWeight.SemiBold)
                }
                if (status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(status, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = {
                ShizukuHelper.detectPrivilege()
                onDismiss()
            }) { Text("刷新状态") }
        }
    )
}

// ====== 首页 ======
@Composable
private fun HomeContent(
    pad: PaddingValues, context: Context, items: List<BackupTypeItem>,
    isBusy: Boolean, progress: Float, progressText: String,
    history: List<BackupHistoryItem>, showAllHistory: Boolean,
    onToggleAll: (Boolean) -> Unit, onToggleItem: (BackupType) -> Unit,
    onBackupDetail: (File) -> Unit, onDeleteHistory: (File) -> Unit,
    onShizukuRequest: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 进度条
        if (isBusy) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp)); Text(progressText, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                    }
                }
            }
        }

        // 提权状态条
        item {
            Card(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onShizukuRequest),
                colors = CardDefaults.cardColors(containerColor = when {
                    ShizukuHelper.getPrivilegeLevel() == ShizukuHelper.PrivilegeLevel.SHIZUKU && ShizukuHelper.isShizukuGranted() -> Color(0xFF4CAF50).copy(0.12f)
                    ShizukuHelper.getPrivilegeLevel() == ShizukuHelper.PrivilegeLevel.ROOT -> Color(0xFFFF9800).copy(0.12f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(0.5f)
                })
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ShizukuHelper.hasPrivilege()) Icons.Filled.Shield else Icons.Filled.VerifiedUser, null, Modifier.size(20.dp),
                        tint = if (ShizukuHelper.hasPrivilege()) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ShizukuHelper.getPrivilegeDescription(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("点此管理授权", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 标题
        item { Text("选择备份内容", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }

        // 备份卡片网格
        val chunks = items.chunked(2)
        items(chunks) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { item ->
                    BackupCard(item = item, modifier = Modifier.weight(1f), onClick = { onToggleItem(item.type) })
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // 历史
        item { Spacer(Modifier.height(4.dp)); HorizontalDivider(); Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("备份历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (history.isNotEmpty()) TextButton(onClick = { onToggleAll(!showAllHistory) }) { Text(if (showAllHistory) "收起" else "全部(${history.size})") }
            }
        }

        if (history.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.4f))) {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f))
                        Spacer(Modifier.height(8.dp)); Text("暂无备份记录", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f))
                    }
                }
            }
        } else {
            val list = if (showAllHistory) history else history.take(3)
            items(list, key = { it.dir.absolutePath }) { h ->
                HistoryCard(h, onTap = { onBackupDetail(h.dir) }, onDelete = { onDeleteHistory(h.dir) })
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

// ====== 备份详情页 ======
@Composable
private fun BackupDetailContent(pad: PaddingValues, backupDir: File?, context: Context) {
    if (backupDir == null) return
    val files = remember(backupDir) { BackupEngine.getBackupFilesByType(backupDir) }

    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(0.5f))) {
                Column(Modifier.padding(16.dp)) {
                    Text(backupDir.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("总大小: ${formatSize(BackupEngine.getBackupDirSize(backupDir))}", style = MaterialTheme.typography.bodySmall)
                    Text(BackupEngine.getBackupSummary(backupDir), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        for ((type, fileList) in files) {
            if (fileList.isEmpty()) continue
            item {
                Column {
                    Text(type.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                }
            }
            items(fileList) { file ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f)),
                    shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Description, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatSize(file.length()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

// ====== 选择恢复源 ======
@Composable
private fun RestorePickContent(pad: PaddingValues, history: List<BackupHistoryItem>, onPick: (File) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("选择要恢复的备份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (history.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.4f))) {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f))
                        Spacer(Modifier.height(8.dp)); Text("暂无备份可恢复", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f))
                    }
                }
            }
        }
        items(history, key = { it.dir.absolutePath }) { h ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onPick(h.dir) }, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.name, fontWeight = FontWeight.Medium)
                        Text(h.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatSize(h.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f))
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

// ====== 恢复结果 ======
data class RestoreResultItem(val type: BackupType, val fileName: String, val success: Boolean, val message: String?)

@Composable
private fun RestoreResultContent(pad: PaddingValues, results: List<RestoreResultItem>) {
    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(0.1f))) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(28.dp), tint = Color(0xFF4CAF50))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("恢复完成", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("成功 ${results.count { it.success }} / 共 ${results.size}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        items(results) { r ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (r.success) MaterialTheme.colorScheme.surfaceVariant.copy(0.3f) else Color(0xFFE53935).copy(0.08f))) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (r.success) Icons.Filled.CheckCircle else Icons.Filled.Error, null,
                        modifier = Modifier.size(18.dp), tint = if (r.success) Color(0xFF4CAF50) else Color(0xFFE53935))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("[${r.type.label}] ${r.fileName}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        if (r.message != null) Text(r.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

// ========== 备份卡片 ==========
@Composable
private fun BackupCard(item: BackupTypeItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = CardColors[item.type.ordinal % CardColors.size]
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (item.selected) 4.dp else 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(0.15f)), contentAlignment = Alignment.Center) {
                    Text(item.type.icon, fontSize = 24.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text(item.type.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(item.type.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            }
            if (item.selected) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// ========== 历史卡片 ==========
@Composable
private fun HistoryCard(item: BackupHistoryItem, onTap: () -> Unit, onDelete: () -> Unit) {
    var confirmDel by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f))
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    Text(formatTs(item.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f))
                    Spacer(Modifier.width(8.dp)); Text(formatSize(item.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f))
                }
            }
            IconButton(onClick = { confirmDel = true }) {
                Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error.copy(0.7f), modifier = Modifier.size(20.dp))
            }
        }
    }
    if (confirmDel) {
        AlertDialog(onDismissRequest = { confirmDel = false }, title = { Text("删除备份") }, text = { Text("确定删除此备份？不可恢复。") },
            confirmButton = { TextButton(onClick = { confirmDel = false; onDelete() }) { Text("删除", color = MaterialTheme.colorScheme.error) }},
            dismissButton = { TextButton(onClick = { confirmDel = false }) { Text("取消") }})
    }
}

// ========== 应用选择器 ==========
@Composable
private fun AppPickerScreen(
    pad: PaddingValues, allApps: List<ApkBackup.InstalledApp>,
    selectedApps: Set<String>, search: String, loading: Boolean,
    onSearchChange: (String) -> Unit, onToggleApp: (String) -> Unit,
    onConfirm: () -> Unit, onCancel: () -> Unit
) {
    val filtered = remember(allApps, search) {
        if (search.isBlank()) allApps
        else allApps.filter { it.appName.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(pad)) {
        // 搜索栏
        OutlinedTextField(
            value = search, onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜索应用...") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true, shape = RoundedCornerShape(12.dp)
        )

        // 统计
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("共 ${filtered.size} 个应用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("已选 ${selectedApps.size}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                if (selectedApps.size == filtered.size) filtered.forEach { onToggleApp(it.packageName) }
                else filtered.forEach { if (!selectedApps.contains(it.packageName)) onToggleApp(it.packageName) }
            }) { Text(if (selectedApps.size >= filtered.size) "取消全选" else "全选") }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f))
                    Spacer(Modifier.height(8.dp)); Text("未找到应用", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    val checked = selectedApps.contains(app.packageName)
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onToggleApp(app.packageName) },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(0.5f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(0.2f)
                        )
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = { onToggleApp(app.packageName) })
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.appName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${app.packageName} · v${app.versionName}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (app.isSystemApp) {
                                Text("系统", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.background(MaterialTheme.colorScheme.tertiaryContainer.copy(0.4f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        // 底部确认按钮
        Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    enabled = selectedApps.isNotEmpty()
                ) { Text("备份 ${selectedApps.size} 个应用") }
            }
        }
    }
}

// ========== 工具 ==========
private val tf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private fun formatTs(t: Long) = tf.format(Date(t))
private fun formatSize(b: Long) = when {
    b >= 1L shl 30 -> String.format("%.1f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format("%.1f MB", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> String.format("%.1f KB", b / (1L shl 10).toDouble())
    else -> "$b B"
}
