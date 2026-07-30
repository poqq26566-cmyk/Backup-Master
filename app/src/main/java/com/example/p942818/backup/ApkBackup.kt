package com.example.p942818.backup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * APK 安装包备份模块 - 导出/导入（安装）APK
 */
object ApkBackup {

    private const val TAG = "ApkBackup"

    data class InstalledApp(
        val packageName: String, val appName: String,
        val versionName: String, val versionCode: Long,
        val sourceDir: String, val isSystemApp: Boolean,
        val firstInstallTime: Long, val lastUpdateTime: Long
    )

    fun getInstalledApps(context: Context, includeSystem: Boolean = false): List<InstalledApp> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val packages: List<PackageInfo> = pm.getInstalledPackages(0)
        return packages.filter { includeSystem || !isSystemApp(it) }
            .sortedBy { it.applicationInfo?.loadLabel(pm).toString() }
            .map { pkg ->
                val label = pkg.applicationInfo?.loadLabel(pm)?.toString() ?: pkg.packageName
                val srcDir = pkg.applicationInfo?.sourceDir ?: ""
                InstalledApp(
                    packageName = pkg.packageName, appName = label,
                    versionName = pkg.versionName ?: "未知",
                    versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode
                    else @Suppress("DEPRECATION") pkg.versionCode.toLong(),
                    sourceDir = srcDir, isSystemApp = isSystemApp(pkg),
                    firstInstallTime = pkg.firstInstallTime, lastUpdateTime = pkg.lastUpdateTime
                )
            }
    }

    private fun isSystemApp(pkg: PackageInfo): Boolean =
        (pkg.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0

    private fun copyFile(src: String, dst: File) {
        FileInputStream(src).use { input -> FileOutputStream(dst).use { input.copyTo(it) } }
    }

    /** 导出单个 APK */
    fun backupApk(context: Context, app: InstalledApp, backupDir: File): BackupResult {
        return try {
            val dir = File(backupDir, "APK").also { it.mkdirs() }
            val safeName = app.appName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val ver = app.versionName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val file = File(dir, "${safeName}_v${ver}.apk")
            if (file.exists()) file.delete()

            // 尝试提权复制，失败则用普通复制
            if (ShizukuHelper.hasPrivilege()) {
                val r = ShizukuHelper.execWithPrivilege("cp \"${app.sourceDir}\" \"${file.absolutePath}\"")
                if (!r.isSuccess || !file.exists() || file.length() == 0L) {
                    copyFile(app.sourceDir, file)
                }
            } else {
                copyFile(app.sourceDir, file)
            }

            if (file.exists() && file.length() > 0)
                BackupResult(BackupType.APK, true, file.absolutePath, file.length(), 1)
            else
                BackupResult(BackupType.APK, false, errorMessage = "APK 文件复制失败")
        } catch (e: Exception) {
            BackupResult(BackupType.APK, false, errorMessage = "备份失败: ${e.message}")
        }
    }

    /** 备份选中的应用 */
    fun backupSelectedApps(
        context: Context, apps: List<InstalledApp>, backupDir: File,
        progressCallback: ((Int, Int, String) -> Unit)? = null
    ): List<BackupResult> {
        return apps.mapIndexed { i, app ->
            progressCallback?.invoke(i + 1, apps.size, app.appName)
            backupApk(context, app, backupDir)
        }
    }

    fun backupAllApks(
        context: Context, backupDir: File, includeSystem: Boolean = false,
        progressCallback: ((Int, Int, String) -> Unit)? = null
    ): List<BackupResult> {
        val apps = getInstalledApps(context, includeSystem)
        return apps.mapIndexed { i, app ->
            progressCallback?.invoke(i + 1, apps.size, app.appName)
            backupApk(context, app, backupDir)
        }
    }

    /** ===== 安装 APK（恢复） ===== */
    fun installApk(context: Context, apkFile: File): BackupResult {
        return try {
            // 尝试用 Shizuku/Root 静默安装
            if (ShizukuHelper.hasPrivilege()) {
                val r = ShizukuHelper.execWithPrivilege(
                    "pm install -r \"${apkFile.absolutePath}\""
                )
                if (r.isSuccess) {
                    return BackupResult(BackupType.APK, true, itemCount = 1)
                }
                // 失败则回退到 Activity 安装
            }

            // 普通 Intent 安装
            val uri: Uri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val authority = "${context.packageName}.fileprovider"
                uri = FileProvider.getUriForFile(context, authority, apkFile)
            } else {
                uri = Uri.fromFile(apkFile)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            BackupResult(BackupType.APK, true, itemCount = 1, errorMessage = "已启动安装界面")
        } catch (e: Exception) {
            BackupResult(BackupType.APK, false, errorMessage = "安装失败: ${e.message}")
        }
    }
}
