package tw.saietf.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ShioajiGatewaySettingsStore(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun save(
        sseUrl: String,
        bearerToken: String?,
    ) {
        val url = sseUrl.trim()
        require(url.startsWith("https://")) { "Shioaji Gateway 必須使用 HTTPS" }
        preferences.edit().putString(KEY_URL, url).apply()

        val token = bearerToken?.trim().orEmpty()
        if (token.isBlank()) {
            preferences.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).apply()
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            preferences.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .apply()
        }
    }

    fun url(): String? =
        preferences.getString(KEY_URL, null)?.trim()?.takeIf { it.startsWith("https://") }

    fun bearerToken(): String? {
        val iv = preferences.getString(KEY_IV, null)?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        } ?: return null
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null)?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun isConfigured(): Boolean = url() != null

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFERENCES_NAME = "saietf-shioaji-gateway"
        private const val KEY_URL = "sse-url"
        private const val KEY_IV = "token-iv"
        private const val KEY_CIPHERTEXT = "token-ciphertext"
        private const val KEY_ALIAS = "saietf-shioaji-gateway-token-v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
