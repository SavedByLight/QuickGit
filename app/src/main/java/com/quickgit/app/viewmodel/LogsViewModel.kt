package com.quickgit.app.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.quickgit.app.data.AppLog
import com.quickgit.app.data.LogEntry
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsViewModel : ViewModel() {
    val entries: StateFlow<List<LogEntry>> = AppLog.entries

    fun clear() = AppLog.clear()

    /** Renders the current log as plain text for copy / save actions. */
    fun asPlainText(): String = AppLog.dumpText()

    /** Suggested filename for a save dialog, e.g. `quickgit-logs-20260821-094200.txt`. */
    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "quickgit-logs-$stamp.txt"
    }

    /**
     * Write logs to a user-chosen SAF [uri] (CreateDocument).
     * Returns an error message on failure, or null on success.
     */
    fun saveToUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(asPlainText().toByteArray(StandardCharsets.UTF_8))
                out.flush()
            } ?: return "Could not open output stream"
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    /**
     * Write logs to app-specific external storage (always available, no picker).
     * File lands under Android/data/.../files/logs/ and is visible to file managers on many tablets.
     */
    fun saveToAppFiles(context: Context): Result<File> {
        val dir = context.getExternalFilesDir("logs") ?: context.filesDir.resolve("logs")
        val file = File(dir, suggestedFileName())
        return AppLog.saveToFile(file)
    }
}
