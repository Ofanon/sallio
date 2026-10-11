package fr.sallio.pronote

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.RSAPublicKeySpec
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The primitives PRONOTE builds its protocol on. They mirror what Pawnote
 * and pronotepy do with node-forge / pycryptodome: the AES key and IV are
 * never used raw, they go through MD5 first, and an empty IV means sixteen
 * zero bytes.
 */
internal object Crypto {

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher {
        val realIv = if (iv.isEmpty()) ByteArray(16) else md5(iv)
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(md5(key), "AES"), IvParameterSpec(realIv))
        }
    }

    fun aesEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(data)

    fun aesDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, iv).doFinal(data)

    /** RSAES-PKCS1-v1_5, which is node-forge's default for `publicKey.encrypt`. */
    fun rsaEncrypt(data: ByteArray, modulusHex: String, exponentHex: String): ByteArray {
        val spec = RSAPublicKeySpec(BigInteger(modulusHex, 16), BigInteger(exponentHex, 16))
        val key = KeyFactory.getInstance("RSA").generatePublic(spec)
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }.doFinal(data)
    }

    /** zlib level 6 without headers, like pako.deflateRaw. */
    fun deflateRaw(data: ByteArray): ByteArray {
        val deflater = Deflater(6, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer))
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    fun inflateRaw(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            // A raw stream needs one extra dummy byte to be fully consumed.
            inflater.setInput(data + 0)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun hex(data: ByteArray): String {
        val out = CharArray(data.size * 2)
        for (i in data.indices) {
            val v = data[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    fun unhex(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd hex length" }
        return ByteArray(text.length / 2) {
            ((Character.digit(text[it * 2], 16) shl 4) or Character.digit(text[it * 2 + 1], 16)).toByte()
        }
    }
}
