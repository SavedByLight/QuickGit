package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.RepoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RepoListViewModel(private val repoManager: RepoManager) : ViewModel() {

    private val _repos = MutableStateFlow<List<RepoInfo>>(emptyList())
    val repos: StateFlow<List<RepoInfo>> = _repos.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _repos.value = withContext(Dispatchers.IO) { repoManager.listLocalRepos() }
            _loading.value = false
        }
    }

    fun deleteRepo(repo: RepoInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.dir.deleteRecursively()
            withContext(Dispatchers.Main) { refresh() }
        }
    }
}
