package com.example.p942818.backup

import android.util.Log

object ShizukuHelper {

    private const val TAG = "ShizukuHelper"

    enum class PrivilegeLevel { NONE, SHIZUKU, ROOT }

    data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    private var privilegeLevel: PrivilegeLevel = PrivilegeLevel.NONE

    fun getPrivilegeLevel(): PrivilegeLevel = privilegeLevel

    fun hasPrivilege(): Boolean = privilegeLevel != PrivilegeLevel.NONE

    fun getPrivilegeDescription(): String = when (privilegeLevel) {
        PrivilegeLevel.ROOT -> "✅ Root 权限已获取"
        PrivilegeLevel.SHIZUKU -> "✅ Shizuku 已授权"
        PrivilegeLevel.NONE -> "⚠️ 未获取提权（部分备份功能受限）"
    }

    fun isShizukuGranted(): Boolean = privilegeLevel == PrivilegeLevel.SHIZUKU

    fun isShizukuInstalled(): Boolean {
        return try {
            rikka.shizuku.Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    /** 尝试获取 Root 权限（通过 su） */
    private fun tryRoot(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val text = p.inputStream.bufferedReader().readText()
            val exit = p.waitFor()
            exit == 0 && text.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /** 检测最高可用权限 */
    fun detectPrivilege(): PrivilegeLevel {
        if (tryRoot()) {
            privilegeLevel = PrivilegeLevel.ROOT
            Log.d(TAG, "Root 权限已获取")
            return PrivilegeLevel.ROOT
        }
        if (isShizukuInstalled()) {
            val granted = try {
                rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (e: Throwable) { false }
            if (granted) {
                privilegeLevel = PrivilegeLevel.SHIZUKU
                Log.d(TAG, "Shizuku 已授权")
                return PrivilegeLevel.SHIZUKU
            }
        }
        privilegeLevel = PrivilegeLevel.NONE
        return PrivilegeLevel.NONE
    }

    /** 请求 Shizuku 授权 */
    fun requestShizukuPermission(requestCode: Int = 10086) {
        try {
            rikka.shizuku.Shizuku.requestPermission(requestCode)
        } catch (e: Throwable) {
            Log.e(TAG, "请求 Shizuku 授权失败", e)
        }
    }

    /** 执行命令（优先 Root，其次 Shizuku，最后普通 shell） */
    fun execWithPrivilege(command: String): CommandResult {
        return when (privilegeLevel) {
            PrivilegeLevel.ROOT -> execWithRoot(command)
            PrivilegeLevel.SHIZUKU -> execWithShizukuShell(command)
            PrivilegeLevel.NONE -> execWithShell(command)
        }
    }

    private fun execWithRoot(command: String): CommandResult {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = p.inputStream.bufferedReader().readText()
            val stderr = p.errorStream.bufferedReader().readText()
            val exit = p.waitFor()
            CommandResult(exit, stdout, stderr)
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: "Root 执行失败")
        }
    }

    private fun execWithShell(command: String): CommandResult {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = p.inputStream.bufferedReader().readText()
            val stderr = p.errorStream.bufferedReader().readText()
            val exit = p.waitFor()
            CommandResult(exit, stdout, stderr)
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: "Shell 执行失败")
        }
    }

    /** 通过 Shizuku shell 执行 */
    private fun execWithShizukuShell(command: String): CommandResult {
        return try {
            val os = java.io.ByteArrayOutputStream()
            val osErr = java.io.ByteArrayOutputStream()

            val callback = object : rikka.shizuku.Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {}
            }
            rikka.shizuku.Shizuku.addBinderReceivedListener(callback)

            // Use Shizuku's newProcess if available, else fallback
            return try {
                @Suppress("UNCHECKED_CAST")
                val process = rikka.shizuku.Shizuku::class.java
                    .getMethod("newProcess", Array<String>::class.java)
                    .invoke(null, arrayOf("sh", "-c", command)) as Process
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val exit = process.waitFor()
                CommandResult(exit, stdout, stderr)
            } catch (nsme: NoSuchMethodException) {
                // fallback to root shell
                execWithRoot(command)
            } catch (e: Exception) {
                CommandResult(-1, "", "Shizuku 执行失败: ${e.message}")
            }
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: "Shizuku 执行失败")
        }
    }
}
