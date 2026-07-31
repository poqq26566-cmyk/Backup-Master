package com.example.p942818.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 短信备份模块 - 导出/导入短信
 */
object SmsBackup {

    private const val TAG = "SmsBackup"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    private const val SMS_URI = "content://sms"

    private val typeMap = mapOf(
        1 to "收件箱", 2 to "已发送", 3 to "草稿", 4 to "发件箱",
        5 to "失败", 6 to "队列中"
    )

    /**
     * 去重检查：判断某条短信（地址+内容+时间完全一致）是否已经存在于系统短信库中。
     * 读取不需要 root/Shizuku、也不需要是默认短信App，READ_SMS 权限即可查询。
     * 用于恢复备份前过滤掉已经存在的记录，避免重复恢复导致同一条短信出现多份。
     */
    private fun smsExists(context: Context, address: String, body: String, date: Long): Boolean {
        return try {
            val cursor = context.contentResolver.query(
                Uri.parse(SMS_URI),
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ? AND ${Telephony.Sms.DATE} = ?",
                arrayOf(address, body, date.toString()),
                null
            )
            cursor?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "smsExists query failed: ${e.message}")
            false
        }
    }

    /**
     * 标准方式读取短信（照抄 sms-ie 的做法）：
     * 用 Telephony.Sms.CONTENT_URI + 普通 ContentResolver.query。
     * 读短信只需要 READ_SMS 权限，不需要 root/Shizuku、也不需要是默认短信App，
     * 这是最稳、兼容性最好的方式（sms-ie 就是靠这个稳定导出上千条短信的）。
     * 同时像 sms-ie 一样动态遍历所有列，而不是只挑几个固定字段，信息更完整。
     */
    fun getAllSms(context: Context): List<SmsRecord> {
        val smsList = mutableListOf<SmsRecord>()
        try {
            val cursor: Cursor? = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, null, null, null, null
            )
            cursor?.use { c ->
                if (!c.moveToFirst()) return@use
                val idIdx = c.getColumnIndex(Telephony.Sms._ID)
                val addressIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val personIdx = c.getColumnIndex(Telephony.Sms.PERSON)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val typeIdx = c.getColumnIndex(Telephony.Sms.TYPE)
                val readIdx = c.getColumnIndex(Telephony.Sms.READ)
                val threadIdIdx = c.getColumnIndex(Telephony.Sms.THREAD_ID)
                do {
                    val dateVal = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    val typeVal = if (typeIdx >= 0) c.getInt(typeIdx) else 0
                    smsList.add(SmsRecord(
                        id = if (idIdx >= 0) c.getLong(idIdx) else 0L,
                        address = if (addressIdx >= 0) c.getString(addressIdx) ?: "" else "",
                        person = if (personIdx >= 0) c.getString(personIdx) else null,
                        date = dateVal,
                        dateString = if (dateVal > 0) dateFormat.format(Date(dateVal)) else "",
                        body = if (bodyIdx >= 0) c.getString(bodyIdx) ?: "" else "",
                        type = typeVal,
                        typeString = typeMap[typeVal] ?: "未知",
                        read = if (readIdx >= 0) c.getInt(readIdx) == 1 else true,
                        threadId = if (threadIdIdx >= 0) c.getLong(threadIdIdx) else null
                    ))
                } while (c.moveToNext())
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取短信失败", e); throw e
        }
        return smsList
    }

    /** 通过 Shizuku/Root shell 执行 content query 读取短信（仅作为标准方式读到0条时的兜底，
     *  正常情况下不应该用这条路径，因为 shell 输出的文本解析很容易因为短信正文里的逗号/换行而出错） */
    fun getAllSmsViaShell(): List<SmsRecord> {
        val smsList = mutableListOf<SmsRecord>()
        try {
            val result = ShizukuHelper.execWithPrivilege("content query --uri content://sms/")
            if (!result.isSuccess) return emptyList()
            result.stdout.lineSequence().forEach { line ->
                if (!line.trimStart().startsWith("Row:")) return@forEach
                val fields = parseContentQueryLine(line)
                val dateMs = fields["date"]?.toLongOrNull() ?: 0L
                val type = fields["type"]?.toIntOrNull() ?: 0
                smsList.add(SmsRecord(
                    id = fields["_id"]?.toLongOrNull() ?: 0L,
                    address = fields["address"]?.takeIf { it != "NULL" } ?: "",
                    person = fields["person"]?.takeIf { it != "NULL" },
                    date = dateMs,
                    dateString = if (dateMs > 0) dateFormat.format(Date(dateMs)) else "",
                    body = fields["body"]?.takeIf { it != "NULL" } ?: "",
                    type = type,
                    typeString = typeMap[type] ?: "未知",
                    read = fields["read"] == "1",
                    threadId = fields["thread_id"]?.toLongOrNull()
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "通过shell读取短信失败", e)
        }
        return smsList
    }

    /** 解析 content query 输出的一行，格式如："Row: 0 _id=1, thread_id=2, address=123, body=你好, type=1" */
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

    /** 导出短信备份（JSON）
     *  关键改动：不再优先用 shell 方式读取。优先用标准 ContentResolver（照抄 sms-ie），
     *  只有在标准方式读到 0 条、且确实有 root/Shizuku 权限时，才尝试 shell 方式兜底一次。
     *  这样即使设备被 root 了，也不会因为 shell 解析出问题而导出空备份。 */
    fun backupToJson(context: Context, backupDir: File): BackupResult {
        return try {
            val smsDir = File(backupDir, "SMS").also { it.mkdirs() }

            var smsList = getAllSms(context)
            if (smsList.isEmpty() && ShizukuHelper.hasPrivilege()) {
                Log.w(TAG, "标准方式读到0条短信，尝试 shell 方式兜底")
                val viaShell = getAllSmsViaShell()
                if (viaShell.isNotEmpty()) smsList = viaShell
            }

            val file = File(smsDir, "短信备份_${fileDateFormat.format(Date())}.json")

            val jsonArray = JSONArray()
            smsList.forEach { sms ->
                jsonArray.put(JSONObject().apply {
                    put("address", sms.address); put("person", sms.person ?: "")
                    put("date", sms.date); put("dateString", sms.dateString)
                    put("body", sms.body); put("type", sms.type)
                    put("typeString", sms.typeString); put("read", sms.read)
                })
            }
            FileWriter(file).use { it.write(JSONObject().apply {
                put("app", "备份大师"); put("backupType", "短信")
                put("backupTime", dateFormat.format(Date()))
                put("totalCount", smsList.size); put("messages", jsonArray)
            }.toString(2)) }

            BackupResult(BackupType.SMS, true, file.absolutePath, file.length(), smsList.size)
        } catch (e: SecurityException) {
            BackupResult(BackupType.SMS, false, errorMessage = "需要短信读取权限")
        } catch (e: Exception) {
            BackupResult(BackupType.SMS, false, errorMessage = "备份失败: ${e.message}")
        }
    }

    /** 把一个字符串安全地包进单引号里，用于拼shell命令 */
    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** ===== 导入/恢复短信 =====（这部分逻辑不变，写入短信本身就必须走默认App或root/Shizuku，
     *  和本次"备份为空"的bug无关，保持原样） */
    fun restoreFromJson(context: Context, jsonFile: File): BackupResult {
        return try {
            val jsonStr = jsonFile.readText()
            val root = JSONObject(jsonStr)
            val messages = root.getJSONArray("messages")

            if (SmsRoleHelper.isDefaultSmsApp(context)) {
                var restoredCount = 0
                var skippedCount = 0
                var duplicateCount = 0
                for (i in 0 until messages.length()) {
                    val msg = messages.getJSONObject(i)
                    val body = msg.optString("body", "")
                    val address = msg.optString("address", "")
                    if (body.isBlank() || address.isBlank()) { skippedCount++; continue }
                    val date = msg.optLong("date", System.currentTimeMillis())
                    if (smsExists(context, address, body, date)) { duplicateCount++; continue }
                    try {
                        val values = ContentValues().apply {
                            put(Telephony.Sms.ADDRESS, address)
                            put(Telephony.Sms.BODY, body)
                            put(Telephony.Sms.DATE, date)
                            put(Telephony.Sms.TYPE, msg.optInt("type", 1))
                            put(Telephony.Sms.READ, if (msg.optBoolean("read", true)) 1 else 0)
                            put(Telephony.Sms.PERSON, msg.optString("person", ""))
                        }
                        val uri = context.contentResolver.insert(Uri.parse(SMS_URI), values)
                        if (uri != null) restoredCount++ else skippedCount++
                    } catch (e: Exception) {
                        skippedCount++
                    }
                }
                val notes = mutableListOf<String>()
                if (skippedCount > 0) notes.add("跳过/失败${skippedCount}条")
                if (duplicateCount > 0) notes.add("已存在跳过${duplicateCount}条")
                return BackupResult(BackupType.SMS, restoredCount > 0, itemCount = restoredCount,
                    errorMessage = if (notes.isNotEmpty()) notes.joinToString(" · ") else null)
            }

            val hasPrivilege = ShizukuHelper.hasPrivilege()

            if (hasPrivilege) {
                val cmds = mutableListOf<String>()
                var skippedBlank = 0
                var duplicateCount = 0
                for (i in 0 until messages.length()) {
                    val msg = messages.getJSONObject(i)
                    val body = msg.optString("body", "")
                    val address = msg.optString("address", "")
                    if (body.isBlank() || address.isBlank()) { skippedBlank++; continue }
                    val date = msg.optLong("date", System.currentTimeMillis())
                    if (smsExists(context, address, body, date)) { duplicateCount++; continue }
                    val type = msg.optInt("type", 1)
                    val read = if (msg.optBoolean("read", true)) 1 else 0
                    cmds.add(
                        "content insert --uri content://sms " +
                            "--bind ${shq("address:s:$address")} " +
                            "--bind ${shq("body:s:$body")} " +
                            "--bind date:l:$date --bind type:i:$type --bind read:i:$read"
                    )
                }
                if (cmds.isEmpty()) {
                    return BackupResult(BackupType.SMS, false,
                        errorMessage = if (duplicateCount > 0) "已存在跳过${duplicateCount}条，无新短信可恢复" else "没有可恢复的短信数据")
                }
                val fullCmd = cmds.joinToString(" ; ")
                val r = ShizukuHelper.execWithPrivilege(fullCmd)
                val out = (r.stdout + r.stderr)
                val failCount = Regex("(Error|Exception)", RegexOption.IGNORE_CASE).findAll(out).count()
                val success = (cmds.size - failCount).coerceAtLeast(0)
                val notes = mutableListOf<String>()
                if (failCount + skippedBlank > 0) notes.add("跳过/失败${failCount + skippedBlank}条")
                if (duplicateCount > 0) notes.add("已存在跳过${duplicateCount}条")
                return BackupResult(BackupType.SMS, success > 0, itemCount = success,
                    errorMessage = if (notes.isNotEmpty()) notes.joinToString(" · ") else null)
            }

            var restoredCount = 0
            var skippedCount = 0
            var duplicateCount = 0
            var lastError: String? = null
            for (i in 0 until messages.length()) {
                val msg = messages.getJSONObject(i)
                val body = msg.optString("body", "")
                val address = msg.optString("address", "")
                if (body.isBlank() || address.isBlank()) { skippedCount++; continue }
                val date = msg.optLong("date", System.currentTimeMillis())
                if (smsExists(context, address, body, date)) { duplicateCount++; continue }
                try {
                    val values = ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, address)
                        put(Telephony.Sms.BODY, body)
                        put(Telephony.Sms.DATE, date)
                        put(Telephony.Sms.TYPE, msg.optInt("type", 1))
                        put(Telephony.Sms.READ, if (msg.optBoolean("read", true)) 1 else 0)
                        put(Telephony.Sms.PERSON, msg.optString("person", ""))
                    }
                    val uri = context.contentResolver.insert(Uri.parse(SMS_URI), values)
                    if (uri != null) restoredCount++ else skippedCount++
                } catch (e: Exception) {
                    skippedCount++
                    lastError = e.message
                }
            }
            val msgParts = mutableListOf<String>()
            if (skippedCount > 0) msgParts.add("跳过/失败${skippedCount}条")
            if (duplicateCount > 0) msgParts.add("已存在跳过${duplicateCount}条")
            msgParts.add("未授权Shizuku/Root可能导致大量写入失败")
            if (lastError != null) msgParts.add("最后错误: $lastError")
            BackupResult(BackupType.SMS, restoredCount > 0, itemCount = restoredCount,
                errorMessage = msgParts.joinToString(" · "))
        } catch (e: SecurityException) {
            BackupResult(BackupType.SMS, false, errorMessage = "需要短信写入权限（可能需要 Shizuku/Root）")
        } catch (e: Exception) {
            BackupResult(BackupType.SMS, false, errorMessage = "恢复失败: ${e.message}")
        }
    }
}
