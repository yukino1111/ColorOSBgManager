package io.github.yukino1111.colorosbgmanager.data

import io.github.yukino1111.colorosbgmanager.model.RootProcessInfo
import java.io.File
import java.util.concurrent.TimeUnit

data class RootQueryResult(
    val rootGranted: Boolean,
    val processes: List<RootProcessInfo>,
    val error: String? = null,
)

object RootProcessClient {

    private const val TOP_CMD = "COLUMNS=512 top -b -n 1 -q -o PID,USER,UID,RES,SHR,S,%CPU,TIME+,ETIME,CMDLINE"

    private fun resolveSuBinary(): String =
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")
            .firstOrNull { File(it).canExecute() }
            ?: "su"

    fun queryRootProcesses(): RootQueryResult {
        return runCatching {
            val proc = ProcessBuilder(resolveSuBinary(), "-c", TOP_CMD)
                .redirectErrorStream(false)
                .start()
            runCatching { proc.outputStream.close() }

            var stderr = ""
            val errThread = Thread {
                stderr = runCatching { proc.errorStream.bufferedReader().readText().trim() }.getOrDefault("")
            }
            errThread.start()

            val stdout = proc.inputStream.bufferedReader().readText()
            errThread.join(2000)

            val finished = proc.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return RootQueryResult(false, emptyList(), "su timeout")
            }
            if (proc.exitValue() != 0 && stdout.isBlank()) {
                return RootQueryResult(
                    rootGranted = false,
                    processes = emptyList(),
                    error = stderr.ifBlank { "su exit=${proc.exitValue()}" },
                )
            }
            val parsed = parseTopOutput(stdout)
            if (parsed.isEmpty() && stdout.isBlank()) {
                return RootQueryResult(
                    rootGranted = false,
                    processes = emptyList(),
                    error = stderr.ifBlank { "empty output (exit=${proc.exitValue()})" },
                )
            }
            RootQueryResult(
                rootGranted = true,
                processes = parsed,
            )
        }.getOrElse { tr ->
            RootQueryResult(false, emptyList(), tr.message ?: "su failed")
        }
    }

    internal fun parseTopOutput(raw: String): List<RootProcessInfo> {
        val result = ArrayList<RootProcessInfo>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(Regex("\\s+"), limit = 10)
            if (parts.size < 10) continue
            val pid = parts[0].toIntOrNull() ?: continue
            val user = parts[1]
            val uid = parts[2].toIntOrNull() ?: continue
            if (uid >= 10000) continue
            val resKb = parseMemoryKb(parts[3])
            val shrKb = parseMemoryKb(parts[4])
            val state = parts[5]
            val cpu = parts[6].toFloatOrNull() ?: 0f
            val cpuTime = parts[7]
            val elapsed = parts[8]
            val cmd = parts[9].trim()
            if (cmd.contains("top -b -n 1 -q -o PID,USER,UID,RES,SHR,S,%CPU,TIME+,ETIME,CMDLINE")) continue
            val isKthread = cmd.startsWith("[") && cmd.endsWith("]")
            result.add(
                RootProcessInfo(
                    pid = pid,
                    user = user,
                    uid = uid,
                    resKb = resKb,
                    shrKb = shrKb,
                    state = state,
                    cpuPercent = cpu,
                    cpuTime = cpuTime,
                    elapsedTime = elapsed,
                    cmdline = cmd,
                    isKernelThread = isKthread,
                ),
            )
        }
        return result.sortedWith(
            compareBy<RootProcessInfo> { it.isKernelThread }
                .thenBy { it.uid != 0 }
                .thenByDescending { it.resKb }
                .thenByDescending { it.cpuPercent },
        )
    }

    internal fun parseMemoryKb(token: String): Long {
        val clean = token.trim().uppercase()
        if (clean.isEmpty() || clean == "0") return 0L
        val suffix = clean.last()
        val num = if (suffix.isLetter()) clean.dropLast(1).toDoubleOrNull() else clean.toDoubleOrNull()
        if (num == null) return 0L
        return when (suffix) {
            'K' -> num.toLong()
            'M' -> (num * 1024.0).toLong()
            'G' -> (num * 1024.0 * 1024.0).toLong()
            'T' -> (num * 1024.0 * 1024.0 * 1024.0).toLong()
            else -> (num / 1024.0).toLong()
        }
    }
}
