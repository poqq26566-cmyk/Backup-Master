package com.example.p942818.backup

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份引擎 - 协调备份导出/导入
 */
object BackupEngine {

    private const val TAG = "BackupEngine"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    private const val BACKUP_ROOT = "BackupMaster"

    val backupTypes = BackupType.entries.toList()

    /** 获取备份根目录 */
    fun getBackupRootDir(context: Context): File {
        val extDir = context.getExternalFilesDir(null)
        return if (extDir != null) File(extDir, BACKUP_ROOT) else File(context.filesDir, BACKUP_ROOT)
    }

    /** 创建带时间戳的备份目录 */
    fun createTimestampBackupDir(context: Context): File {
        val dir = File(getBackupRootDir(context), "备份_${dateFormat.format(Date())}")
        dir.mkdirs(); return dir
    }

    /** 获取所有备份目录列表 */
    fun getBackupHistory(context: Context): List<File> {
        val root = getBackupRootDir(context)
        return root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** 获取目录摘要 */
    fun getBackupSummary(backupDir: File): String {
        val cats = listOf("APK", "SMS", "CallLog", "Wallpaper", "WiFi", "DesktopLayout", "Contacts")
        val parts = mutableListOf<String>()
        for (cat in cats) {
            val catDir = File(backupDir, cat)
            if (catDir.exists()) {
                val files = catDir.listFiles() ?: emptyArray()
                if (files.isNotEmpty()) {
                    parts.add("${cat}(${files.size}项, ${formatSize(files.sumOf { it.length() })})")
                }
            }
        }
        return if (parts.isEmpty()) "空备份" else parts.joinToString(" · ")
    }

    /** 扫描目录下所有备份文件按类型分类 */
    fun getBackupFilesByType(backupDir: File): Map<BackupType, List<File>> {
        val map = mutableMapOf<BackupType, List<File>>()
        map[BackupType.APK] = File(backupDir, "APK").takeIf { it.exists() }?.listFiles()?.toList() ?: emptyList()
        map[BackupType.SMS] = File(backupDir, "SMS").takeIf { it.exists() }?.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        map[BackupType.CALL_LOG] = File(backupDir, "CallLog").takeIf { it.exists() }?.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        map[BackupType.WALLPAPER] = File(backupDir, "Wallpaper").takeIf { it.exists() }?.listFiles()?.filter { it.extension == "png" || it.extension == "jpg" } ?: emptyList()
        map[BackupType.WIFI] = File(backupDir, "WiFi").takeIf { it.exists() }?.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        map[BackupType.DESKTOP_LAYOUT] = File(backupDir, "DesktopLayout").takeIf { it.exists() }?.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        map[BackupType.CONTACTS] = File(backupDir, "Contacts").takeIf { it.exists() }?.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        return map
    }

    /** 删除备份 */
    fun deleteBackup(backupDir: File): Boolean = try { backupDir.deleteRecursively(); true } catch (e: Exception) { false }

    /** ===== 导出备份 ===== */
    fun executeBackup(context: Context, type: BackupType, backupDir: File): BackupResult {
        Log.d(TAG, "开始备份: ${type.label}")
        return when (type) {
            BackupType.APK -> {
                val results = ApkBackup.backupAllApks(context, backupDir)
                val success = results.filter { it.success }
                BackupResult(BackupType.APK, success = results.none { !it.success },
                    fileSize = success.sumOf { it.fileSize }, itemCount = success.size,
                    errorMessage = if (results.any { !it.success }) "${results.count { !it.success }}个失败" else null)
            }
            BackupType.SMS -> SmsBackup.backupToJson(context, backupDir)
            BackupType.CALL_LOG -> CallLogBackup.backupToJson(context, backupDir)
            BackupType.WALLPAPER -> WallpaperBackup.backupWallpaper(context, backupDir)
            BackupType.WIFI -> WifiBackup.backupToJson(context, backupDir)
            BackupType.DESKTOP_LAYOUT -> DesktopLayoutBackup.backupDesktopLayout(context, backupDir)
            BackupType.CONTACTS -> ContactsBackup.backupToJson(context, backupDir)
        }
    }

    /** ===== 导入/恢复 ===== */
    fun executeRestore(context: Context, type: BackupType, file: File): BackupResult {
        Log.d(TAG, "开始恢复: ${type.label} <- ${file.name}")
        return when (type) {
            BackupType.SMS -> SmsBackup.restoreFromJson(context, file)
            BackupType.CALL_LOG -> CallLogBackup.restoreFromJson(context, file)
            BackupType.WALLPAPER -> WallpaperBackup.restoreWallpaper(context, file)
            BackupType.APK -> ApkBackup.installApk(context, file)
            BackupType.WIFI -> WifiBackup.restoreFromJson(context, file)
            BackupType.DESKTOP_LAYOUT -> DesktopLayoutBackup.restoreDesktopLayout(context, file)
            BackupType.CONTACTS -> ContactsBackup.restoreFromJson(context, file)
        }
    }

    /** 扫描目录下第一个备份文件 */
    fun findFirstBackupFile(backupDir: File, type: BackupType): File? {
        return getBackupFilesByType(backupDir)[type]?.firstOrNull()
    }

    fun getBackupDirSize(backupDir: File): Long =
        if (backupDir.isDirectory) backupDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        else backupDir.length()

    private fun formatSize(bytes: Long) = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format("%.1f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    data class BackupHistoryItem(
        val name: String, val dir: File, val time: Long, val size: Long, val summary: String
    )
}
