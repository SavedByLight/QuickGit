package com.quickgit.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.quickgit.app.MainActivity
import com.quickgit.app.R

/**
 * Shows ongoing progress for long git operations (clone / push / pull) as a
 * notification. Call [start], then [update], then [finish] or [cancel].
 *
 * Requires [POST_NOTIFICATIONS] on Android 13+ and a monochrome [R.drawable.ic_notification].
 */
class GitProgressNotifier(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "GitProgressNotifier"
        const val CHANNEL_ID = "git_progress"
        private const val CHANNEL_NAME = "Git progress"
        private const val NOTIFICATION_ID = 42001

        /** Create the progress channel early (Application.onCreate). Safe to call repeatedly. */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val sysNm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = sysNm.getNotificationChannel(CHANNEL_ID)
            if (existing != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                // DEFAULT so the shade entry is visible; setOnlyAlertOnce avoids sound spam.
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows progress while cloning, pushing, or pulling"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            sysNm.createNotificationChannel(channel)
            AppLog.i(TAG, "notification channel created: $CHANNEL_ID")
        }
    }

    private val nm = NotificationManagerCompat.from(appContext)
    private var title: String = "QuickGit"
    private var lastPercent: Int = -1
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        ensureChannel(appContext)
    }

    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                AppLog.w(TAG, "POST_NOTIFICATIONS not granted")
                return false
            }
        }
        // Also respect the system-level "app notifications" toggle
        if (!nm.areNotificationsEnabled()) {
            AppLog.w(TAG, "notifications disabled for app (system setting)")
            return false
        }
        return true
    }

    fun start(title: String, text: String = "Starting…") {
        this.title = title
        this.lastPercent = -1
        post(text, indeterminate = true)
    }

    fun update(text: String, percent: Int? = null) {
        val p = percent?.coerceIn(0, 100)
        if (p != null) lastPercent = p
        post(text, indeterminate = p == null, percent = p ?: lastPercent.coerceAtLeast(0))
    }

    fun finish(text: String = "Done") {
        post(text, indeterminate = false, percent = 100, ongoing = false, autoCancel = true)
        mainHandler.postDelayed({ cancel() }, 2500)
    }

    fun cancel() {
        try {
            nm.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            AppLog.w(TAG, "cancel failed: ${e.message}")
        }
    }

    private fun post(
        text: String,
        indeterminate: Boolean,
        percent: Int = 0,
        ongoing: Boolean = true,
        autoCancel: Boolean = false
    ) {
        if (!hasPermission()) return

        ensureChannel(appContext)

        val openApp = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(ongoing)
            .setAutoCancel(autoCancel)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, percent, false)
        }

        try {
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            AppLog.w(TAG, "notify failed (permission): ${e.message}")
        } catch (e: Exception) {
            AppLog.e(TAG, "notify failed", e)
        }
    }
}
