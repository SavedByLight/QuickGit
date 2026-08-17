package com.quickgit.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Non-sensitive app preferences (layout, etc.).
 *
 * Desktop layout mode controls whether the permanent left NavigationRail
 * (matching the Linux desktop app) is shown:
 * - [DesktopLayoutMode.AUTO] — show rail when window is tablet/Chromebook width or wider
 * - [DesktopLayoutMode.ALWAYS] — always show the left rail (phone or wide)
 * - [DesktopLayoutMode.NEVER] — never show the rail (phone-style navigation only)
 */
enum class DesktopLayoutMode {
    AUTO,
    ALWAYS,
    NEVER;

    companion object {
        fun fromStorage(value: String?): DesktopLayoutMode =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}

class AppPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _desktopLayoutMode = MutableStateFlow(
        DesktopLayoutMode.fromStorage(prefs.getString(KEY_DESKTOP_LAYOUT, null))
    )
    val desktopLayoutMode: StateFlow<DesktopLayoutMode> = _desktopLayoutMode.asStateFlow()

    fun setDesktopLayoutMode(mode: DesktopLayoutMode) {
        prefs.edit().putString(KEY_DESKTOP_LAYOUT, mode.name).apply()
        _desktopLayoutMode.value = mode
    }

    companion object {
        private const val PREFS_NAME = "quickgit_prefs"
        private const val KEY_DESKTOP_LAYOUT = "desktop_layout_mode"
    }
}
