package com.example.p942818.backup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面布局备份模块 - 导出/导入桌面布局
 * 需要 Shizuku/Root 提权读取系统桌面数据库
 */
object DesktopLayoutBackup {

    private const val TAG = "DesktopLayoutBackup"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    data class DesktopItem(
        val type: String, val packageName: String, val activityName: String?,
        val displayName: String, val screen: Int, val x: Int, val y: Int,
        val spanX: Int, val spanY: Int
    )

    fun getCurrentLauncherPackage(context: Context): String? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (e: Exception) { null }
    }

    fun getDesktopLayoutViaPrivilege(context: Context): List<DesktopItem> {
        val items = mutableListOf<DesktopItem>()
        val launcherPkg = getCurrentLauncherPackage(context) ?: "com.android.launcher3"

        // 尝试多种方法读取桌面数据库
        try {
            // 方法1: 读取 launcher db
            val dbPaths = listOf(
                "/data/data/$launcherPkg/databases/launcher.db",
                "/data/data/$launcherPkg/databases/launcher3.db",
                "/data/user/0/$launcherPkg/databases/launcher.db"
            )
            for (dbPath in dbPaths) {
                val r = ShizukuHelper.execWithPrivilege(
                    "sqlite3 \"$dbPath\" \"SELECT * FROM favorites;\" 2>/dev/null"
                )
                if (r.isSuccess && r.stdout.isNotBlank()) {
                    r.stdout.lines().forEach { line ->
                        if (line.isNotBlank()) {
                            val cols = line.split("|")
                            if (cols.size >= 6) {
                                items.add(DesktopItem(
                                    type = if (cols.getOrNull(1) == "0") "app" else "folder",
                                    packageName = cols.getOrNull(4) ?: "",
                                    activityName = cols.getOrNull(5),
                                    displayName = cols.getOrNull(3) ?: "未知",
                                    screen = cols.getOrNull(2)?.toIntOrNull() ?: 0,
                                    x = cols.getOrNull(6)?.toIntOrNull() ?: 0,
                                    y = cols.getOrNull(7)?.toIntOrNull() ?: 0,
                                    spanX = cols.getOrNull(8)?.toIntOrNull() ?: 1,
                                    spanY = cols.getOrNull(9)?.toIntOrNull() ?: 1
                                ))
                            }
                        }
                    }
                    if (items.isNotEmpty()) return items
                }
            }

            // 方法2: 读取 favorites.xml
            val xmlPaths = listOf(
                "/data/data/$launcherPkg/files/favorites.xml",
                "/data/data/$launcherPkg/shared_prefs/favorites.xml"
            )
            for (xmlPath in xmlPaths) {
                val r = ShizukuHelper.execWithPrivilege("cat \"$xmlPath\" 2>/dev/null")
                if (r.isSuccess && r.stdout.isNotBlank()) {
                    val regex = Regex("""package="([^"]*)"[^>]*class="([^"]*)"[^>]*screen="(\d+)"[^>]*x="(\d+)"[^>]*y="(\d+)"""")
                    regex.findAll(r.stdout).forEach { match ->
                        items.add(DesktopItem(
                            type = "app", packageName = match.groupValues[1],
                            activityName = match.groupValues[2], displayName = match.groupValues[1],
                            screen = match.groupValues[3].toIntOrNull() ?: 0,
                            x = match.groupValues[4].toIntOrNull() ?: 0,
                            y = match.groupValues[5].toIntOrNull() ?: 0,
                            spanX = 1, spanY = 1
                        ))
                    }
                    if (items.isNotEmpty()) return items
                }
            }
        } catch (e: Exception) { Log.e(TAG, "获取桌面布局失败", e) }

        // 备用: 生成启动器应用列表作为虚拟布局
        if (items.isEmpty()) {
            try {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                val apps = pm.queryIntentActivities(intent, 0)
                apps.forEachIndexed { index, info ->
                    val screen = index / 16; val pos = index % 16
                    items.add(DesktopItem("app", info.activityInfo.packageName, info.activityInfo.name,
                        info.activityInfo.loadLabel(pm).toString(), screen, pos % 4, pos / 4, 1, 1))
                }
            } catch (e: Exception) { Log.e(TAG, "生成桌面布局失败", e) }
        }
        return items
    }

    /** 导出桌面布局 */
    fun backupDesktopLayout(context: Context, backupDir: File): BackupResult {
        return try {
            val dir = File(backupDir, "DesktopLayout").also { it.mkdirs() }
            val items = getDesktopLayoutViaPrivilege(context)
            val file = File(dir, "桌面布局_${fileDateFormat.format(Date())}.json")
            val jsonArray = JSONArray()
            items.forEach { item ->
                jsonArray.put(JSONObject().apply {
                    put("type", item.type); put("packageName", item.packageName)
                    put("activityName", item.activityName ?: "")
                    put("displayName", item.displayName)
                    put("screen", item.screen); put("x", item.x); put("y", item.y)
                    put("spanX", item.spanX); put("spanY", item.spanY)
                })
            }
            FileWriter(file).use { it.write(JSONObject().apply {
                put("app", "备份大师"); put("backupType", "桌面布局")
                put("backupTime", dateFormat.format(Date()))
                put("launcherPackage", getCurrentLauncherPackage(context) ?: "未知")
                put("totalCount", items.size); put("items", jsonArray)
            }.toString(2)) }
            BackupResult(BackupType.DESKTOP_LAYOUT, true, file.absolutePath, file.length(), items.size)
        } catch (e: Exception) {
            BackupResult(BackupType.DESKTOP_LAYOUT, false, errorMessage = "备份失败: ${e.message}")
        }
    }

    /** ===== 恢复桌面布局 =====
     *  通过 Shizuku/Root 将桌面应用图标恢复至桌面数据库
     *  注意: 不同桌面实现不同，恢复效果可能不完美 */
    fun restoreDesktopLayout(context: Context, jsonFile: File): BackupResult {
        return try {
            val root = JSONObject(jsonFile.readText())
            val items = root.getJSONArray("items")
            val launcherPkg = getCurrentLauncherPackage(context) ?: "com.android.launcher3"

            if (!ShizukuHelper.hasPrivilege()) {
                return BackupResult(BackupType.DESKTOP_LAYOUT, false,
                    errorMessage = "恢复桌面布局需要 Shizuku/Root 提权")
            }

            var count = 0
            // 方式1: 通过 content provider 插入（某些启动器支持）
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val pkg = item.optString("packageName", "")
                val activity = item.optString("activityName", "")
                val screen = item.optInt("screen", 0)
                val x = item.optInt("x", 0)
                val y = item.optInt("y", 0)

                if (pkg.isBlank()) continue

                // 尝试通过 ContentProvider 插入到启动器
                val cmd = "content insert --uri content://$launcherPkg.settings/favorites " +
                        "--bind package_name:s:\"$pkg\" " +
                        "--bind activity_name:s:\"${activity}\" " +
                        "--bind screen:i:$screen " +
                        "--bind cellX:i:$x --bind cellY:i:$y " +
                        "--bind spanX:i:1 --bind spanY:i:1 2>/dev/null"
                ShizukuHelper.execWithPrivilege(cmd)
                count++
            }

            // 方式2: 如果 content provider 不可用，尝试写入数据库
            if (count == 0) {
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val pkg = item.optString("packageName", "")
                    val activity = item.optString("activityName", "")
                    val screen = item.optInt("screen", 0)
                    val x = item.optInt("x", 0)
                    val y = item.optInt("y", 0)

                    if (pkg.isBlank()) continue

                    val dbPaths = listOf(
                        "/data/data/$launcherPkg/databases/launcher.db",
                        "/data/user/0/$launcherPkg/databases/launcher.db"
                    )
                    for (dbPath in dbPaths) {
                        val sql = "INSERT OR IGNORE INTO favorites(" +
                                "title,intent,container,screen,cellX,cellY,spanX,spanY,itemType,appWidgetId) " +
                                "VALUES(" +
                                "'$pkg','#Intent;action=android.intent.action.MAIN;" +
                                "launchFlags=0x10000000;" +
                                "component=$pkg/$activity;end'," +
                                "-100,$screen,$x,$y,1,1,0,-1);"
                        ShizukuHelper.execWithPrivilege("sqlite3 \"$dbPath\" \"$sql\" 2>/dev/null")
                        count++
                        break
                    }
                }
            }

            // 重启桌面
            ShizukuHelper.execWithPrivilege("am force-stop $launcherPkg 2>/dev/null")
            ShizukuHelper.execWithPrivilege("am start -n $launcherPkg/.MainActivity 2>/dev/null")

            BackupResult(BackupType.DESKTOP_LAYOUT, true, itemCount = count)
        } catch (e: Exception) {
            BackupResult(BackupType.DESKTOP_LAYOUT, false, errorMessage = "恢复失败: ${e.message}")
        }
    }
}
