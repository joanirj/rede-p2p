package br.redep2p

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import br.redep2p.crypto.Schnorr
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Identidade do aparelho na rede: um par de chaves secp256k1 (Schnorr/BIP340),
 * o mesmo tipo usado na Kaspa. A chave privada fica num arquivo do app,
 * cifrada com uma chave AES guardada no Android Keystore.
 */
object Identity {
    private const val ALIAS = "redep2p-identidade"
    private val random = SecureRandom()
    private var secret: ByteArray? = null

    @Volatile
    var publicKey: ByteArray = ByteArray(32)
        private set

    val publicHex: String get() = Schnorr.hex(publicKey)

    /** Identificador curto para mostrar na tela. */
    val shortId: String get() = shortId(publicKey)

    fun shortId(key: ByteArray): String = Schnorr.hex(key).take(8)

    @Synchronized
    fun init(context: Context) {
        if (secret != null) return
        val file = File(context.filesDir, "identity.bin")
        val sk = try {
            if (file.exists()) decrypt(file.readBytes()) else null
        } catch (e: Exception) {
            AppLog.e("Não consegui ler a identidade salva; criando outra", e)
            null
        } ?: newSecret().also { file.writeBytes(encrypt(it)) }
        secret = sk
        publicKey = Schnorr.publicKey(sk)
    }

    /** Assina um hash de 32 bytes. */
    fun sign(hash: ByteArray): ByteArray {
        val sk = secret ?: throw IllegalStateException("identidade não carregada")
        val aux = ByteArray(32).also { random.nextBytes(it) }
        return Schnorr.sign(hash, sk, aux)
    }

    private fun newSecret(): ByteArray {
        while (true) {
            val sk = ByteArray(32).also { random.nextBytes(it) }
            try {
                Schnorr.publicKey(sk) // confere se está no intervalo válido
                return sk
            } catch (ignored: IllegalArgumentException) {
            }
        }
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(data)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(blob.copyOfRange(1 + ivLen, blob.size))
    }
}
