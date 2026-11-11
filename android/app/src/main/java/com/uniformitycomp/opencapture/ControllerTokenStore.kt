package com.uniformitycomp.opencapture

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Opt-in Remote ready credentials, encrypted on this device and bound to one exact origin. */
class ControllerTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("opencapture", Context.MODE_PRIVATE)
    private val alias = "OpenCapture.RemoteReady"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun save(origin: String, token: String) {
        if (token.isEmpty()) {
            check(preferences.edit().remove("controllerToken").commit()) { "Cannot save controller settings" }
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(origin.toByteArray(Charsets.UTF_8)) }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val record = JSONObject().put("origin", origin).put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("value", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        check(preferences.edit().putString("controllerToken", record.toString()).commit()) { "Cannot save controller settings" }
    }
    fun load(origin: String): String {
        val encoded = preferences.getString("controllerToken", null) ?: return ""
        val record = JSONObject(encoded)
        if (record.getString("origin") != origin) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(record.getString("iv"), Base64.NO_WRAP)))
            updateAAD(origin.toByteArray(Charsets.UTF_8))
        }
        return String(cipher.doFinal(Base64.decode(record.getString("value"), Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
