package org.astrasec.launcher.shelltools

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Runs one non-interactive command; the UI never waits for the process or its output. */
class ShellCommandRunner(
    private val shell: String,
    private val sessionPrefix: List<String>,
    private val terminateGroup: (Int) -> Unit
) {
    data class Result(val exitCode: Int?, val cancelled: Boolean, val error: String?)

    interface Listener {
        fun onOutput(text: String)
        fun onFinished(result: Result)
    }

    fun start(command: String, directory: File, listener: Listener): RunningCommand =
        RunningCommand(command, directory, listener).also { it.start() }

    inner class RunningCommand internal constructor(
        private val command: String,
        private val directory: File,
        private val listener: Listener
    ) {
        private val cancelled = AtomicBoolean(false)
        private val process = AtomicReference<Process?>()
        private val groupId = AtomicReference<Int?>()

        internal fun start() {
            Thread({ run() }, "astra-shell-command").apply { isDaemon = true }.start()
        }

        fun cancel() {
            cancelled.set(true)
            stopProcess()
        }

        private fun stopProcess() {
            terminateProcessGroup()
            process.get()?.let { current -> runCatching { current.destroyForcibly() } }
        }

        private fun terminateProcessGroup() {
            groupId.getAndSet(null)?.let { id -> runCatching { terminateGroup(id) } }
        }

        private fun run() {
            var exitCode: Int? = null
            var error: String? = null
            var outputThread: Thread? = null
            try {
                if (cancelled.get()) return
                val current = ProcessBuilder(
                    sessionPrefix + listOf(shell, "-c", START_WRAPPER, shell, command)
                ).directory(directory).redirectErrorStream(true).start()
                process.set(current)
                if (cancelled.get()) {
                    stopProcess()
                    return
                }
                val reader = current.inputStream.bufferedReader(Charsets.UTF_8)
                val greeting = reader.readLine()
                val pid = greeting?.toIntOrNull()
                    ?: throw IllegalStateException(
                        "无法创建 Shell 会话（需要系统 setsid）：${greeting.orEmpty().take(200)}"
                    )
                require(pid > 1) { "无效的 Shell 进程" }
                groupId.set(pid)
                if (cancelled.get()) {
                    stopProcess()
                    return
                }
                // The child waits for this acknowledgement before executing user input. This
                // closes the cancellation race before we know its independent process group.
                current.outputStream.use { it.write("run\n".toByteArray(Charsets.UTF_8)) }
                outputThread = Thread({
                    try {
                        val chunk = CharArray(2048)
                        while (true) {
                            val count = reader.read(chunk)
                            if (count < 0) break
                            listener.onOutput(String(chunk, 0, count))
                        }
                    } catch (_: Exception) {
                        // Cancellation closes the pipe while a read may still be pending.
                    }
                }, "astra-shell-output").apply { isDaemon = true; start() }
                exitCode = current.waitFor()
                // A command can finish while background children still hold stdout open.
                // Kill the session before joining the reader, so those children cannot leak.
                terminateProcessGroup()
                outputThread.join(2000)
            } catch (exception: Exception) {
                if (!cancelled.get()) error = exception.message ?: exception.javaClass.simpleName
            } finally {
                stopProcess()
                process.getAndSet(null)?.let { current ->
                    runCatching { current.outputStream.close() }
                    runCatching { current.inputStream.close() }
                    runCatching { current.errorStream.close() }
                    runCatching { current.waitFor() }
                }
                outputThread?.let { if (it.isAlive) it.join(1000) }
                listener.onFinished(Result(exitCode, cancelled.get(), error))
            }
        }
    }

    private companion object {
        // $0 is the shell and $1 is the entire user command; neither is interpolated into code.
        const val START_WRAPPER = "printf '%s\\n' \"\$\$\"; IFS= read -r ready; [ \"\$ready\" = run ] || exit 125; exec \"\$0\" -c \"\$1\""
    }
}

/** Keeps memory bounded even for commands that continuously print without newlines. */
class BoundedShellOutput(private val capacity: Int = 65_536) {
    private val content = StringBuilder()
    var truncated: Boolean = false
        private set

    init { require(capacity > 0) }

    @Synchronized
    fun append(text: String) {
        if (text.length >= capacity) {
            truncated = truncated || text.length > capacity || content.isNotEmpty()
            content.clear().append(text.takeLast(capacity))
        } else {
            content.append(text)
            val excess = content.length - capacity
            if (excess > 0) {
                content.delete(0, excess)
                truncated = true
            }
        }
    }

    @Synchronized
    fun snapshot(): Pair<String, Boolean> = content.toString() to truncated
}
