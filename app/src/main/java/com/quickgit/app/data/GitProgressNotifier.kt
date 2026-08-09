package com.quickgit.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.quickgit.app.MainActivity
import com.quickgit.app.R

/**
 * Shows ongoing progress for long git operations (clone / push / pull) as a
 * notification. On many devices an ongoing progress notification appears as a
 * persistent chip / "pill" in the status bar; otherwise it lives in the
 * notification shade. Call [start], then [update] with status text (and optional
 * percent), then [finish] or [cancel].
 */
class GitProgressNotifier(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "git_progress"
        private const val CHANNEL_NAME = "Git progress"
        private const val NOTIFICATION_ID = 42001
    }

    private val nm = NotificationManagerCompat.from(context)
    private var title: String = "QuickGit"
    private var lastPercent: Int = -1

    init {
        ensureChannel()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while cloning, pushing, or pulling"
                setShowBadge(false)
            }
            val sysNm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            sysNm.createNotificationChannel(channel)
        }
    }

    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start (or replace) the ongoing progress notification.
     * @param title Short title, e.g. "Cloning…" or "Pushing…"
     * @param text  Initial status line
     */
    fun start(title: String, text: String = "Starting…") {
        this.title = title
        this.lastPercent = -1
        post(text, indeterminate = true)
    }

    /**
     * Update the status text. If [percent] is 0–100 the notification shows a
     * determinate progress bar; otherwise it stays indeterminate.
     */
    fun update(text: String, percent: Int? = null) {
        val p = percent?.coerceIn(0, 100)
        if (p != null) lastPercent = p
        post(text, indeterminate = p == null, percent = p ?: lastPercent.coerceAtLeast(0))
    }

    /** Mark the operation finished successfully and auto-dismiss after a short delay. */
    fun finish(text: String = "Done") {
        post(text, indeterminate = false, percent = 100, ongoing = false, autoCancel = true)
        // Clear shortly so it doesn't linger
        android.os.Handler(context.mainLooper).postDelayed({ cancel() }, 2500)
    }

    /** Remove the notification (error or user cancelled). */
    fun cancel() {
        nm.cancel(NOTIFICATION_ID)
    }

    private fun post(
        text: String,
        indeterminate: Boolean,
        percent: Int = 0,
        ongoing: Boolean = true,
        autoCancel: Boolean = false
    ) {
        if (!hasPermission()) return

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher) // reuse launcher; replace with a dedicated icon later if desired
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(autoCancel)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, percent, false)
        }

        try {
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            AppLog.w("GitProgressNotifier", "notify failed: ${e.message}")
        }
    }
}
