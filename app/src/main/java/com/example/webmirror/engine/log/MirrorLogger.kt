package com.example.webmirror.engine.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * App + mirror session logger. Writes to files under app filesDir/logs/.
 * Supports export of the current session log.
 */
class MirrorLogger private constructor(context: Context) {

    private val logDir = File(context.applicationContext.filesDir, "logs").also { it.mkdirs() }
    private val lock = ReentrantLock()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    @Volatile
    private var sessionFile: File = newSessionFile()

    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)
    fun d(tag: String, msg: String) = write("D", tag, msg)

    fun logHttp(
        url: String,
        code: Int?,
        bytes: Long?,
        ms: Long?,
        status: String,
        extra: String? = null
    ) {
        val parts = buildString {
            append("HTTP $status")
            if (code != null) append(" code=$code")
            if (bytes != null) append(" bytes=$bytes")
            if (ms != null) append(" ${ms}ms")
            append(" url=$url")
            if (!extra.isNullOrBlank()) append(" $extra")
        }
        i("HTTP", parts)
    }

    private fun write(level: String, tag: String, msg: String, t: Throwable? = null) {
        val line = buildString {
            append(timeFmt.format(Date()))
            append(' ')
            append(level)
            append('/')
            append(tag)
            append(": ")
            append(msg)
            if (t != null) {
                append('\n')
                append(Log.getStackTraceString(t))
            }
            append('\n')
        }
        when (level) {
            "E" -> Log.e(tag, msg, t)
            "W" -> Log.w(tag, msg, t)
            "D" -> Log.d(tag, msg)
            else -> Log.i(tag, msg)
        }
        lock.withLock {
            try {
                rotateIfNeeded()
                sessionFile.appendText(line, Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }

    private fun rotateIfNeeded() {
        // Keep single session file under ~8MB
        if (sessionFile.length() > 8L * 1024 * 1024) {
            sessionFile = newSessionFile()
        }
    }

    private fun newSessionFile(): File {
        val name = "mirror_${dayFmt.format(Date())}_${System.currentTimeMillis()}.log"
        return File(logDir, name).also {
            if (!it.exists()) {
                it.writeText(
                    "=== WebMirror log started ${timeFmt.format(Date())} ===\n",
                    Charsets.UTF_8
                )
            }
        }
    }

    /** Current session log file (for sharing/export). */
    fun currentLogFile(): File = sessionFile

    /** All log files, newest first. */
    fun listLogFiles(): List<File> =
        logDir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * Export: copy current (or all) logs into a single file under [targetDir].
     * @return exported file or null on failure
     */
    fun exportTo(targetDir: File, includeAllSessions: Boolean = false): File? {
        return try {
            targetDir.mkdirs()
            val out = File(
                targetDir,
                "WebMirror_logs_${dayFmt.format(Date())}_${System.currentTimeMillis()}.txt"
            )
            lock.withLock {
                val sources = if (includeAllSessions) listLogFiles() else listOf(sessionFile)
                out.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.appendLine("WebMirror log export ${timeFmt.format(Date())}")
                    writer.appendLine("files=${sources.size}")
                    writer.appendLine("---")
                    for (f in sources) {
                        writer.appendLine()
                        writer.appendLine("##### ${f.name} #####")
                        if (f.exists()) writer.append(f.readText(Charsets.UTF_8))
                    }
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    fun clearAll() {
        lock.withLock {
            listLogFiles().forEach { it.delete() }
            sessionFile = newSessionFile()
        }
    }

    /**
     * Delete log files older than [keepDays]. Always keeps the current session file.
     * @return number of deleted files
     */
    fun autoClean(keepDays: Int = 7): Int {
        val cutoff = System.currentTimeMillis() - keepDays.coerceAtLeast(1) * 24L * 3600_000L
        var deleted = 0
        lock.withLock {
            for (f in listLogFiles()) {
                if (f.absolutePath == sessionFile.absolutePath) continue
                if (f.lastModified() < cutoff) {
                    if (f.delete()) deleted++
                }
            }
        }
        return deleted
    }

    fun logDirSizeBytes(): Long =
        listLogFiles().sumOf { it.length() }

    companion object {
        @Volatile private var instance: MirrorLogger? = null

        fun get(context: Context): MirrorLogger {
            return instance ?: synchronized(this) {
                instance ?: MirrorLogger(context.applicationContext).also { instance = it }
            }
        }
    }
}
