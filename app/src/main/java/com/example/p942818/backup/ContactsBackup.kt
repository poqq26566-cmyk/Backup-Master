package com.example.p942818.backup

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 联系人备份模块 - 导出/导入联系人
 */
object ContactsBackup {

    private const val TAG = "ContactsBackup"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    /** 读取所有联系人（含电话号码和邮箱） */
    fun getAllContacts(context: Context): List<ContactRecord> {
        val contacts = mutableListOf<ContactRecord>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, null, null, null,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )
            cursor?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val hasPhoneIdx = c.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                while (c.moveToNext()) {
                    val contactId = if (idIdx >= 0) c.getLong(idIdx) else continue
                    val displayName = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                    val hasPhone = if (hasPhoneIdx >= 0) c.getInt(hasPhoneIdx) > 0 else false

                    val phones = mutableListOf<ContactPhone>()
                    if (hasPhone) {
                        context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
                            arrayOf(contactId.toString()), null
                        )?.use { p ->
                            val numIdx = p.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            val typeIdx = p.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                            while (p.moveToNext()) {
                                val number = if (numIdx >= 0) p.getString(numIdx) ?: "" else ""
                                val type = if (typeIdx >= 0) p.getInt(typeIdx)
                                           else ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                                if (number.isNotBlank()) phones.add(ContactPhone(number, type))
                            }
                        }
                    }

                    val emails = mutableListOf<String>()
                    context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Email.CONTENT_URI, null,
                        "${ContactsContract.CommonDataKinds.Email.CONTACT_ID}=?",
                        arrayOf(contactId.toString()), null
                    )?.use { e ->
                        val addrIdx = e.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                        while (e.moveToNext()) {
                            val addr = if (addrIdx >= 0) e.getString(addrIdx) ?: "" else ""
                            if (addr.isNotBlank()) emails.add(addr)
                        }
                    }

                    if (displayName.isNotBlank() || phones.isNotEmpty()) {
                        contacts.add(ContactRecord(contactId, displayName, phones, emails))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取联系人失败", e); throw e
        }
        return contacts
    }

    /** 导出联系人备份（JSON） */
    fun backupToJson(context: Context, backupDir: File): BackupResult {
        return try {
            val contactsDir = File(backupDir, "Contacts").also { it.mkdirs() }
            val contacts = getAllContacts(context)
            val file = File(contactsDir, "联系人备份_${fileDateFormat.format(Date())}.json")

            val jsonArray = JSONArray()
            contacts.forEach { contact ->
                val phonesArray = JSONArray()
                contact.phones.forEach { phone ->
                    phonesArray.put(JSONObject().apply {
                        put("number", phone.number); put("type", phone.type)
                    })
                }
                val emailsArray = JSONArray()
                contact.emails.forEach { emailsArray.put(it) }

                jsonArray.put(JSONObject().apply {
                    put("displayName", contact.displayName)
                    put("phones", phonesArray)
                    put("emails", emailsArray)
                })
            }
            FileWriter(file).use {
                it.write(JSONObject().apply {
                    put("app", "备份大师"); put("backupType", "联系人")
                    put("backupTime", dateFormat.format(Date()))
                    put("totalCount", contacts.size); put("contacts", jsonArray)
                }.toString(2))
            }

            BackupResult(BackupType.CONTACTS, true, file.absolutePath, file.length(), contacts.size)
        } catch (e: SecurityException) {
            BackupResult(BackupType.CONTACTS, false, errorMessage = "需要联系人读取权限")
        } catch (e: Exception) {
            BackupResult(BackupType.CONTACTS, false, errorMessage = "备份失败: ${e.message}")
        }
    }

    /** ===== 导入/恢复联系人 ===== */
    fun restoreFromJson(context: Context, jsonFile: File): BackupResult {
        return try {
            val root = JSONObject(jsonFile.readText())
            val contactsJson = root.getJSONArray("contacts")
            var restored = 0; var skipped = 0

            for (i in 0 until contactsJson.length()) {
                val c = contactsJson.getJSONObject(i)
                val displayName = c.optString("displayName", "")
                val phonesArr = c.optJSONArray("phones")
                val emailsArr = c.optJSONArray("emails")

                if (displayName.isBlank() && (phonesArr == null || phonesArr.length() == 0)) {
                    skipped++; continue
                }

                // 去重检查：按姓名是否已存在
                if (displayName.isNotBlank()) {
                    val existing = context.contentResolver.query(
                        ContactsContract.Contacts.CONTENT_URI,
                        arrayOf(ContactsContract.Contacts._ID),
                        "${ContactsContract.Contacts.DISPLAY_NAME}=?",
                        arrayOf(displayName), null
                    )
                    val exists = existing?.use { it.count > 0 } ?: false
                    existing?.close()
                    if (exists) { skipped++; continue }
                }

                val ops = ArrayList<ContentProviderOperation>()
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                        .build()
                )
                if (phonesArr != null) {
                    for (j in 0 until phonesArr.length()) {
                        val p = phonesArr.getJSONObject(j)
                        val number = p.optString("number", "")
                        val type = p.optInt("type", ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        if (number.isBlank()) continue
                        ops.add(
                            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                                .withValue(
                                    ContactsContract.Data.MIMETYPE,
                                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                                )
                                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, type)
                                .build()
                        )
                    }
                }
                if (emailsArr != null) {
                    for (j in 0 until emailsArr.length()) {
                        val addr = emailsArr.optString(j, "")
                        if (addr.isBlank()) continue
                        ops.add(
                            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                                .withValue(
                                    ContactsContract.Data.MIMETYPE,
                                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
                                )
                                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, addr)
                                .withValue(
                                    ContactsContract.CommonDataKinds.Email.TYPE,
                                    ContactsContract.CommonDataKinds.Email.TYPE_HOME
                                )
                                .build()
                        )
                    }
                }

                try {
                    context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                    restored++
                } catch (e: Exception) {
                    Log.e(TAG, "写入联系人失败: $displayName", e)
                    skipped++
                }
            }

            BackupResult(
                BackupType.CONTACTS, true, itemCount = restored,
                errorMessage = if (skipped > 0) "跳过${skipped}条已存在/失败" else null
            )
        } catch (e: SecurityException) {
            BackupResult(BackupType.CONTACTS, false, errorMessage = "需要联系人写入权限（可能需要 Shizuku/Root）")
        } catch (e: Exception) {
            BackupResult(BackupType.CONTACTS, false, errorMessage = "恢复失败: ${e.message}")
        }
    }
}
