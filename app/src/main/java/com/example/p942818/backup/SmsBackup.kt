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
    private val typeReverse = typeMap.entries.associate { it.value to it.key }

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

    /** 导出短信备份（JSON） */
    fun backupToJson(context: Context, backupDir: File): BackupResult {
        return try {
            val smsDir = File(backupDir, "SMS").also { it.mkdirs() }
            val smsList = getAllSms(context)
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

    /** ===== 导入/恢复短信 ===== */
    fun restoreFromJson(context: Context, jsonFile: File): BackupResult {
        return try {
            val jsonStr = jsonFile.readText()
            val root = JSONObject(jsonStr)
            val messages = root.getJSONArray("messages")
            var restoredCount = 0
            var skippedCount = 0

            for (i in 0 until messages.length()) {
                val msg = messages.getJSONObject(i)
                val body = msg.optString("body", "")
                val address = msg.optString("address", "")
                val date = msg.optLong("date", System.currentTimeMillis())
                val type = msg.optInt("type", 1)

                if (body.isBlank() || address.isBlank()) { skippedCount++; continue }

                // 检查是否已存在
                val existing = context.contentResolver.query(
                    Uri.parse(SMS_URI),
                    arrayOf(Telephony.Sms._ID),
                    "${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.BODY}=? AND ${Telephony.Sms.DATE}=?",
                    arrayOf(address, body, date.toString()), null
                )
                val exists = existing?.use { it.count > 0 } ?: false
                existing?.close()
                if (exists) { skippedCount++; continue }

                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, date)
                    put(Telephony.Sms.TYPE, type)
                    put(Telephony.Sms.READ, msg.optBoolean("read", true))
                    put(Telephony.Sms.PERSON, msg.optString("person", ""))
                    put(Telephony.Sms.SUBJECT, "")
                    put(Telephony.Sms.STATUS, -1)
                    put(Telephony.Sms.PROTOCOL, 0)
                    put(Telephony.Sms.REPLY_PATH_PRESENT, 0)
                    put(Telephony.Sms.SERVICE_CENTER, "")
                }
                context.contentResolver.insert(Uri.parse(SMS_URI), values)
                restoredCount++
            }

            BackupResult(BackupType.SMS, true,
                itemCount = restoredCount,
                errorMessage = if (skippedCount > 0) "跳过${skippedCount}条已存在" else null)
        } catch (e: SecurityException) {
            BackupResult(BackupType.SMS, false, errorMessage = "需要短信写入权限（可能需要 Shizuku/Root）")
        } catch (e: Exception) {
            BackupResult(BackupType.SMS, false, errorMessage = "恢复失败: ${e.message}")
        }
    }
}
