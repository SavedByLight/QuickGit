package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import com.quickgit.app.data.AppLog
import com.quickgit.app.data.LogEntry
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsViewModel : ViewModel() {
    val entries: StateFlow<List<LogEntry>> = AppLog.entries

    fun clear() = AppLog.clear()

    /** Renders the current log as plain text for the "Copy logs" action. */
    fun asPlainText(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return entries.value.joinToString("\n") { e ->
            "${fmt.format(Date(e.timestampMillis))} ${e.level} [${e.tag}] ${e.message}"
        }
    }
}
