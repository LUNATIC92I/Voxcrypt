package com.voxcrypt.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * Non regression of format version 1. A reference file produced by version 1.0.0
 * is committed in test resources: future versions must keep decrypting it, and the
 * encoder must keep producing the exact same bytes for the same random input.
 *
 * Regenerate (only when intentionally creating a NEW format version):
 *   ./gradlew :app:testDebugUnitTest -Dvxc.regenerateVectors=true
 */
class FormatCompatibilityTest {

    /** Deterministic "random" source: for vectors only, never used by the app. */
    private class FixedRandom : SecureRandom() {
        private var counter = 0
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = (counter++ * 31 + 7).toByte()
        }
    }

    private val password = "VOXCRYPT-test-vector-v1".toCharArray()
    private val audio = fakeAudio(10_000, seed = 2026)
    private val metadata = VxcMetadata("vector.m4a", "audio/mp4", 1_761_523_200_000L, 5_000L, audio.size.toLong())

    private fun produce(): ByteArray {
        val engine = VxcEngine(FixedRandom())
        val out = ByteArrayOutputStream()
        engine.preparePassword(password, VxcFormat.MIN_PBKDF2_ITERATIONS, VxcFormat.MIN_SEGMENT_SIZE).use {
            engine.encrypt(it, metadata, ByteArrayInputStream(audio), out)
        }
        return out.toByteArray()
    }

    private fun vector(): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(VECTOR)
        if (stream == null || System.getProperty("vxc.regenerateVectors") == "true") {
            val bytes = produce()
            val target = File("src/test/resources/$VECTOR")
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
            return bytes
        }
        return stream.use { it.readBytes() }
    }

    @Test
    fun referenceFile_v1_stillDecrypts() {
        val result = decryptWithPassword(VxcEngine(), vector(), password)
        assertArrayEquals(audio, result.audio)
        assertEquals(metadata, result.metadata)
    }

    @Test
    fun encoder_v1_isByteStable() {
        assertArrayEquals(vector(), produce())
    }

    @Test
    fun referenceFile_headerFields() {
        val h = VxcHeader.read(ByteArrayInputStream(vector()))
        assertEquals(1, h.version)
        assertEquals(KeyProtection.PASSWORD, h.protection)
        assertEquals(VxcFormat.MIN_PBKDF2_ITERATIONS, h.iterations)
        assertEquals(VxcFormat.MIN_SEGMENT_SIZE, h.segmentSize)
    }

    companion object {
        const val VECTOR = "vectors/v1_password_segment4096.vxc"
    }
}
