package com.quickgit.app

import android.app.Application
import com.quickgit.app.data.CredentialStore
import com.quickgit.app.data.GitHubAccountManager
import com.quickgit.app.data.IssueManager
import com.quickgit.app.data.PullRequestManager
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.WorkflowManager

class QuickGitApp : Application() {
    lateinit var credentialStore: CredentialStore
        private set
    lateinit var repoManager: RepoManager
        private set
    lateinit var pullRequestManager: PullRequestManager
        private set
    lateinit var gitHubAccountManager: GitHubAccountManager
        private set
    lateinit var issueManager: IssueManager
        private set
    lateinit var workflowManager: WorkflowManager
        private set

    override fun onCreate() {
        super.onCreate()
        credentialStore = CredentialStore(this)
        repoManager = RepoManager(this, credentialStore)
        pullRequestManager = PullRequestManager(repoManager, credentialStore)
        gitHubAccountManager = GitHubAccountManager(credentialStore)
        issueManager = IssueManager(repoManager, credentialStore)
        workflowManager = WorkflowManager(repoManager, credentialStore)
    }
}
