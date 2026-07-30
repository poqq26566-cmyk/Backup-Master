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

    /** 添加 Shizuku 权限授权结果监听器（在 MainActivity onCreate 里调用一次） */
    fun addPermissionResultListener(onResult: (granted: Boolean) -> Unit) {
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener { _, grantResult ->
                val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) {
                    privilegeLevel = PrivilegeLevel.SHIZUKU
                }
                onResult(granted)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "添加权限监听器失败", e)
        }
    }

    /** 添加 Shizuku 绑定监听器，Shizuku 服务连接上时自动重新检测权限 */
    fun addBinderReceivedListener(onReceived: () -> Unit) {
        try {
            rikka.shizuku.Shizuku.addBinderReceivedListener {
                detectPrivilege()
                onReceived()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "添加绑定监听器失败", e)
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

    private var shellService: IShellService? = null
    private val serviceLock = Any()

    /** 绑定 Shizuku 用户服务（懒加载，只绑定一次） */
    private fun getShellService(): IShellService? {
        synchronized(serviceLock) {
            if (shellService != null) return shellService
            val latch = java.util.concurrent.CountDownLatch(1)
            val args = rikka.shizuku.Shizuku.UserServiceArgs(
                android.content.ComponentName(
                    "com.example.p942818",
                    ShellUserService::class.java.name
                )
            ).daemon(false).processNameSuffix("shell").debuggable(false).version(1)

            val connection = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                    shellService = IShellService.Stub.asInterface(binder)
                    latch.countDown()
                }
                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    shellService = null
                }
            }
            try {
                rikka.shizuku.Shizuku.bindUserService(args, connection)
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Throwable) {
                Log.e(TAG, "绑定Shizuku用户服务失败", e)
            }
            return shellService
        }
    }

    /** 通过 Shizuku 用户服务执行 shell 命令 */
    private fun execWithShizukuShell(command: String): CommandResult {
        return try {
            val service = getShellService()
                ?: return CommandResult(-1, "", "无法连接Shizuku用户服务，请重新授权Shizuku")
            val result = service.exec(command)
            val idx = result.indexOf('\u0000')
            if (idx < 0) return CommandResult(-1, "", result)
            val exitCode = result.substring(0, idx).toIntOrNull() ?: -1
            val output = result.substring(idx + 1)
            CommandResult(exitCode, output, "")
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku shell 执行失败", e)
            CommandResult(-1, "", "Shizuku 执行失败: ${e.message}")
        }
    }
}
