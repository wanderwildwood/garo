package com.wanderwildwood.garo.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Nextcloud app password, sealed: it opens every file on the account, so it is not kept as
 * plain text even in the app's own preferences, which other apps cannot read and which are left
 * out of every backup. It is sealed with a key generated inside the phone's hardware-backed
 * keystore, which cannot be exported and is not itself backed up. The stored value is
 * `enc:v1:base64(iv + ciphertext)`.
 *
 * The same file as Audio Reading's and Music Box's, under this app's own key alias.
 *
 * If the keystore key is gone -- a reinstall, or the file reaching another phone -- unsealing
 * fails and returns nothing, and the app behaves as though no server was set up and asks again.
 * That is the right outcome: the alternative is a login that silently cannot work.
 */
object Secrets {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "garo.secrets.v1"
    private const val PREFIX = "enc:v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun seal(plain: String?): String? {
        if (plain.isNullOrEmpty()) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Better to store nothing than to store it in the clear after promising not to.
            android.util.Log.w("Secrets", "could not seal a secret", e)
            null
        }
    }

    fun open(stored: String?): String? {
        if (stored.isNullOrEmpty()) return null
        if (!stored.startsWith(PREFIX)) return stored          // written before this existed
        return try {
            val blob = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
            )
            String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.w("Secrets", "could not open a secret; treating it as absent", e)
            null
        }
    }

    /** True for a value this has already sealed, so a migration can tell what still needs it. */
    fun isSealed(stored: String?): Boolean = stored?.startsWith(PREFIX) == true

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately not requiring the screen to be unlocked: backing up runs at night
                // with the phone locked on its charger, and a key that could not be opened while
                // the screen was locked would stop it.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
