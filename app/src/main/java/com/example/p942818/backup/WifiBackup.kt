package com.example.p942818.backup

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WiFi 网络备份模块 - 导出/导入 WiFi 配置
 */
object WifiBackup {

    private const val TAG = "WifiBackup"
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileDf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    data class WifiEntry(val ssid: String, val psk: String, val keyMgmt: String)

    /** 获取已保存的 WiFi 列表（含密码，需提权） */
    fun getWifiList(context: Context): List<WifiEntry> {
        val entries = mutableListOf<WifiEntry>()

        // 先通过 Root/Shizuku 读取 wpa_supplicant.conf
        if (ShizukuHelper.hasPrivilege()) {
            val paths = listOf(
                "/data/misc/wifi/wpa_supplicant.conf",
                "/data/misc/apexdata/com.android.wifi/wpa_supplicant.conf"
            )
            for (path in paths) {
                val r = ShizukuHelper.execWithPrivilege("cat \"$path\" 2>/dev/null")
                if (r.isSuccess && r.stdout.isNotBlank()) {
                    var ssid = ""; var psk = ""; var keyMgmt = ""
                    r.stdout.lines().forEach { line ->
                        val t = line.trim()
                        when {
                            t.startsWith("ssid=") -> ssid = t.substringAfter("ssid=").trim('"')
                            t.startsWith("psk=") -> psk = t.substringAfter("psk=").trim('"')
                            t.startsWith("key_mgmt=") -> keyMgmt = t.substringAfter("key_mgmt=").trim()
                            t == "}" && ssid.isNotEmpty() -> {
                                entries.add(WifiEntry(ssid, psk, keyMgmt))
                                ssid = ""; psk = ""; keyMgmt = ""
                            }
                        }
                    }
                    if (entries.isNotEmpty()) return entries
                }
            }
        }

        // 备用：通过系统 API 获取（无密码）
        try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return entries
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                wm.configuredNetworks?.forEach { config ->
                    entries.add(WifiEntry(
                        ssid = config.SSID.trim('"'), psk = "",
                        keyMgmt = when {
                            config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE) -> "SAE"
                            config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK) -> "WPA2"
                            config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> "WPA"
                            config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE) -> "NONE"
                            else -> "WPA"
                        }
                    ))
                }
            }
        } catch (_: Exception) { }

        return entries
    }

    /** 导出 WiFi 配置 */
    fun backupToJson(context: Context, backupDir: File): BackupResult {
        return try {
            val dir = File(backupDir, "WiFi").also { it.mkdirs() }
            val list = getWifiList(context)
            val file = File(dir, "WiFi备份_${fileDf.format(Date())}.json")
            val arr = JSONArray()
            list.forEach { w ->
                arr.put(JSONObject().apply {
                    put("ssid", w.ssid); put("psk", w.psk)
                    put("keyMgmt", w.keyMgmt)
                })
            }
            FileWriter(file).use { it.write(JSONObject().apply {
                put("app", "备份大师"); put("backupType", "WiFi")
                put("backupTime", df.format(Date()))
                put("totalCount", list.size); put("networks", arr)
            }.toString(2)) }
            BackupResult(BackupType.WIFI, true, file.absolutePath, file.length(), list.size)
        } catch (e: Exception) {
            BackupResult(BackupType.WIFI, false, errorMessage = "备份失败: ${e.message}")
        }
    }

    /** ===== 恢复 WiFi =====
     *  需要 Shizuku/Root 写入 wpa_supplicant.conf */
    fun restoreFromJson(context: Context, jsonFile: File): BackupResult {
        return try {
            val root = JSONObject(jsonFile.readText())
            val arr = root.getJSONArray("networks")
            if (!ShizukuHelper.hasPrivilege()) {
                return BackupResult(BackupType.WIFI, false,
                    errorMessage = "恢复 WiFi 需要 Shizuku/Root 提权")
            }
            var count = 0
            for (i in 0 until arr.length()) {
                val net = arr.getJSONObject(i)
                val ssid = net.optString("ssid", "")
                val psk = net.optString("psk", "")
                val keyMgmt = net.optString("keyMgmt", "WPA")
                if (ssid.isBlank()) continue

                // 用 wpa_cli 添加网络
                val cmds = """
                    wpa_cli -i wlan0 add_network 2>/dev/null
                    wpa_cli -i wlan0 set_network 0 ssid '"$ssid"' 2>/dev/null
                    wpa_cli -i wlan0 set_network 0 psk '"$psk"' 2>/dev/null
                    wpa_cli -i wlan0 set_network 0 key_mgmt $keyMgmt 2>/dev/null
                    wpa_cli -i wlan0 enable_network 0 2>/dev/null
                    wpa_cli -i wlan0 save_config 2>/dev/null
                """.trimIndent()
                ShizukuHelper.execWithPrivilege(cmds)
                count++
            }
            BackupResult(BackupType.WIFI, true, itemCount = count)
        } catch (e: Exception) {
            BackupResult(BackupType.WIFI, false, errorMessage = "恢复失败: ${e.message}")
        }
    }
}
