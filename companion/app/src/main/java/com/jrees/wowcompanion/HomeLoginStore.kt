package com.jrees.wowcompanion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * THE SAVED BATTLE.NET LOGIN (LATER 29: "Stored Keystore-encrypted, our app only"). One file in the app's private folder, sealed with
 * an AES key that lives in Android's Keystore and never leaves it: the file alone opens nowhere else, not even in a copy of this app
 * on another device. Nothing here is ever logged, shown again (the prompt starts empty), or sent anywhere but the game's two fields.
 *
 * The gear overwrites it ([save]); there is no "show me my password". A key the Keystore lost (a device reset, a restore onto another
 * phone) makes [load] answer null, and the waiting screen asks for the login again, as on the first day.
 */
object HomeLoginStore {
    private const val KEY = "ds_bnet_login_v1"
    private const val FILE = "bnet_login.bin"

    data class Login(val email: String, val password: String)

    fun has(ctx: Context) = File(ctx.noBackupFilesDir, FILE).isFile

    fun save(ctx: Context, login: Login): Boolean = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val body = JSONObject().put("e", login.email).put("p", login.password).toString().toByteArray(Charsets.UTF_8)
        val sealed = c.doFinal(body)
        body.fill(0)
        val f = File(ctx.noBackupFilesDir, FILE)
        val tmp = File(ctx.noBackupFilesDir, "$FILE.part")
        tmp.outputStream().use { o -> o.write(c.iv.size); o.write(c.iv); o.write(sealed) }
        tmp.renameTo(f)
    } catch (_: Throwable) { false }

    fun load(ctx: Context): Login? = try {
        val bytes = File(ctx.noBackupFilesDir, FILE).readBytes()
        val n = bytes[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, n))
        val body = c.doFinal(bytes, 1 + n, bytes.size - 1 - n)
        val j = JSONObject(String(body, Charsets.UTF_8))
        body.fill(0)
        Login(j.getString("e"), j.getString("p"))
    } catch (_: Throwable) { null }

    fun clear(ctx: Context) { File(ctx.noBackupFilesDir, FILE).delete() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return g.generateKey()
    }
}
