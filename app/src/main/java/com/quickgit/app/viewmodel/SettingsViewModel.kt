package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import com.quickgit.app.data.CredentialStore

class SettingsViewModel(private val credentialStore: CredentialStore) : ViewModel() {

    fun saveHttpsToken(host: String, username: String, token: String) =
        credentialStore.saveHttpsToken(host, username, token)

    fun clearHttpsToken(host: String) = credentialStore.clearHttpsToken(host)

    fun hasHttpsToken(host: String) = credentialStore.hasHttpsCredential(host)

    fun saveSshKey(pem: String, passphrase: String?) = credentialStore.saveSshKey(pem, passphrase)

    fun hasSshKey() = credentialStore.hasSshKey()

    fun clearSshKey() = credentialStore.clearSshKey()
}
