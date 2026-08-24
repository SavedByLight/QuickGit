package com.quickgit.app.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val timestampMillis: Long,
    val tag: String,
    val level: LogLevel,
    val message: String
) {
    val formattedTime: String
        get() = TIME_FMT.format(Date(timestampMillis))

    /** Single line / multi-line block suitable for export / file dump. */
    val formattedLine: String
        get() = "${FULL_FMT.format(Date(timestampMillis))} ${level.name} [$tag] $message"

    companion object {
        private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)
        private val FULL_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * Lightweight in-memory log ring buffer for git operations (stage, commit, push, pull,
 * revert, discard, clone...). Backs the in-app "Logs" screen so failures like transport
 * errors or checkout conflicts can be inspected without pulling logcat off the device.
 */
object AppLog {
    // Stack traces are multi-line; keep more room so a few ERROR dumps do not
    // push out the preceding context (clone / checkout / stage lines).
    private const val MAX_ENTRIES = 800

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun d(tag: String, message: String) = add(tag, LogLevel.DEBUG, message)
    fun i(tag: String, message: String) = add(tag, LogLevel.INFO, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            add(tag, LogLevel.WARN, formatWithStack(message, throwable))
            Log.w(tag, message, throwable)
        } else {
            add(tag, LogLevel.WARN, message)
        }
    }

    /**
     * Log an error. When [throwable] is set, the full stack (including causes) is
     * stored in the in-app buffer and passed to logcat so both the Logs screen and
     * `adb logcat` show the same detail.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            add(tag, LogLevel.ERROR, formatWithStack(message, throwable), logToLogcat = false)
            Log.e(tag, message, throwable)
        } else {
            add(tag, LogLevel.ERROR, message)
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /** Full text of the current ring buffer (for copy / save-to-file). */
    fun dumpText(): String = synchronized(this) {
        _entries.value.joinToString("\n") { it.formattedLine }
    }

    /**
     * Write the current log buffer to [target]. Parent directories are created if needed.
     * Useful for app-private files; prefer SAF [CreateDocument] from the UI for user-visible paths.
     */
    fun saveToFile(target: java.io.File): Result<java.io.File> = runCatching {
        target.parentFile?.mkdirs()
        target.writeText(dumpText())
        target
    }

    private fun formatWithStack(message: String, throwable: Throwable): String = buildString {
        append(message)
        if (throwable.message != null) {
            append(": ")
            append(throwable.message)
        }
        append('\n')
        append(throwable.stackTraceToString().trimEnd())
        var cause = throwable.cause
        // stackTraceToString() already walks causes on recent Kotlin; guard against
        // duplicates while still covering older runtimes / wrapped exceptions.
        val seen = mutableSetOf<Throwable>()
        seen.add(throwable)
        while (cause != null && seen.add(cause)) {
            append("\nCaused by: ")
            append(cause.stackTraceToString().trimEnd())
            cause = cause.cause
        }
    }

    private fun add(
        tag: String,
        level: LogLevel,
        message: String,
        logToLogcat: Boolean = true
    ) {
        val entry = LogEntry(System.currentTimeMillis(), tag, level, message)
        synchronized(this) {
            _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        }
        if (!logToLogcat) return
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
    }
}
