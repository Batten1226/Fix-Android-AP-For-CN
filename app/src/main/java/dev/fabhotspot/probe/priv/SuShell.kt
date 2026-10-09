package dev.fabhotspot.probe.priv

import java.util.concurrent.TimeUnit

data class ShellResult(
    val command: String,
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * 唯一的提权出口。Phase 1 只允许只读命令，调用方不得传入任何写操作。
 */
object SuShell {

    /** 探测 root。返回 id 输出（含 uid=0）或 null。 */
    fun probeRoot(): String? {
        val r = run("id", timeoutMs = 10_000)
        return if (r.ok && r.output.contains("uid=0")) r.output.trim() else null
    }

    fun run(command: String, timeoutMs: Long = 20_000): ShellResult {
        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (t: Throwable) {
            return ShellResult(command, -1, "启动 su 失败: ${t.javaClass.simpleName}: ${t.message}", false)
        }

        process.outputStream.close()

        val buf = StringBuilder()
        val pump = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { buf.append(it).append('\n') }
            } catch (_: Throwable) {
                // 进程被强杀时会抛，忽略
            }
        }.apply {
            isDaemon = true
            start()
        }

        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            return ShellResult(command, -1, "等待被中断", true)
        }

        if (!finished) {
            process.destroyForcibly()
            pump.join(800)
            return ShellResult(command, -1, buf.toString(), true)
        }

        pump.join(1500)
        return ShellResult(command, process.exitValue(), buf.toString(), false)
    }
}
