package com.asghar.downloader.utils

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/**
 * Keeps the opening dialog text out of plain source/resources.
 * Note: anything shipped inside an APK can ultimately be extracted by a determined
 * reverse engineer; this is obfuscation, not an unbreakable security boundary.
 */
object ProtectedMessage {
    private const val APP_ID = "com.asghar.downloader"
    private const val KEY_PART_1 = "Asghar"
    private const val KEY_PART_2 = "Downloader_2026"

    private const val IV_B64 = "u4a7LtXVbVQsS56pKCUR7Q=="
    private const val CIPHER_B64 = "4g9GgdNnGUONgsAcPy1Q1O0LcXDN2skid0Z/ok8muFHg/lz3w6N/cwKI10ULrTxU6MY7luEFyZBH5FU0jIzYynf4wpF9MxMBxAYoKPMn95LkjvBvgDezSBvaeyJ+K8nlvuP8PqCEWL7jc8fJ4A7sgdDrFj1SpUh0Sx3QdO4eVTzXkgbYdJ1SPZjk5gldmXSLVBwsVhAVWpjE98LnbnxeUL5ojQuzMyW+9THTDjBm/wU="

    fun get(): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val keyBytes = digest.digest("$KEY_PART_1:$KEY_PART_2:$APP_ID".toByteArray(Charsets.UTF_8))
            val key = SecretKeySpec(keyBytes, "AES")
            val iv = IvParameterSpec(Base64.decode(IV_B64, Base64.DEFAULT))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            String(cipher.doFinal(Base64.decode(CIPHER_B64, Base64.DEFAULT)), Charsets.UTF_8)
        } catch (_: Exception) {
            "Welcome to Asghar Downloader!"
        }
    }
}
