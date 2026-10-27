package com.voxcrypt.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * The unit test JVM runs with -Xmx160m (see app/build.gradle.kts). Encrypting and
 * decrypting 256 MiB proves that memory does not grow with the file size.
 */
class LargeFileStreamingTest {

    /** Deterministic pseudo audio stream of [size] bytes, generated on the fly. */
    private class SyntheticStream(private val size: Long) : InputStream() {
        private var pos = 0L
        override fun read(): Int = if (pos >= size) -1 else ((pos++ * 2654435761L) ushr 13).toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= size) return -1
            val n = minOf(len.toLong(), size - pos).toInt()
            for (i in 0 until n) b[off + i] = ((((pos + i) * 2654435761L) ushr 13).toInt() and 0xFF).toByte()
            pos += n
            return n
        }
    }

    @Test
    fun encryptAndDecrypt256MiB_withBoundedMemory() {
        val size = 256L * 1024 * 1024
        val engine = VxcEngine()
        val vxc = File.createTempFile("large", ".vxc")
        try {
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            val runtime = Runtime.getRuntime()
            var peak = 0L
            val start = System.nanoTime()
            vxc.outputStream().buffered().use { out ->
                engine.preparePassword(PASSWORD, TEST_ITERATIONS).use { key ->
                    engine.encrypt(key, sampleMetadata(size), DigestInputStream(SyntheticStream(size), sourceDigest), out) {
                        peak = maxOf(peak, runtime.totalMemory() - runtime.freeMemory())
                    }
                }
            }
            val encMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(vxc.length() > size)

            val plainDigest = MessageDigest.getInstance("SHA-256")
            val start2 = System.nanoTime()
            vxc.inputStream().buffered().use { input ->
                val header = VxcHeader.read(input)
                engine.openPassword(header, PASSWORD).use { key ->
                    engine.decrypt(key, input, DigestOutputStream(OutputStream.nullOutputStream(), plainDigest)) {
                        peak = maxOf(peak, runtime.totalMemory() - runtime.freeMemory())
                    }
                }
            }
            val decMs = (System.nanoTime() - start2) / 1_000_000
            assertArrayEquals(sourceDigest.digest(), plainDigest.digest())
            println("256 MiB: encrypt ${encMs} ms, decrypt ${decMs} ms, peak heap used ${peak / (1024 * 1024)} MiB, max heap ${runtime.maxMemory() / (1024 * 1024)} MiB")
            assertTrue("heap peak too high: $peak", peak < 120L * 1024 * 1024)
        } finally {
            vxc.delete()
        }
    }
}
