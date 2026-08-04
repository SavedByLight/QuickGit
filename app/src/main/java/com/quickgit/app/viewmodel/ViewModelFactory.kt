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
        return when (modelClass) {
            RepoListViewModel::class.java -> RepoListViewModel(gitApp.repoManager) as T
            CloneViewModel::class.java -> CloneViewModel(gitApp.repoManager) as T
            RepoDetailViewModel::class.java -> RepoDetailViewModel(gitApp.repoManager) as T
            HistoryViewModel::class.java -> HistoryViewModel(gitApp.repoManager) as T
            BranchesViewModel::class.java -> BranchesViewModel(gitApp.repoManager) as T
            DiffViewModel::class.java -> DiffViewModel(gitApp.repoManager) as T
            MergeViewModel::class.java -> MergeViewModel(gitApp.repoManager) as T
            SettingsViewModel::class.java -> SettingsViewModel(gitApp.credentialStore) as T
            FilesViewModel::class.java -> FilesViewModel(gitApp.repoManager) as T
            EditorViewModel::class.java -> EditorViewModel(gitApp.repoManager) as T
            LogsViewModel::class.java -> LogsViewModel() as T
            else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
        }
    }
}
