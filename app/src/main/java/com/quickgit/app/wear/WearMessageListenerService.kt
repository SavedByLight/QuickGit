package com.quickgit.app.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.quickgit.app.QuickGitApp

/**
 * Receives messages from the Wear companion and triggers a repo-list sync.
 */
class WearMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearSyncManager.PATH_REQUEST_SYNC -> {
                Log.i(TAG, "Watch requested sync from node ${messageEvent.sourceNodeId}")
                val app = applicationContext as? QuickGitApp
                app?.wearSyncManager?.syncReposToWear()
            }
            else -> Log.d(TAG, "Ignored message path=${messageEvent.path}")
        }
    }

    companion object {
        private const val TAG = "WearMsgListener"
    }
}
