package com.example.p942818.backup

/**
 * 运行在 Shizuku 提权进程里的用户服务，真正的 shell 命令在这里执行，
 * 这个类拥有 Shizuku/shell 的权限，所以它自己调用 ProcessBuilder("sh","-c",cmd)
 * 跑出来的命令天然带提权，不需要再依赖 su。
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

    override fun destroy() {
        System.exit(0)
    }
}
