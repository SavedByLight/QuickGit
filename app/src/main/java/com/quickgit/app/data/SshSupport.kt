package com.quickgit.app.data

import org.eclipse.jgit.transport.sshd.KeyPasswordProvider
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import java.io.File

/**
 * Builds a JGit SshSessionFactory backed by a single user-imported key, rather than reading
 * from ~/.ssh (Android apps have no such thing). The key is materialized to a private,
 * app-only file just before each session and left in place for reuse.
 *
 * NOTE: host key verification is intentionally permissive here (accept-on-first-use is not
 * implemented) to keep the sample self-contained. For a production build, persist and check
 * known_hosts entries instead of trusting every host key.
 */
object SshSupport {

    fun buildSessionFactory(context: android.content.Context, credentialStore: CredentialStore): SshdSessionFactory {
        val sshDir = File(context.filesDir, ".ssh").apply { mkdirs() }
        val keyFile = File(sshDir, "imported_key")

        val pem = credentialStore.getSshPrivateKey()
        if (pem != null) {
            keyFile.writeText(pem)
            keyFile.setReadable(false, false)
            keyFile.setReadable(true, true)
            keyFile.setWritable(false, false)
            keyFile.setWritable(true, true)
        }

        val passphrase = credentialStore.getSshPassphrase()

        return SshdSessionFactoryBuilder()
            .setPreferredAuthentications("publickey")
            .setHomeDirectory(context.filesDir)
            .setSshDirectory(sshDir)
            .setDefaultIdentities { _ -> if (keyFile.exists()) listOf(keyFile.toPath()) else emptyList() }
            .setDefaultKeysProvider { _ -> emptyList() }
            .setKeyPasswordProvider { _ ->
                object : KeyPasswordProvider {
                    override fun getPassphrase(uri: org.eclipse.jgit.transport.URIish?, attempt: Int): CharArray? {
                        return passphrase?.takeIf { it.isNotEmpty() }?.toCharArray()
                    }
                }
            }
            // Accept any host key: acceptable for a first-run mobile client, not for hardened use.
            .setServerKeyDatabase { _, _ ->
                object : org.eclipse.jgit.transport.sshd.ServerKeyDatabase {
                    override fun lookup(
                        connectAddress: String,
                        remoteAddress: java.net.InetSocketAddress,
                        config: org.eclipse.jgit.transport.sshd.ServerKeyDatabase.Configuration
                    ): List<java.security.PublicKey> = emptyList()

                    override fun accept(
                        connectAddress: String,
                        remoteAddress: java.net.InetSocketAddress,
                        serverKey: java.security.PublicKey,
                        config: org.eclipse.jgit.transport.sshd.ServerKeyDatabase.Configuration,
                        provider: org.eclipse.jgit.transport.CredentialsProvider?
                    ): Boolean = true
                }
            }
            .build(null)
    }
}
