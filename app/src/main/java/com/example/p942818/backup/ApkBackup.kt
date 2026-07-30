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
        val firstInstallTime: Long, val lastUpdateTime: Long,
        val splitSourceDirs: List<String> = emptyList() // 分包App(Split APK)的额外split文件路径
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
                val splits = pkg.applicationInfo?.splitSourceDirs?.toList() ?: emptyList()
                InstalledApp(
                    packageName = pkg.packageName, appName = label,
                    versionName = pkg.versionName ?: "未知",
                    versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode
                    else @Suppress("DEPRECATION") pkg.versionCode.toLong(),
                    sourceDir = srcDir, isSystemApp = isSystemApp(pkg),
                    firstInstallTime = pkg.firstInstallTime, lastUpdateTime = pkg.lastUpdateTime,
                    splitSourceDirs = splits
                )
            }
    }

    private fun isSystemApp(pkg: PackageInfo): Boolean =
        (pkg.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0

    private fun copyFile(src: String, dst: File) {
        FileInputStream(src).use { input -> FileOutputStream(dst).use { input.copyTo(it) } }
    }

    /** 导出单个 APK（如果是分包App，会把base+所有split都存进同一个文件夹里） */
    fun backupApk(context: Context, app: InstalledApp, backupDir: File): BackupResult {
        return try {
            val dir = File(backupDir, "APK").also { it.mkdirs() }
            val safeName = app.appName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val ver = app.versionName.replace(Regex("[/\\\\:*?\"<>|]"), "_")

            if (app.splitSourceDirs.isEmpty()) {
                // 普通单文件App
                val file = File(dir, "${safeName}_v${ver}.apk")
                if (file.exists()) file.delete()
                copyOne(app.sourceDir, file)
                return if (file.exists() && file.length() > 0)
                    BackupResult(BackupType.APK, true, file.absolutePath, file.length(), 1)
                else
                    BackupResult(BackupType.APK, false, errorMessage = "APK 文件复制失败")
            }

            // 分包App：存到一个以App命名的文件夹里，base.apk + split_0.apk, split_1.apk...
            val bundleDir = File(dir, "${safeName}_v${ver}").also {
                if (it.exists()) it.deleteRecursively()
                it.mkdirs()
            }
            val baseFile = File(bundleDir, "base.apk")
            copyOne(app.sourceDir, baseFile)
            var totalSize = baseFile.length()
            app.splitSourceDirs.forEachIndexed { idx, splitPath ->
                val splitFile = File(bundleDir, "split_$idx.apk")
                copyOne(splitPath, splitFile)
                totalSize += splitFile.length()
            }

            if (baseFile.exists() && baseFile.length() > 0)
                BackupResult(BackupType.APK, true, bundleDir.absolutePath, totalSize, 1)
            else
                BackupResult(BackupType.APK, false, errorMessage = "分包 APK 复制失败")
        } catch (e: Exception) {
            BackupResult(BackupType.APK, false, errorMessage = "备份失败: ${e.message}")
        }
    }

    /** 复制单个文件，优先提权复制，失败则普通复制 */
    private fun copyOne(src: String, dst: File) {
        if (ShizukuHelper.hasPrivilege()) {
            val r = ShizukuHelper.execWithPrivilege("cp \"$src\" \"${dst.absolutePath}\"")
            if (!r.isSuccess || !dst.exists() || dst.length() == 0L) {
                copyFile(src, dst)
            }
        } else {
            copyFile(src, dst)
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

    /** ===== 安装 APK（恢复） =====
     *  target 可以是单个 .apk 文件，也可以是分包App的文件夹（里面有 base.apk + split_N.apk） */
    fun installApk(context: Context, target: File): BackupResult {
        return try {
            val apkFiles: List<File> = if (target.isDirectory) {
                target.listFiles()?.filter { it.extension == "apk" }
                    ?.sortedBy { if (it.name == "base.apk") "" else it.name } ?: emptyList()
            } else {
                listOf(target)
            }
            if (apkFiles.isEmpty()) {
                return BackupResult(BackupType.APK, false, errorMessage = "没有找到可安装的 APK 文件")
            }

            // 优先用 Shizuku/Root 通过 pm session 方式安装（支持分包，且用管道传输文件内容，
            // 不依赖 system_server 直接读公共存储路径，规避 FUSE 权限问题）
            if (ShizukuHelper.hasPrivilege()) {
                val r = installViaSession(apkFiles)
                val out = (r.stdout + r.stderr).trim()
                if (r.isSuccess && out.contains("Success", ignoreCase = true)) {
                    return BackupResult(BackupType.APK, true, itemCount = 1)
                }
                if (out.isNotBlank()) {
                    return BackupResult(BackupType.APK, false,
                        errorMessage = "提权安装失败: ${out.take(200)}")
                }
            }

            // 没有提权时，分包App无法通过普通安装界面直接装（系统安装器一次只吃一个文件），
            // 只有单文件App能走这条路
            if (apkFiles.size > 1) {
                return BackupResult(BackupType.APK, false,
                    errorMessage = "此App为分包安装(共${apkFiles.size}个文件)，需要先授权 Shizuku/Root 才能恢复")
            }

            val apkFile = apkFiles[0]
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
            BackupResult(BackupType.APK, false, itemCount = 0,
                errorMessage = "已打开系统安装界面，请在弹出窗口中点击\"安装\"完成恢复（未授权Shizuku/Root时无法自动安装）")
        } catch (e: Exception) {
            BackupResult(BackupType.APK, false, errorMessage = "安装失败: ${e.message}")
        }
    }

    /** 用 pm install-create/install-write/install-commit 会话方式安装一个或多个 apk 文件。
     *  参考 SAI 的做法：每一步都单独执行一条 shell 命令，在 Kotlin 里解析结果，
     *  不把 session id 放在一条命令里用 shell 变量传递，避免嵌套引号/子shell出错 */
    private fun installViaSession(apkFiles: List<File>): ShizukuHelper.CommandResult {
        // 1. 创建安装会话
        val createResult = ShizukuHelper.execWithPrivilege("pm install-create -r")
        val createOut = (createResult.stdout + createResult.stderr)
        val sessionId = Regex("\\[(\\d+)]").find(createOut)?.groupValues?.get(1)
            ?: Regex("(\\d+)").find(createOut)?.value
        if (sessionId == null) {
            return ShizukuHelper.CommandResult(-1, "", "创建安装会话失败: ${createOut.take(200)}")
        }

        // 2. 依次写入每个 apk 文件（通过管道传内容，不依赖system_server直接读源文件路径）
        for ((idx, f) in apkFiles.withIndex()) {
            val path = f.absolutePath
            val writeCmd = "cat \"$path\" | pm install-write -S ${f.length()} $sessionId $idx.apk"
            val writeResult = ShizukuHelper.execWithPrivilege(writeCmd)
            val writeOut = (writeResult.stdout + writeResult.stderr)
            if (!writeResult.isSuccess || writeOut.contains("Exception", ignoreCase = true) ||
                writeOut.contains("Error", ignoreCase = true)
            ) {
                // 写入失败，放弃这次会话
                ShizukuHelper.execWithPrivilege("pm install-abandon $sessionId")
                return ShizukuHelper.CommandResult(-1, "", "写入 ${f.name} 失败: ${writeOut.take(200)}")
            }
        }

        // 3. 提交安装
        return ShizukuHelper.execWithPrivilege("pm install-commit $sessionId")
    }
}
