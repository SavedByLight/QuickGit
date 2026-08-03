package com.quickgit.app

import android.app.Application
import com.quickgit.app.data.CredentialStore
import com.quickgit.app.data.RepoManager

class QuickGitApp : Application() {
    lateinit var credentialStore: CredentialStore
        private set
    lateinit var repoManager: RepoManager
        private set

    override fun onCreate() {
        super.onCreate()
        credentialStore = CredentialStore(this)
        repoManager = RepoManager(this, credentialStore)
    }
}
