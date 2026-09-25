package com.sandyz.virtualcam.utils

import java.io.IOException

/**
 * 轻量 root 命令执行工具。
 * 依赖 APatch/Magisk 提供的 `su` 二进制，首次调用会触发授权弹窗。
 */
object RootShell {

    data class ShellResult(
        val exitCode: Int,
        val output: String,
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /**
     * 探测 root 是否可用：执行 `su -c "id"` 看输出是否包含 uid=0。
     */
    fun isRootAvailable(): Boolean {
        return try {
            val r = exec("id")
            r.isSuccess && r.output.contains("uid=0")
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 以 root 身份执行单条 shell 命令。
     * 用 ProcessBuilder + redirectErrorStream 合并输出流，避免 stdout/stderr 缓冲区写满死锁。
     */
    fun exec(cmd: String): ShellResult {
        val process = try {
            ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            return ShellResult(-1, "启动 su 失败: ${e.message}")
        }

        val output = try {
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }

        val exitCode = try {
            process.waitFor()
        } catch (e: InterruptedException) {
            process.destroy()
            -1
        }
        return ShellResult(exitCode, output)
    }
}
