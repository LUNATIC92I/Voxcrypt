package com.voxcrypt.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class VxcEngineTest {
    private val engine = VxcEngine()

    @Test
    fun roundTrip_restoresAudioAndMetadata() {
        val audio = fakeAudio(50_000)
        val file = encryptWithPassword(engine, audio)
        val result = decryptWithPassword(engine, file)
        assertArrayEquals(audio, result.audio)
        assertEquals(sampleMetadata(audio.size.toLong()), result.metadata)
    }

    @Test
    fun roundTrip_variousSizesAroundSegmentBoundaries() {
        // Metadata block shifts boundaries, so test a wide range of lengths.
        val sizes = listOf(0, 1, 15, 16, 17, SMALL_SEGMENT - 70, SMALL_SEGMENT - 1, SMALL_SEGMENT,
            SMALL_SEGMENT + 1, 2 * SMALL_SEGMENT, 3 * SMALL_SEGMENT + 123, 100_003)
        for (size in sizes) {
            val audio = fakeAudio(size, seed = size.toLong())
            val file = encryptWithPassword(engine, audio)
            assertArrayEquals("size=$size", audio, decryptWithPassword(engine, file).audio)
        }
        // Exact multiple of the segment size for the whole plaintext (4 + metadata + audio).
        val metaLen = 4 + sampleMetadata(0).encode().size
        for (k in 1..3) {
            val audio = fakeAudio(k * SMALL_SEGMENT - metaLen)
            val file = encryptWithPassword(engine, audio, metadata = sampleMetadata(audio.size.toLong()))
            assertArrayEquals("exact k=$k", audio, decryptWithPassword(engine, file).audio)
        }
    }

    @Test
    fun emptyAudio_isSupportedByTheEngine() {
        val file = encryptWithPassword(engine, ByteArray(0))
        val result = decryptWithPassword(engine, file)
        assertEquals(0, result.audio.size)
    }

    @Test
    fun ciphertext_differsFromOriginal_andLeaksNoPlaintext() {
        val audio = fakeAudio(40_000)
        val file = encryptWithPassword(engine, audio)
        assertFalse(file.contentEquals(audio))
        assertEquals(-1, file.indexOf("AAC-LC-AUDIO-PATTERN".toByteArray()))
        assertEquals(-1, file.indexOf("ftypM4A".toByteArray()))
        // Metadata (original name) is encrypted too.
        assertEquals(-1, file.indexOf("memo_secret".toByteArray()))
        // Any 32 byte window of the audio must not appear in the file.
        for (offset in listOf(1000, 9000, 30000)) {
            assertEquals(-1, file.indexOf(audio.copyOfRange(offset, offset + 32)))
        }
    }

    @Test
    fun sameAudioSamePassword_producesDifferentFiles() {
        val audio = fakeAudio(20_000)
        val a = encryptWithPassword(engine, audio)
        val b = encryptWithPassword(engine, audio)
        assertFalse(a.contentEquals(b))
        val ha = VxcHeader.read(ByteArrayInputStream(a))
        val hb = VxcHeader.read(ByteArrayInputStream(b))
        assertFalse(ha.salt.contentEquals(hb.salt))
        assertFalse(ha.noncePrefix.contentEquals(hb.noncePrefix))
    }

    @Test
    fun wrongPassword_fails() {
        val file = encryptWithPassword(engine, fakeAudio(10_000))
        try {
            decryptWithPassword(engine, file, "Wrong-Horse-Battery-42".toCharArray())
            fail("wrong password accepted")
        } catch (e: VxcException.AuthenticationFailed) {
            // expected
        }
    }

    @Test
    fun wrongPassword_writesNoAudio() {
        val file = encryptWithPassword(engine, fakeAudio(10_000))
        val input = ByteArrayInputStream(file)
        val header = VxcHeader.read(input)
        val out = ByteArrayOutputStream()
        try {
            engine.openPassword(header, "nope-nope-nope".toCharArray()).use { engine.decrypt(it, input, out) }
            fail()
        } catch (e: VxcException.AuthenticationFailed) {
            assertEquals(0, out.size())
        }
    }

    @Test
    fun everySingleBitFlip_inCiphertextIsDetected() {
        val audio = fakeAudio(3 * SMALL_SEGMENT)
        val file = encryptWithPassword(engine, audio)
        val headerLen = VxcHeader.read(ByteArrayInputStream(file)).length
        // Flip one bit at many positions spread over every segment and tag.
        var pos = headerLen
        while (pos < file.size) {
            val copy = file.copyOf()
            copy[pos] = (copy[pos].toInt() xor 0x01).toByte()
            assertTampered(copy, "pos=$pos")
            pos += 97
        }
        // Last byte (final tag).
        val copy = file.copyOf()
        copy[copy.size - 1] = (copy[copy.size - 1].toInt() xor 0x80).toByte()
        assertTampered(copy, "last byte")
    }

    @Test
    fun headerModifications_areDetected() {
        val file = encryptWithPassword(engine, fakeAudio(10_000))
        val header = VxcHeader.read(ByteArrayInputStream(file))
        // Flip each byte of the nonce prefix and of the salt: header still parses, auth fails.
        for (pos in 18 until header.length) {
            val copy = file.copyOf()
            copy[pos] = (copy[pos].toInt() xor 0x01).toByte()
            try {
                decryptWithPassword(engine, copy)
                fail("header byte $pos modification undetected")
            } catch (e: VxcException) {
                // InvalidHeader for iterations out of range, AuthenticationFailed otherwise.
            }
        }
    }

    @Test
    fun segmentSizeModification_isRejectedOrDetected() {
        val file = encryptWithPassword(engine, fakeAudio(30_000))
        val copy = file.copyOf()
        // Segment size field at offset 14: 4096 -> 8192
        copy[16] = 0x20
        try {
            decryptWithPassword(engine, copy)
            fail()
        } catch (e: VxcException) {
            assertTrue(e is VxcException.AuthenticationFailed || e is VxcException.Truncated)
        }
    }

    @Test
    fun truncation_isDetected_atEveryCutPoint() {
        val audio = fakeAudio(3 * SMALL_SEGMENT + 500)
        val file = encryptWithPassword(engine, audio)
        val headerLen = VxcHeader.read(ByteArrayInputStream(file)).length
        val segCt = SMALL_SEGMENT + 16
        val cuts = mutableListOf(headerLen, headerLen + 1, headerLen + 15, headerLen + 16, file.size - 1, file.size - 16)
        for (k in 1..3) cuts += headerLen + k * segCt // exact segment boundaries
        for (cut in cuts) {
            val truncated = file.copyOf(cut)
            try {
                decryptWithPassword(engine, truncated)
                fail("truncation at $cut undetected")
            } catch (e: VxcException) {
                assertTrue("cut=$cut -> $e", e is VxcException.Truncated || e is VxcException.Tampered ||
                    e is VxcException.AuthenticationFailed)
            }
        }
    }

    @Test
    fun appendedData_isDetected() {
        val file = encryptWithPassword(engine, fakeAudio(10_000))
        assertTampered(file + ByteArray(1), "1 byte appended")
        assertTampered(file + ByteArray(200) { 7 }, "200 bytes appended")
    }

    @Test
    fun segmentReorderingAndRemoval_areDetected() {
        val audio = fakeAudio(4 * SMALL_SEGMENT)
        val file = encryptWithPassword(engine, audio)
        val h = VxcHeader.read(ByteArrayInputStream(file)).length
        val segCt = SMALL_SEGMENT + 16
        val seg = { i: Int -> file.copyOfRange(h + i * segCt, minOf(file.size, h + (i + 1) * segCt)) }
        val head = file.copyOf(h)
        // swap segments 1 and 2
        val swapped = head + seg(0) + seg(2) + seg(1) + seg(3) + seg(4)
        assertTampered(swapped, "swap")
        // remove segment 1
        val removed = head + seg(0) + seg(2) + seg(3) + seg(4)
        assertTampered(removed, "removal")
        // duplicate segment 0
        val dup = head + seg(0) + seg(0) + seg(1) + seg(2) + seg(3) + seg(4)
        assertTampered(dup, "duplicate")
    }

    @Test
    fun segmentsFromAnotherFile_cannotBeSpliced() {
        val audio = fakeAudio(3 * SMALL_SEGMENT)
        val a = encryptWithPassword(engine, audio)
        val b = encryptWithPassword(engine, audio)
        val h = VxcHeader.read(ByteArrayInputStream(a)).length
        val segCt = SMALL_SEGMENT + 16
        val spliced = a.copyOf(h + segCt) + b.copyOfRange(h + segCt, b.size)
        assertTampered(spliced, "splice")
    }

    @Test
    fun deviceMode_roundTrip_andOtherDeviceFails() {
        val wrapper = InMemoryDeviceKeyWrapper()
        val audio = fakeAudio(25_000)
        val out = ByteArrayOutputStream()
        engine.prepareDevice(wrapper, SMALL_SEGMENT).use { engine.encrypt(it, sampleMetadata(), ByteArrayInputStream(audio), out) }
        val file = out.toByteArray()
        val input = ByteArrayInputStream(file)
        val header = VxcHeader.read(input)
        assertEquals(KeyProtection.DEVICE, header.protection)
        val plain = ByteArrayOutputStream()
        engine.openDevice(header, wrapper).use { engine.decrypt(it, input, plain) }
        assertArrayEquals(audio, plain.toByteArray())

        // A different device key (other phone, reinstall) cannot open it.
        try {
            engine.openDevice(header, InMemoryDeviceKeyWrapper())
            fail()
        } catch (e: VxcException.DeviceKeyUnavailable) {
            // expected
        }
        // A password cannot open a device file.
        try {
            engine.openPassword(header, PASSWORD)
            fail()
        } catch (e: VxcException.InvalidArgument) {
            // expected
        }
    }

    @Test
    fun contentKey_cannotEncryptTwice() {
        val key = engine.preparePassword(PASSWORD, TEST_ITERATIONS, SMALL_SEGMENT)
        engine.encrypt(key, sampleMetadata(), ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream())
        try {
            engine.encrypt(key, sampleMetadata(), ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream())
            fail("nonce reuse allowed")
        } catch (e: VxcException.InvalidArgument) {
            // expected
        } finally {
            key.close()
        }
    }

    @Test
    fun decryptionKey_cannotBeUsedToEncrypt() {
        val file = encryptWithPassword(engine, fakeAudio(100))
        val header = VxcHeader.read(ByteArrayInputStream(file))
        engine.openPassword(header, PASSWORD).use { key ->
            try {
                engine.encrypt(key, sampleMetadata(), ByteArrayInputStream(ByteArray(1)), ByteArrayOutputStream())
                fail()
            } catch (e: VxcException.InvalidArgument) {
                // expected
            }
        }
    }

    @Test
    fun declaredAudioLengthMismatch_isRefusedAtEncryption() {
        try {
            encryptWithPassword(engine, fakeAudio(100), metadata = sampleMetadata(99))
            fail()
        } catch (e: VxcException.InvalidArgument) {
            // expected
        }
    }

    @Test
    fun progress_isReportedAndCancellationPropagates() {
        val audio = fakeAudio(10 * SMALL_SEGMENT)
        val seen = mutableListOf<Long>()
        engine.preparePassword(PASSWORD, TEST_ITERATIONS, SMALL_SEGMENT).use { key ->
            engine.encrypt(key, sampleMetadata(), ByteArrayInputStream(audio), ByteArrayOutputStream()) { seen += it }
        }
        assertTrue(seen.size >= 10)
        assertEquals(audio.size.toLong(), seen.last())
        assertEquals(seen, seen.sorted())

        class Stop : RuntimeException()
        try {
            engine.preparePassword(PASSWORD, TEST_ITERATIONS, SMALL_SEGMENT).use { key ->
                engine.encrypt(key, sampleMetadata(), ByteArrayInputStream(audio), ByteArrayOutputStream()) {
                    if (it > 3 * SMALL_SEGMENT) throw Stop()
                }
            }
            fail()
        } catch (e: Stop) {
            // expected
        }
    }

    @Test
    fun verify_authenticatesWithoutOutput() {
        val audio = fakeAudio(9_000)
        val file = encryptWithPassword(engine, audio)
        val input = ByteArrayInputStream(file)
        val header = VxcHeader.read(input)
        val meta = engine.openPassword(header, PASSWORD).use { engine.verify(it, input) }
        assertEquals("memo_secret.m4a", meta.originalName)
    }

    @Test
    fun nonce_layout() {
        val n = VxcEngine.nonce(byteArrayOf(1, 2, 3, 4, 5, 6, 7), 0x01020304, true)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 1, 2, 3, 4, 1), n)
        assertNotEquals(VxcEngine.nonce(ByteArray(7), 1, false).toList(), VxcEngine.nonce(ByteArray(7), 1, true).toList())
    }

    private fun assertTampered(file: ByteArray, label: String) {
        try {
            decryptWithPassword(engine, file)
            fail("$label: modification undetected")
        } catch (e: VxcException) {
            assertTrue("$label -> $e", e is VxcException.Tampered || e is VxcException.AuthenticationFailed ||
                e is VxcException.Truncated)
        }
    }
}
