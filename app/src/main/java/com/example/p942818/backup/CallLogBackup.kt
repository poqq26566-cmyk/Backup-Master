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
 * 读取优先走 Shizuku/Root shell（跟短信同一套逻辑），因为部分厂商 ROM 即使系统权限
 * 显示"已允许"，普通 ContentResolver 查询依然会被静默拦截、返回空结果，
 * 只有 shell 提权能绕过这层限制；没有提权时才退回普通方式（参考 sms-ie）。
 * 写入相对没有这个限制，用普通 ContentResolver 即可。
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

    /** 导出通话记录备份：有提权优先走shell（绕过厂商限制），没有则用普通ContentResolver；
     *  如果有提权但shell读到0条，再兜底试一次普通方式，双重保险 */
    fun backupToJson(context: Context, backupDir: File): BackupResult {
        return try {
            val callDir = File(backupDir, "CallLog").also { it.mkdirs() }
            var logs = if (ShizukuHelper.hasPrivilege()) getAllCallLogsViaShell() else emptyList()
            if (logs.isEmpty()) {
                logs = getAllCallLogs(context)
            }
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

    /** ===== 导入/恢复通话记录 =====
     *  普通ContentResolver写入，需要 WRITE_CALL_LOG 权限（跟Shizuku/Root无关） */
    fun restoreFromJson(context: Context, jsonFile: File): BackupResult {
        return try {
            val root = JSONObject(jsonFile.readText())
            val logs = root.getJSONArray("callLogs")
            var restored = 0
            var skipped = 0
            var lastError: String? = null

            for (i in 0 until logs.length()) {
                val log = logs.getJSONObject(i)
                val number = log.optString("number", "")
                if (number.isBlank()) { skipped++; continue }
                try {
                    val values = ContentValues().apply {
                        put(CallLog.Calls.NUMBER, number)
                        put(CallLog.Calls.CACHED_NAME, log.optString("name", ""))
                        put(CallLog.Calls.DATE, log.optLong("date", 0L))
                        put(CallLog.Calls.DURATION, log.optLong("duration", 0L))
                        put(CallLog.Calls.TYPE, log.optInt("type", 1))
                        put(CallLog.Calls.NEW, 1)
                    }
                    val uri = context.contentResolver.insert(CallLog.Calls.CONTENT_URI, values)
                    if (uri != null) restored++ else skipped++
                } catch (e: Exception) {
                    skipped++
                    lastError = e.message
                }
            }

            if (logs.length() == 0) {
                return BackupResult(BackupType.CALL_LOG, false, errorMessage = "备份文件里没有通话记录数据")
            }

            val msgParts = mutableListOf<String>()
            if (skipped > 0) msgParts.add("跳过/失败${skipped}条")
            if (lastError != null) msgParts.add("最后错误: $lastError")
            BackupResult(BackupType.CALL_LOG, restored > 0, itemCount = restored,
                errorMessage = if (msgParts.isNotEmpty()) msgParts.joinToString(" · ") else null)
        } catch (e: SecurityException) {
            BackupResult(BackupType.CALL_LOG, false, errorMessage = "需要通话记录写入权限")
        } catch (e: Exception) {
            BackupResult(BackupType.CALL_LOG, false, errorMessage = "恢复失败: ${e.message}")
        }
    }
}
