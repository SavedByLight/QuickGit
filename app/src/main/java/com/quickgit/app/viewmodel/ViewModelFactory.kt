package com.quickgit.app.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.quickgit.app.QuickGitApp

class ViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    private val gitApp get() = app as QuickGitApp

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        return create(modelClass)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val vm: ViewModel = when {
            modelClass.isAssignableFrom(RepoListViewModel::class.java) ->
                RepoListViewModel(gitApp.repoManager, gitApp.gitHubAccountManager)
            modelClass.isAssignableFrom(CloneViewModel::class.java) ->
                CloneViewModel(gitApp.repoManager)
            modelClass.isAssignableFrom(RepoDetailViewModel::class.java) ->
                RepoDetailViewModel(gitApp.repoManager)
            modelClass.isAssignableFrom(HistoryViewModel::class.java) ->
                HistoryViewModel(gitApp.repoManager)
            modelClass.isAssignableFrom(BranchesViewModel::class.java) ->
                BranchesViewModel(gitApp.repoManager)
            modelClass.isAssignableFrom(DiffViewModel::class.java) ->
                DiffViewModel(gitApp.repoManager)
            modelClass.isAssignableFrom(MergeViewModel::class.java) ->
                MergeViewModel(gitApp.repoManager)
            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(
                    gitApp.credentialStore,
                    gitApp.repoManager,
                    gitApp.gitHubAccountManager,
                    gitApp.gitLabAccountManager,
                    gitApp.gerritAccountManager,
                    gitApp.appUpdateManager
                )
            modelClass.isAssignableFrom(FilesViewModel::class.java) ->
                FilesViewModel(gitApp.repoManager)
            modelClass.isAssignableFrom(EditorViewModel::class.java) ->
                EditorViewModel(gitApp.repoManager)
            modelClass.isAssignableFrom(LogsViewModel::class.java) ->
                LogsViewModel()
            modelClass.isAssignableFrom(PullRequestsViewModel::class.java) ->
                PullRequestsViewModel(gitApp.repoManager, gitApp.pullRequestManager)
            modelClass.isAssignableFrom(IssuesViewModel::class.java) ->
                IssuesViewModel(gitApp.issueManager)
            modelClass.isAssignableFrom(WorkflowsViewModel::class.java) ->
                WorkflowsViewModel(gitApp.workflowManager)
            modelClass.isAssignableFrom(ReleasesViewModel::class.java) ->
                ReleasesViewModel(gitApp.releaseManager)
            modelClass.isAssignableFrom(BrowseGitHubViewModel::class.java) ->
                BrowseGitHubViewModel(
                    gitApp.gitHubAccountManager,
                    gitApp.gitLabAccountManager,
                    gitApp.gerritAccountManager,
                    gitApp.repoManager
                )
            modelClass.isAssignableFrom(ProfileViewModel::class.java) ->
                ProfileViewModel(gitApp.gitHubAccountManager)
            modelClass.isAssignableFrom(UserSearchViewModel::class.java) ->
                UserSearchViewModel(gitApp.gitHubAccountManager)
            modelClass.isAssignableFrom(RemoteBrowseViewModel::class.java) ->
                RemoteBrowseViewModel(gitApp.gitHubAccountManager)
            modelClass.isAssignableFrom(RemoteFileViewModel::class.java) ->
                RemoteFileViewModel(gitApp.gitHubAccountManager)
            else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
        }
        return vm as T
    }
}
