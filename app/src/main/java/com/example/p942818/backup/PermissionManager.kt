package com.example.p942818.backup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * 权限管理器 - 处理运行时权限请求
 */
object PermissionManager {

    /** 备份功能所需的所有危险权限 */
    val requiredPermissions: Map<BackupType, Array<String>> = mapOf(
        BackupType.SMS to arrayOf(Manifest.permission.READ_SMS),
        BackupType.CALL_LOG to arrayOf(Manifest.permission.READ_CALL_LOG),
        BackupType.WIFI to arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE),
        BackupType.APK to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            arrayOf() // Android 11+ 不需要存储权限，用 getExternalFilesDir
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        },
        BackupType.WALLPAPER to arrayOf(),
        BackupType.DESKTOP_LAYOUT to arrayOf(),
        BackupType.CONTACTS to arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    )

    /** 所有需要申请的权限合集 */
    val allPermissions: List<String> by lazy {
        val set = mutableSetOf<String>()
        requiredPermissions.values.forEach { set.addAll(it) }
        // 通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            set.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        set.toList()
    }

    /** 检查指定备份类型所需权限是否都已授予 */
    fun hasPermissionsForType(context: Context, type: BackupType): Boolean {
        val perms = requiredPermissions[type] ?: return true
        return perms.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 检查基本权限 */
    fun hasAllEssentialPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val check = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            return check == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /** 检查是否需要引导用户开启通知权限 */
    fun shouldRequestNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val check = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            return check != PackageManager.PERMISSION_GRANTED
        }
        return false
    }

    /** 获取权限描述文本 */
    fun getPermissionDescription(type: BackupType): String {
        return when (type) {
            BackupType.SMS -> "需要短信读取权限来备份短信内容"
            BackupType.CALL_LOG -> "需要通话记录权限来备份通话记录"
            BackupType.WIFI -> "需要位置权限来读取WiFi网络信息"
            BackupType.APK -> "需要存储权限导出APK文件"
            BackupType.WALLPAPER -> "需要系统壁纸访问权限"
            BackupType.DESKTOP_LAYOUT -> "需要Shizuku/Root提权读取桌面布局"
            BackupType.CONTACTS -> "需要联系人读写权限来备份/恢复联系人"
        }
    }

    /** 获取未授权的权限列表 */
    fun getMissingPermissions(context: Context): List<String> {
        return allPermissions.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
    }

    /** 打开应用权限设置 */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 是否已获得"所有文件访问权限"（Android 11+ 写入 /storage/emulated/0/ 根目录需要） */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else true
    }

    /** 跳转到系统"所有文件访问权限"授权页面 */
    fun requestAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }
}
