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

    /** Single line suitable for export / file dump. */
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
    private const val MAX_ENTRIES = 500

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun d(tag: String, message: String) = add(tag, LogLevel.DEBUG, message)
    fun i(tag: String, message: String) = add(tag, LogLevel.INFO, message)
    fun w(tag: String, message: String) = add(tag, LogLevel.WARN, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        add(tag, LogLevel.ERROR, if (throwable != null) "$message: ${throwable.message}" else message)

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

    private fun add(tag: String, level: LogLevel, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), tag, level, message)
        synchronized(this) {
            _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        }
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
    }
}
