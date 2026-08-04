package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import com.quickgit.app.data.AppLog
import com.quickgit.app.data.LogEntry
import kotlinx.coroutines.flow.StateFlow

class LogsViewModel : ViewModel() {
    val entries: StateFlow<List<LogEntry>> = AppLog.entries

    fun clear() = AppLog.clear()
}
