package com.quickgit.app.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.quickgit.app.QuickGitApp

/**
 * Receives /quickgit/request_sync from the watch and replies with the repo list.
 */
class WearMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.i(TAG, "Message path=${messageEvent.path} from=${messageEvent.sourceNodeId}")
        when (messageEvent.path) {
            WearSyncManager.PATH_REQUEST_SYNC -> {
                val app = applicationContext as? QuickGitApp
                if (app == null) {
                    Log.e(TAG, "Application is not QuickGitApp — cannot reply")
                    return
                }
                // Application.onCreate always builds wearSyncManager before services run.
                runCatching {
                    app.wearSyncManager.replyReposToNode(messageEvent.sourceNodeId)
                }.onFailure {
                    Log.e(TAG, "reply failed: ${it.message}")
                }
            }
            else -> Log.d(TAG, "Ignored path=${messageEvent.path}")
        }
    }

    companion object {
        private const val TAG = "WearMsgListener"
    }
}
