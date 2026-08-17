package com.quickgit.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val id: Long,
    val timestampMillis: Long,
    val tag: String,
    val level: LogLevel,
    val message: String
) {
    val formattedTime: String
        get() = TIME_FMT.format(Date(timestampMillis))

    val formattedLine: String
        get() = "${TIME_FMT.format(Date(timestampMillis))} ${level.name.first()}/$tag: $message"

    companion object {
        private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * In-memory ring buffer (+ optional on-disk append log) for the desktop Logs screen.
 * Entries remain until [clear] is called.
 */
object AppLog {
    private const val MAX_ENTRIES = 2000
    private val lock = Any()
    private val nextId = java.util.concurrent.atomic.AtomicLong(1)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val logDir: File by lazy {
        File(System.getProperty("user.home"), ".config/quickgit").also { it.mkdirs() }
    }
    private val logFile: File by lazy { File(logDir, "app.log") }

    init {
        // Reload last session's file into memory (capped) so logs persist until cleared
        try {
            if (logFile.isFile) {
                val lines = logFile.readLines()
                val restored = lines.takeLast(MAX_ENTRIES).mapNotNull { parseLine(it) }
                if (restored.isNotEmpty()) {
                    _entries.value = restored
                }
            }
        } catch (_: Exception) {
            // ignore corrupt log file
        }
        // Do not call i() here — that would append another line every startup and inflate the file.
        println("I/AppLog: Log buffer ready (${_entries.value.size} entries restored)")
    }

    fun d(tag: String, msg: String) = add(tag, LogLevel.DEBUG, msg)
    fun i(tag: String, msg: String) = add(tag, LogLevel.INFO, msg)
    fun w(tag: String, msg: String) = add(tag, LogLevel.WARN, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val full = if (t != null) {
            val sw = java.io.StringWriter()
            t.printStackTrace(PrintWriter(sw))
            "$msg\n$sw"
        } else msg
        add(tag, LogLevel.ERROR, full)
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
            try {
                if (logFile.exists()) logFile.writeText("")
            } catch (_: Exception) { }
        }
        i("AppLog", "Logs cleared")
    }

    /** Full text of all buffered entries (for copy / save). */
    fun dumpText(): String = synchronized(lock) {
        _entries.value.joinToString("\n") { it.formattedLine }
    }

    fun saveToFile(target: File): Result<File> = runCatching {
        target.parentFile?.mkdirs()
        target.writeText(dumpText())
        target
    }

    private fun add(tag: String, level: LogLevel, message: String) {
        val entry = LogEntry(nextId.getAndIncrement(), System.currentTimeMillis(), tag, level, message)
        synchronized(lock) {
            _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
            try {
                FileWriter(logFile, true).use { w ->
                    w.append(entry.formattedLine).append('\n')
                }
            } catch (_: Exception) { }
        }
        when (level) {
            LogLevel.DEBUG -> println("D/$tag: $message")
            LogLevel.INFO -> println("I/$tag: $message")
            LogLevel.WARN -> System.err.println("W/$tag: $message")
            LogLevel.ERROR -> System.err.println("E/$tag: $message")
        }
    }

    private fun parseLine(line: String): LogEntry? {
        // HH:mm:ss.SSS L/tag: message
        if (line.length < 14) return null
        val levelChar = line.getOrNull(13) ?: return null
        val level = when (levelChar) {
            'D' -> LogLevel.DEBUG
            'I' -> LogLevel.INFO
            'W' -> LogLevel.WARN
            'E' -> LogLevel.ERROR
            else -> LogLevel.INFO
        }
        val slash = line.indexOf('/', 13)
        val colon = line.indexOf(": ", slash + 1)
        if (slash < 0 || colon < 0) return null
        val tag = line.substring(slash + 1, colon)
        val message = line.substring(colon + 2)
        // Best-effort parse of today's time from the line prefix (display only)
        val ts = try {
            val timePart = line.substring(0, 12) // HH:mm:ss.SSS
            val cal = java.util.Calendar.getInstance()
            val parsed = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).parse(timePart)
            if (parsed != null) {
                val pc = java.util.Calendar.getInstance().apply { time = parsed }
                cal.set(java.util.Calendar.HOUR_OF_DAY, pc.get(java.util.Calendar.HOUR_OF_DAY))
                cal.set(java.util.Calendar.MINUTE, pc.get(java.util.Calendar.MINUTE))
                cal.set(java.util.Calendar.SECOND, pc.get(java.util.Calendar.SECOND))
                cal.set(java.util.Calendar.MILLISECOND, pc.get(java.util.Calendar.MILLISECOND))
                cal.timeInMillis
            } else System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
        return LogEntry(nextId.getAndIncrement(), ts, tag, level, message)
    }
}
