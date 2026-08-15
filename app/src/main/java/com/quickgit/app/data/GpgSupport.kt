package com.quickgit.app.data

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.GpgSignature
import org.eclipse.jgit.lib.GpgSigner
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.CredentialsProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.Security

/**
 * OpenPGP commit signing using a user-imported armored secret key (no system gpg binary).
 * Registers as JGit's default [GpgSigner] for the duration of a signed commit.
 */
object GpgSupport {

    private val TAG = "GpgSupport"

    init {
        ensureFullBouncyCastle()
    }

    /**
     * Android registers a stripped-down provider under the name "BC" that lacks many
     * algorithms (including SHA1 used by OpenPGP S2K for encrypted secret keys).
     * Always replace it with the full Bouncy Castle from the app classpath.
     * Without this, unlock fails with: "no such algorithm: SHA1 for provider BC".
     */
    private fun ensureFullBouncyCastle() {
        try {
            // Remove every provider named BC (Android's incomplete one and any prior install)
            while (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) != null) {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            }
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            AppLog.i(TAG, "Registered full BouncyCastle provider at position 1")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to register BouncyCastle: ${e.message}", e)
            // Last resort — add if somehow still missing
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    /**
     * Installs a temporary default signer that uses [armoredSecretKey], runs [block], then
     * restores the previous default signer.
     */
    fun <T> withStoredKeySigner(armoredSecretKey: String, passphrase: String?, block: () -> T): T {
        val previous = GpgSigner.getDefault()
        GpgSigner.setDefault(StoredKeyGpgSigner(armoredSecretKey, passphrase))
        return try {
            block()
        } finally {
            GpgSigner.setDefault(previous)
        }
    }

    /**
     * Validates that the armored blob parses as a secret key ring with at least one
     * usable secret key, and that the passphrase (if any) unlocks it.
     * @return hex key id of the signing key
     */
    fun validateArmoredSecretKey(armored: String, passphrase: String?): String {
        ensureFullBouncyCastle()
        val cleaned = armored.trim()
        if (cleaned.isEmpty()) {
            throw IllegalArgumentException("Key is empty")
        }
        if (!cleaned.contains("-----BEGIN") || !cleaned.contains("-----END")) {
            throw IllegalArgumentException(
                "Not an ASCII-armored key. Export with: gpg --armor --export-secret-keys YOUR_KEY_ID"
            )
        }
        if (cleaned.contains("PUBLIC KEY")) {
            throw IllegalArgumentException(
                "This looks like a public key. Paste the SECRET / PRIVATE key block instead."
            )
        }
        val secretKey = try {
            findSigningSecretKey(cleaned)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Could not parse armored key: ${e.message ?: e.javaClass.simpleName}. " +
                    "Export with: gpg --armor --export-secret-keys YOUR_KEY_ID",
                e
            )
        } ?: throw IllegalArgumentException(
            "No usable secret key found in the armored block. " +
                "Make sure you exported a secret key (not public) and that it includes a signing subkey."
        )
        try {
            extractPrivateKey(secretKey, passphrase)
        } catch (e: PGPException) {
            throw e
        } catch (e: Exception) {
            throw PGPException("Could not unlock GPG key: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return java.lang.Long.toHexString(secretKey.keyID).uppercase()
    }

    private class StoredKeyGpgSigner(
        private val armoredSecretKey: String,
        private val passphrase: String?
    ) : GpgSigner() {

        override fun canLocateSigningKey(
            gpgSigningKey: String?,
            committer: PersonIdent?,
            credentialsProvider: CredentialsProvider?
        ): Boolean = try {
            findSigningSecretKey(armoredSecretKey) != null
        } catch (_: Exception) {
            false
        }

        override fun sign(
            commit: CommitBuilder,
            gpgSigningKey: String?,
            committer: PersonIdent,
            credentialsProvider: CredentialsProvider?
        ) {
            commit.setGpgSignature(null)
            val payload = commit.build()
            val signatureBytes = createDetachedSignature(payload, armoredSecretKey, passphrase)
            commit.setGpgSignature(GpgSignature(signatureBytes))
        }
    }

    private fun createDetachedSignature(payload: ByteArray, armored: String, passphrase: String?): ByteArray {
        ensureFullBouncyCastle()
        val secretKey = findSigningSecretKey(armored)
            ?: throw PGPException("No signing-capable secret key found")
        val privateKey = extractPrivateKey(secretKey, passphrase)
        val generator = PGPSignatureGenerator(
            JcaPGPContentSignerBuilder(
                secretKey.publicKey.algorithm,
                HashAlgorithmTags.SHA256
            ).setProvider(BouncyCastleProvider.PROVIDER_NAME)
        )
        generator.init(PGPSignature.BINARY_DOCUMENT, privateKey)
        generator.update(payload)
        val signature = generator.generate()
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armoredOut ->
            signature.encode(armoredOut)
        }
        return out.toByteArray()
    }

    /**
     * Prefer a non-empty signing-capable secret key; fall back to any non-empty secret key
     * (some exports mark the primary key oddly, or only include a signing subkey).
     */
    private fun findSigningSecretKey(armored: String): PGPSecretKey? {
        val input = PGPUtil.getDecoderStream(ByteArrayInputStream(armored.toByteArray(Charsets.UTF_8)))
        val rings = PGPSecretKeyRingCollection(input, JcaKeyFingerprintCalculator())
        var fallback: PGPSecretKey? = null
        val ringIt = rings.keyRings
        while (ringIt.hasNext()) {
            val keyRing = ringIt.next() as PGPSecretKeyRing
            val keyIt = keyRing.secretKeys
            while (keyIt.hasNext()) {
                val key = keyIt.next() as PGPSecretKey
                if (key.isPrivateKeyEmpty) continue
                if (key.isSigningKey) return key
                if (fallback == null) fallback = key
            }
        }
        return fallback
    }

    private fun extractPrivateKey(secretKey: PGPSecretKey, passphrase: String?): PGPPrivateKey {
        ensureFullBouncyCastle()
        val decryptorBuilder = JcePBESecretKeyDecryptorBuilder()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        val chars = passphrase?.toCharArray() ?: CharArray(0)
        return try {
            secretKey.extractPrivateKey(decryptorBuilder.build(chars))
        } catch (e: Exception) {
            val detail = (e.message ?: "") + " " + (e.cause?.message ?: "")
            when {
                detail.contains("SHA1", ignoreCase = true) ||
                    detail.contains("no such algorithm", ignoreCase = true) ->
                    throw PGPException(
                        "OpenPGP crypto provider is incomplete on this device (SHA1/BC). " +
                            "Update the app and try again. Technical: ${e.message}",
                        e
                    )
                chars.isEmpty() ->
                    throw PGPException(
                        "GPG key is encrypted — enter the passphrase in Settings and tap Save again",
                        e
                    )
                else ->
                    throw PGPException("Could not unlock GPG key — check the passphrase (${e.message})", e)
            }
        }
    }
}
