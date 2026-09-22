package com.quickgit.app.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.quickgit.app.QuickGitApp

/**
 * Background entry-point when the phone process is not already running.
 */
class WearMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.i(TAG, "Service message path=${messageEvent.path} from=${messageEvent.sourceNodeId}")
        val app = applicationContext as? QuickGitApp
        if (app == null) {
            Log.e(TAG, "Not QuickGitApp")
            return
        }
        // Prefer the shared manager (also has a foreground listener).
        runCatching {
            app.wearSyncManager.handleIncomingMessage(messageEvent)
        }.onFailure { Log.e(TAG, "handle failed: ${it.message}", it) }
    }

    companion object {
        private const val TAG = "WearMsgListener"
    }
}
