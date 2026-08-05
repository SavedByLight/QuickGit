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
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
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

    /** Validates that the armored blob parses as a secret key ring with at least one signing-capable key. */
    fun validateArmoredSecretKey(armored: String, passphrase: String?): String {
        val secretKey = findSigningSecretKey(armored)
            ?: throw IllegalArgumentException("No signing-capable secret key found in the armored key")
        extractPrivateKey(secretKey, passphrase) // fails if passphrase wrong / key unreadable
        val id = java.lang.Long.toHexString(secretKey.keyID).uppercase()
        return id
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

    private fun findSigningSecretKey(armored: String): PGPSecretKey? {
        val input = PGPUtil.getDecoderStream(ByteArrayInputStream(armored.toByteArray(Charsets.UTF_8)))
        val rings = PGPSecretKeyRingCollection(input, JcaKeyFingerprintCalculator())
        val ringIt = rings.keyRings
        while (ringIt.hasNext()) {
            val keyRing = ringIt.next() as PGPSecretKeyRing
            val keyIt = keyRing.secretKeys
            while (keyIt.hasNext()) {
                val key = keyIt.next() as PGPSecretKey
                if (!key.isPrivateKeyEmpty && key.isSigningKey) return key
            }
        }
        return null
    }

    private fun extractPrivateKey(secretKey: PGPSecretKey, passphrase: String?): PGPPrivateKey {
        val decryptorBuilder = JcePBESecretKeyDecryptorBuilder()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        val chars = passphrase?.toCharArray() ?: CharArray(0)
        return try {
            secretKey.extractPrivateKey(decryptorBuilder.build(chars))
        } catch (e: PGPException) {
            if (chars.isEmpty()) {
                throw PGPException("GPG key is encrypted — provide the passphrase in Settings", e)
            }
            throw PGPException("Could not unlock GPG key — check the passphrase", e)
        }
    }
}
