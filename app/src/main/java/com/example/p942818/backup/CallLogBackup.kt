package com.example.p942818.backup

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通话记录备份模块 - 导出/导入通话记录
 */
object CallLogBackup {

    private const val TAG = "CallLogBackup"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    private val typeMap = mapOf(
        CallLog.Calls.INCOMING_TYPE to "来电", CallLog.Calls.OUTGOING_TYPE to "拨出",
        CallLog.Calls.MISSED_TYPE to "未接", CallLog.Calls.VOICEMAIL_TYPE to "语音邮件",
        CallLog.Calls.REJECTED_TYPE to "拒接", CallLog.Calls.BLOCKED_TYPE to "已拦截"
    )

    fun getAllCallLogs(context: Context): List<CallLogRecord> {
        val callList = mutableListOf<CallLogRecord>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, null, null, null,
                "${CallLog.Calls.DEFAULT_SORT_ORDER} ASC"
            )?.use { c ->
                val idIdx = c.getColumnIndex(CallLog.Calls._ID)
                val numberIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val countryIsoIdx = c.getColumnIndex(CallLog.Calls.COUNTRY_ISO)
                val geoIdx = c.getColumnIndex(CallLog.Calls.GEOCODED_LOCATION)

                while (c.moveToNext()) {
                    val type = if (typeIdx >= 0) c.getInt(typeIdx) else 0
                    val duration = if (durationIdx >= 0) c.getLong(durationIdx) else 0L
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    callList.add(CallLogRecord(
                        id = if (idIdx >= 0) c.getLong(idIdx) else 0L,
                        number = if (numberIdx >= 0) c.getString(numberIdx) ?: "" else "",
                        name = if (nameIdx >= 0) c.getString(nameIdx) else null,
                        date = date,
                        dateString = dateFormat.format(Date(date)),
                        duration = duration,
                        durationString = formatDuration(duration),
                        type = type, typeString = typeMap[type] ?: "未知",
                        countryIso = if (countryIsoIdx >= 0) c.getString(countryIsoIdx) else null,
                        geocodedLocation = if (geoIdx >= 0) c.getString(geoIdx) else null
                    ))
                }
            }
        } catch (e: Exception) { Log.e(TAG, "读取通话记录失败", e); throw e }
        return callList
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        return when { h > 0 -> "${h}时${m}分${s}秒"; m > 0 -> "${m}分${s}秒"; else -> "${s}秒" }
    }

    /** 通过 Shizuku/Root shell 执行 content query 读取通话记录（绕过部分厂商 ROM 对普通App的读取限制） */
    fun getAllCallLogsViaShell(): List<CallLogRecord> {
        val callList = mutableListOf<CallLogRecord>()
        try {
            val result = ShizukuHelper.execWithPrivilege("content query --uri content://call_log/calls/")
            if (!result.isSuccess) return emptyList()
            result.stdout.lineSequence().forEach { line ->
                if (!line.trimStart().startsWith("Row:")) return@forEach
                val fields = parseContentQueryLine(line)
                val date = fields["date"]?.toLongOrNull() ?: 0L
                val duration = fields["duration"]?.toLongOrNull() ?: 0L
                val type = fields["type"]?.toIntOrNull() ?: 0
                callList.add(CallLogRecord(
                    id = fields["_id"]?.toLongOrNull() ?: 0L,
                    number = fields["number"]?.takeIf { it != "NULL" } ?: "",
                    name = fields["name"]?.takeIf { it != "NULL" },
                    date = date,
                    dateString = if (date > 0) dateFormat.format(Date(date)) else "",
                    duration = duration,
                    durationString = formatDuration(duration),
                    type = type, typeString = typeMap[type] ?: "未知",
                    countryIso = fields["countryiso"]?.takeIf { it != "NULL" },
                    geocodedLocation = fields["geocoded_location"]?.takeIf { it != "NULL" }
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "通过shell读取通话记录失败", e)
        }
        return callList
    }

    /** 解析 content query 输出的一行 */
    private fun parseContentQueryLine(line: String): Map<String, String> {
        val trimmed = line.trimStart()
        val firstSpace = trimmed.indexOf(' ')
        val secondSpace = if (firstSpace >= 0) trimmed.indexOf(' ', firstSpace + 1) else -1
        val body = if (secondSpace >= 0) trimmed.substring(secondSpace + 1) else trimmed
        val parts = body.split(Regex(", (?=[a-zA-Z_][a-zA-Z0-9_]*=)"))
        val map = mutableMapOf<String, String>()
        for (p in parts) {
            val eq = p.indexOf('=')
            if (eq > 0) {
                map[p.substring(0, eq).trim()] = p.substring(eq + 1).trim()
            }
        }
        return map
    }

    /** 导出通话记录备份 */
    fun backupToJson(context: Context, backupDir: File): BackupResult {
        return try {
            val callDir = File(backupDir, "CallLog").also { it.mkdirs() }
            val logs = if (ShizukuHelper.hasPrivilege()) getAllCallLogsViaShell() else getAllCallLogs(context)
            val file = File(callDir, "通话记录备份_${fileDateFormat.format(Date())}.json")
            val jsonArray = JSONArray()
            logs.forEach { log ->
                jsonArray.put(JSONObject().apply {
                    put("number", log.number); put("name", log.name ?: "")
                    put("date", log.date); put("dateString", log.dateString)
                    put("duration", log.duration); put("durationString", log.durationString)
                    put("type", log.type); put("typeString", log.typeString)
                    put("countryIso", log.countryIso ?: ""); put("geocodedLocation", log.geocodedLocation ?: "")
                })
            }
            FileWriter(file).use { it.write(JSONObject().apply {
                put("app", "备份大师"); put("backupType", "通话记录")
                put("backupTime", dateFormat.format(Date()))
                put("totalCount", logs.size); put("callLogs", jsonArray)
            }.toString(2)) }
            BackupResult(BackupType.CALL_LOG, true, file.absolutePath, file.length(), logs.size)
        } catch (e: SecurityException) {
            BackupResult(BackupType.CALL_LOG, false, errorMessage = "需要通话记录读取权限")
        } catch (e: Exception) {
            BackupResult(BackupType.CALL_LOG, false, errorMessage = "备份失败: ${e.message}")
        }
    }

    /** ===== 导入/恢复通话记录 ===== */
    fun restoreFromJson(context: Context, jsonFile: File): BackupResult {
        return try {
            val root = JSONObject(jsonFile.readText())
            val logs = root.getJSONArray("callLogs")
            var restored = 0; var skipped = 0

            for (i in 0 until logs.length()) {
                val log = logs.getJSONObject(i)
                val number = log.optString("number", "")
                val date = log.optLong("date", 0L)
                val duration = log.optLong("duration", 0L)
                val type = log.optInt("type", 1)

                if (number.isBlank()) { skipped++; continue }

                // 去重检查
                val existing = context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls._ID),
                    "${CallLog.Calls.NUMBER}=? AND ${CallLog.Calls.DATE}=? AND ${CallLog.Calls.DURATION}=?",
                    arrayOf(number, date.toString(), duration.toString()), null
                )
                val exists = existing?.use { it.count > 0 } ?: false
                existing?.close()
                if (exists) { skipped++; continue }

                val values = ContentValues().apply {
                    put(CallLog.Calls.NUMBER, number)
                    put(CallLog.Calls.CACHED_NAME, log.optString("name", ""))
                    put(CallLog.Calls.DATE, date)
                    put(CallLog.Calls.DURATION, duration)
                    put(CallLog.Calls.TYPE, type)
                    put(CallLog.Calls.COUNTRY_ISO, log.optString("countryIso", ""))
                    put(CallLog.Calls.GEOCODED_LOCATION, log.optString("geocodedLocation", ""))
                    put(CallLog.Calls.NEW, 1)
                }
                context.contentResolver.insert(CallLog.Calls.CONTENT_URI, values)
                restored++
            }
            BackupResult(BackupType.CALL_LOG, true, itemCount = restored,
                errorMessage = if (skipped > 0) "跳过${skipped}条已存在" else null)
        } catch (e: SecurityException) {
            BackupResult(BackupType.CALL_LOG, false, errorMessage = "需要通话记录写入权限（可能需要 Shizuku/Root）")
        } catch (e: Exception) {
            BackupResult(BackupType.CALL_LOG, false, errorMessage = "恢复失败: ${e.message}")
        }
    }
}
