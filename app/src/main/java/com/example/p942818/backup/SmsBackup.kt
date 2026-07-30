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

    fun getAllSms(context: Context): List<SmsRecord> {
        val smsList = mutableListOf<SmsRecord>()
        try {
            val cursor: Cursor? = context.contentResolver.query(
                Uri.parse(SMS_URI), null, null, null,
                "${Telephony.Sms.DEFAULT_SORT_ORDER} ASC"
            )
            cursor?.use { c ->
                val idIdx = c.getColumnIndex(Telephony.Sms._ID)
                val addressIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val personIdx = c.getColumnIndex(Telephony.Sms.PERSON)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val typeIdx = c.getColumnIndex(Telephony.Sms.TYPE)
                val readIdx = c.getColumnIndex(Telephony.Sms.READ)
                val threadIdIdx = c.getColumnIndex(Telephony.Sms.THREAD_ID)
                while (c.moveToNext()) {
                    smsList.add(SmsRecord(
                        id = if (idIdx >= 0) c.getLong(idIdx) else 0L,
                        address = if (addressIdx >= 0) c.getString(addressIdx) ?: "" else "",
                        person = if (personIdx >= 0) c.getString(personIdx) else null,
                        date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L,
                        dateString = if (dateIdx >= 0) dateFormat.format(Date(c.getLong(dateIdx))) else "",
                        body = if (bodyIdx >= 0) c.getString(bodyIdx) ?: "" else "",
                        type = if (typeIdx >= 0) c.getInt(typeIdx) else 0,
                        typeString = typeMap[c.getInt(typeIdx)] ?: "未知",
                        read = if (readIdx >= 0) c.getInt(readIdx) == 1 else true,
                        threadId = if (threadIdIdx >= 0) c.getLong(threadIdIdx) else null
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取短信失败", e); throw e
        }
        return smsList
    }

    /** 通过 Shizuku/Root shell 执行 content query 读取短信（绕过部分厂商 ROM 对普通App的读取限制） */
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

    /** 导出短信备份（JSON） */
    fun backupToJson(context: Context, backupDir: File): BackupResult {
        return try {
            val smsDir = File(backupDir, "SMS").also { it.mkdirs() }
            val smsList = if (ShizukuHelper.hasPrivilege()) getAllSmsViaShell() else getAllSms(context)
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

    /** ===== 导入/恢复短信 =====
     *  普通App没法直接写系统短信数据库（Android只允许"默认短信App"写入）。
     *  优先级：① 如果本App此刻已经是默认短信App（参考sms-ie项目的做法，
     *  可以临时申请这个身份），直接用最简单可靠的 ContentResolver 写入；
     *  ② 否则如果有 Shizuku/Root，走 shell 的 content insert 命令；
     *  ③ 都没有就只能尝试普通写入（大概率失败）。 */
    fun restoreFromJson(context: Context, jsonFile: File): BackupResult {
        return try {
            val jsonStr = jsonFile.readText()
            val root = JSONObject(jsonStr)
            val messages = root.getJSONArray("messages")

            // ① 已经是默认短信App：这是最正规、最可靠的方式，系统完全允许写入
            if (SmsRoleHelper.isDefaultSmsApp(context)) {
                var restoredCount = 0
                var skippedCount = 0
                for (i in 0 until messages.length()) {
                    val msg = messages.getJSONObject(i)
                    val body = msg.optString("body", "")
                    val address = msg.optString("address", "")
                    if (body.isBlank() || address.isBlank()) { skippedCount++; continue }
                    try {
                        val values = ContentValues().apply {
                            put(Telephony.Sms.ADDRESS, address)
                            put(Telephony.Sms.BODY, body)
                            put(Telephony.Sms.DATE, msg.optLong("date", System.currentTimeMillis()))
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
                return BackupResult(BackupType.SMS, restoredCount > 0, itemCount = restoredCount,
                    errorMessage = if (skippedCount > 0) "跳过/失败${skippedCount}条" else null)
            }

            val hasPrivilege = ShizukuHelper.hasPrivilege()

            if (hasPrivilege) {
                val cmds = mutableListOf<String>()
                var skippedBlank = 0
                for (i in 0 until messages.length()) {
                    val msg = messages.getJSONObject(i)
                    val body = msg.optString("body", "")
                    val address = msg.optString("address", "")
                    if (body.isBlank() || address.isBlank()) { skippedBlank++; continue }
                    val date = msg.optLong("date", System.currentTimeMillis())
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
                    return BackupResult(BackupType.SMS, false, errorMessage = "没有可恢复的短信数据")
                }
                // 每条 content insert 之间用分号连接成一条shell命令，一次性执行完
                val fullCmd = cmds.joinToString(" ; ")
                val r = ShizukuHelper.execWithPrivilege(fullCmd)
                val out = (r.stdout + r.stderr)
                // 数一下有多少条真的失败了（每条失败会在输出里留下 Error/Exception 字样）
                val failCount = Regex("(Error|Exception)", RegexOption.IGNORE_CASE).findAll(out).count()
                val success = (cmds.size - failCount).coerceAtLeast(0)
                val totalSkipped = failCount + skippedBlank
                return BackupResult(BackupType.SMS, success > 0, itemCount = success,
                    errorMessage = if (totalSkipped > 0) "跳过/失败${totalSkipped}条" else null)
            }

            // 没有提权时，只有当前App恰好是默认短信App时才可能写入成功
            var restoredCount = 0
            var skippedCount = 0
            var lastError: String? = null
            for (i in 0 until messages.length()) {
                val msg = messages.getJSONObject(i)
                val body = msg.optString("body", "")
                val address = msg.optString("address", "")
                if (body.isBlank() || address.isBlank()) { skippedCount++; continue }
                try {
                    val values = ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, address)
                        put(Telephony.Sms.BODY, body)
                        put(Telephony.Sms.DATE, msg.optLong("date", System.currentTimeMillis()))
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
