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
 * 参考 sms-ie 项目的做法：通话记录的读写不像短信那样需要"默认App"身份，
 * 普通的 READ_CALL_LOG / WRITE_CALL_LOG 权限 + 标准 ContentResolver 就够了，
 * 不需要走 Shizuku/Root shell 那一套。
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

    /** 导出通话记录备份（普通ContentResolver读取，需要 READ_CALL_LOG 权限） */
    fun backupToJson(context: Context, backupDir: File): BackupResult {
        return try {
            val callDir = File(backupDir, "CallLog").also { it.mkdirs() }
            val logs = getAllCallLogs(context)
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
