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
 * Progress notifications for long-running work: clone, push/pull, and Actions runs.
 * Use distinct [Kind] values so concurrent operations do not overwrite each other.
 */
class GitProgressNotifier(context: Context) {

    private val appContext = context.applicationContext

    enum class Kind(val notificationId: Int, val defaultTitle: String) {
        CLONE(42001, "Cloning…"),
        PUSH(42002, "Pushing…"),
        PULL(42003, "Pulling…"),
        ACTIONS(42004, "Actions…")
    }

    companion object {
        private const val TAG = "GitProgressNotifier"
        const val CHANNEL_ID = "git_progress"
        private const val CHANNEL_NAME = "Git progress"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val sysNm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (sysNm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Progress for clone, push, pull, and workflow runs"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            sysNm.createNotificationChannel(channel)
            AppLog.i(TAG, "notification channel created: $CHANNEL_ID")
        }
    }

    private val nm = NotificationManagerCompat.from(appContext)
    private val titles = mutableMapOf<Kind, String>()
    private val lastPercent = mutableMapOf<Kind, Int>()
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
        if (!nm.areNotificationsEnabled()) {
            AppLog.w(TAG, "notifications disabled for app (system setting)")
            return false
        }
        return true
    }

    fun start(kind: Kind, title: String = kind.defaultTitle, text: String = "Starting…") {
        titles[kind] = title
        lastPercent[kind] = -1
        post(kind, text, indeterminate = true)
    }

    fun start(title: String, text: String = "Starting…") {
        val kind = when {
            title.contains("clon", ignoreCase = true) -> Kind.CLONE
            title.contains("pull", ignoreCase = true) -> Kind.PULL
            title.contains("action", ignoreCase = true) ||
                title.contains("workflow", ignoreCase = true) -> Kind.ACTIONS
            else -> Kind.PUSH
        }
        start(kind, title, text)
    }

    fun update(kind: Kind, text: String, percent: Int? = null) {
        val p = percent?.coerceIn(0, 100)
        if (p != null) lastPercent[kind] = p
        post(
            kind,
            text,
            indeterminate = p == null,
            percent = p ?: lastPercent[kind]?.coerceAtLeast(0) ?: 0
        )
    }

    fun update(text: String, percent: Int? = null) {
        val kind = titles.keys.lastOrNull() ?: Kind.PUSH
        update(kind, text, percent)
    }

    fun finish(kind: Kind, text: String = "Done") {
        post(kind, text, indeterminate = false, percent = 100, ongoing = false, autoCancel = true)
        mainHandler.postDelayed({ cancel(kind) }, 3500)
    }

    fun finish(text: String = "Done") {
        val kind = titles.keys.lastOrNull() ?: Kind.PUSH
        finish(kind, text)
    }

    fun cancel(kind: Kind) {
        try {
            nm.cancel(kind.notificationId)
        } catch (e: Exception) {
            AppLog.w(TAG, "cancel failed: ${e.message}")
        }
        titles.remove(kind)
        lastPercent.remove(kind)
    }

    fun cancel() {
        val kinds = titles.keys.toList().ifEmpty { Kind.entries }
        kinds.forEach { cancel(it) }
    }

    private fun post(
        kind: Kind,
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
            kind.notificationId,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = titles[kind] ?: kind.defaultTitle
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
            .setCategory(
                if (kind == Kind.ACTIONS) NotificationCompat.CATEGORY_STATUS
                else NotificationCompat.CATEGORY_PROGRESS
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, percent, false)
        }

        try {
            nm.notify(kind.notificationId, builder.build())
        } catch (e: SecurityException) {
            AppLog.w(TAG, "notify failed (permission): ${e.message}")
        } catch (e: Exception) {
            AppLog.e(TAG, "notify failed", e)
        }
    }
}
