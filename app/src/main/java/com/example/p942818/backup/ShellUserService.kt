package com.example.p942818.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import org.json.JSONArray

/**
 * 运行在 Shizuku 提权进程里的用户服务，真正的 shell 命令在这里执行，
 * 这个类拥有 Shizuku/shell 的权限，所以它自己调用 ProcessBuilder("sh","-c",cmd)
 * 跑出来的命令天然带提权，不需要再依赖 su。
 *
 * bulkInsertSms/bulkInsertCallLog 走的是另一条更快的路：直接在这个已经提权、
 * 已经启动好的进程内部用 ContentResolver 批量写入，不用像 exec() 那样每条
 * 记录都单独 fork 一次 "content" 命令（那个命令本身启动很慢，几千条数据会
 * 慢到像卡死一样）。
 */
class ShellUserService : IShellService.Stub() {

    override fun exec(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            // 用 \u0000 分隔退出码和输出内容，方便调用方解析
            "$exitCode\u0000$output"
        } catch (e: Exception) {
            "-1\u0000${e.message}"
        }
    }

    /** 通过反射拿到当前(已提权)进程里的 Application Context，用来获取 ContentResolver */
    private fun getContext(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThreadClass.getMethod("currentApplication").invoke(null)
            currentApplication as? Context
        } catch (e: Exception) {
            null
        }
    }

    override fun bulkInsertSms(jsonArray: String): String {
        return try {
            val context = getContext() ?: return "ERROR:无法获取提权进程的Context"
            val arr = JSONArray(jsonArray)
            var success = 0
            var fail = 0
            val uri = Uri.parse("content://sms")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val values = ContentValues().apply {
                    put("address", obj.optString("address", ""))
                    put("body", obj.optString("body", ""))
                    put("date", obj.optLong("date", 0L))
                    put("type", obj.optInt("type", 1))
                    put("read", if (obj.optBoolean("read", true)) 1 else 0)
                    val person = obj.optString("person", "")
                    if (person.isNotBlank()) put("person", person)
                }
                try {
                    val resultUri = context.contentResolver.insert(uri, values)
                    if (resultUri != null) success++ else fail++
                } catch (e: Exception) {
                    fail++
                }
            }
            "OK:$success:$fail"
        } catch (e: Exception) {
            "ERROR:${e.message}"
        }
    }

    override fun bulkInsertCallLog(jsonArray: String): String {
        return try {
            val context = getContext() ?: return "ERROR:无法获取提权进程的Context"
            val arr = JSONArray(jsonArray)
            var success = 0
            var fail = 0
            val uri = Uri.parse("content://call_log/calls")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val values = ContentValues().apply {
                    put("number", obj.optString("number", ""))
                    put("date", obj.optLong("date", 0L))
                    put("duration", obj.optLong("duration", 0L))
                    put("type", obj.optInt("type", 1))
                    put("new", 1)
                    val name = obj.optString("name", "")
                    if (name.isNotBlank()) put("name", name)
                }
                try {
                    val resultUri = context.contentResolver.insert(uri, values)
                    if (resultUri != null) success++ else fail++
                } catch (e: Exception) {
                    fail++
                }
            }
            "OK:$success:$fail"
        } catch (e: Exception) {
            "ERROR:${e.message}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
