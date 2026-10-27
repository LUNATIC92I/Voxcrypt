package com.voxcrypt.app.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Lowest accepted iteration count keeps the suite fast; production uses 600 000. */
const val TEST_ITERATIONS = VxcFormat.MIN_PBKDF2_ITERATIONS
const val SMALL_SEGMENT = VxcFormat.MIN_SEGMENT_SIZE

val PASSWORD = "Correct-Horse-Battery-42".toCharArray()

fun sampleMetadata(length: Long = -1) =
    VxcMetadata("memo_secret.m4a", "audio/mp4", 1_700_000_000_000L, 12_345L, length)

/** Pseudo audio with a recognisable repeating pattern (easy to search for leaks). */
fun fakeAudio(size: Int, seed: Long = 7): ByteArray {
    val header = "ftypM4A isomAAC-LC-AUDIO-PATTERN".toByteArray()
    val out = ByteArray(size)
    val rnd = java.util.Random(seed)
    for (i in out.indices) {
        out[i] = if (i % 512 < header.size) header[i % 512] else rnd.nextInt(256).toByte()
    }
    return out
}

fun encryptWithPassword(
    engine: VxcEngine,
    audio: ByteArray,
    password: CharArray = PASSWORD,
    segment: Int = SMALL_SEGMENT,
    metadata: VxcMetadata = sampleMetadata(audio.size.toLong()),
): ByteArray {
    val out = ByteArrayOutputStream()
    engine.preparePassword(password, TEST_ITERATIONS, segment).use { key ->
        engine.encrypt(key, metadata, ByteArrayInputStream(audio), out)
    }
    return out.toByteArray()
}

data class Decrypted(val metadata: VxcMetadata, val audio: ByteArray)

fun decryptWithPassword(engine: VxcEngine, file: ByteArray, password: CharArray = PASSWORD): Decrypted {
    val input = ByteArrayInputStream(file)
    val header = VxcHeader.read(input)
    val out = ByteArrayOutputStream()
    val meta = engine.openPassword(header, password).use { key -> engine.decrypt(key, input, out) }
    return Decrypted(meta, out.toByteArray())
}

/** JVM stand in for the Android Keystore wrapper (same AES-GCM wrapping logic). */
class InMemoryDeviceKeyWrapper : DeviceKeyWrapper {
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

    override fun wrap(dataKey: ByteArray, associatedData: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(associatedData)
        return iv + c.doFinal(dataKey)
    }

    override fun unwrap(wrapped: ByteArray, associatedData: ByteArray): ByteArray {
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, 12))
            c.updateAAD(associatedData)
            return c.doFinal(wrapped, 12, wrapped.size - 12)
        } catch (e: AEADBadTagException) {
            throw VxcException.DeviceKeyUnavailable(e)
        }
    }
}

fun ByteArray.indexOf(needle: ByteArray): Int {
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}
