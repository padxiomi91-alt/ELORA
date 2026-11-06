package com.elora.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted local store. The AES key never leaves Android Keystore;
 * pairing credentials are encrypted before being written to app-private preferences.
 */
class EloraSecureStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("elora_secure", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    @Synchronized
    fun ownerId(): String = getOrCreate("owner_id")

    @Synchronized
    fun deviceId(): String = getOrCreate("viewer_device_id")

    @Synchronized
    fun trustCredential(ownerId: String): String? = read("trust_$ownerId")

    @Synchronized
    fun saveTrustCredential(ownerId: String, credential: String) {
        write("trust_$ownerId", credential)
    }

    @Synchronized
    fun forgetTrust(ownerId: String) {
        preferences.edit().remove("trust_$ownerId").apply()
    }

    private fun getOrCreate(name: String): String {
        return read(name) ?: ByteArray(32).also(random::nextBytes).let {
            Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.also { write(name, it) }
    }

    private fun read(name: String): String? {
        val encoded = preferences.getString(name, null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore requires it to generate the IV when randomized encryption is enabled.
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = iv + encrypted
        preferences.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "elora_local_encryption_v1"
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
