package com.voxcrypt.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class VxcHeaderTest {
    private val salt = ByteArray(32) { it.toByte() }
    private val prefix = ByteArray(7) { (100 + it).toByte() }

    @Test
    fun encodeParse_roundTrip() {
        val h = VxcHeader.forPassword(600_000, salt, prefix)
        val p = VxcHeader.parse(h.encoded)
        assertEquals(KeyProtection.PASSWORD, p.protection)
        assertEquals(600_000, p.iterations)
        assertEquals(VxcFormat.DEFAULT_SEGMENT_SIZE, p.segmentSize)
        assertArrayEquals(salt, p.salt)
        assertArrayEquals(prefix, p.noncePrefix)
        assertArrayEquals(h.encoded, p.encoded)
        assertEquals(25 + 4 + 1 + 32, h.length)
    }

    @Test
    fun knownLayout() {
        val e = VxcHeader.forPassword(100_000, salt, prefix, 4096).encoded
        assertArrayEquals("VOXCRYPT".toByteArray(), e.copyOfRange(0, 8))
        assertEquals(1, e[8].toInt())
        assertEquals(e.size, ByteBuffer.wrap(e, 9, 2).short.toInt())
        assertEquals(1, e[11].toInt()) // AES-256-GCM segmented
        assertEquals(1, e[12].toInt()) // password
        assertEquals(0, e[13].toInt()) // flags
        assertEquals(4096, ByteBuffer.wrap(e, 14, 4).int)
        assertArrayEquals(prefix, e.copyOfRange(18, 25))
        assertEquals(100_000, ByteBuffer.wrap(e, 25, 4).int)
        assertEquals(32, e[29].toInt())
    }

    @Test
    fun read_consumesExactlyTheHeader() {
        val h = VxcHeader.forPassword(600_000, salt, prefix)
        val input = ByteArrayInputStream(h.encoded + byteArrayOf(42, 43))
        VxcHeader.read(input)
        assertEquals(42, input.read())
    }

    @Test
    fun rejectsNonVxcData() {
        expect<VxcException.NotVxcFile> { VxcHeader.read(ByteArrayInputStream("ftypM4A audio".toByteArray())) }
        expect<VxcException.NotVxcFile> { VxcHeader.read(ByteArrayInputStream(ByteArray(0))) }
        expect<VxcException.NotVxcFile> { VxcHeader.read(ByteArrayInputStream("VOXCRYP".toByteArray())) }
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val e = VxcHeader.forPassword(600_000, salt, prefix).encoded
        e[8] = 2
        val ex = expect<VxcException.UnsupportedVersion> { VxcHeader.read(ByteArrayInputStream(e)) }
        assertEquals(2, ex.version)
    }

    @Test
    fun rejectsTruncatedHeader() {
        val e = VxcHeader.forPassword(600_000, salt, prefix).encoded
        expect<VxcException.Truncated> { VxcHeader.read(ByteArrayInputStream(e.copyOf(10))) }
        expect<VxcException.Truncated> { VxcHeader.read(ByteArrayInputStream(e.copyOf(e.size - 1))) }
    }

    @Test
    fun rejectsInvalidParameters_beforeAnyDerivation() {
        val base = VxcHeader.forPassword(600_000, salt, prefix).encoded
        fun mutate(block: (ByteBuffer) -> Unit): ByteArray = base.copyOf().also { block(ByteBuffer.wrap(it)) }

        // iterations too low / too high
        expect<VxcException.InvalidHeader> { VxcHeader.parse(mutate { it.putInt(25, 1000) }) }
        expect<VxcException.InvalidHeader> { VxcHeader.parse(mutate { it.putInt(25, Int.MAX_VALUE) }) }
        expect<VxcException.InvalidHeader> { VxcHeader.parse(mutate { it.putInt(25, -1) }) }
        // segment size
        expect<VxcException.InvalidHeader> { VxcHeader.parse(mutate { it.putInt(14, 0) }) }
        expect<VxcException.InvalidHeader> { VxcHeader.parse(mutate { it.putInt(14, 512 * 1024 * 1024) }) }
        // unknown cipher, protection, flags
        expect<VxcException.InvalidHeader> { VxcHeader.parse(mutate { it.put(11, 9) }) }
        expect<VxcException.InvalidHeader> { VxcHeader.parse(mutate { it.put(12, 7) }) }
        expect<VxcException.InvalidHeader> { VxcHeader.parse(mutate { it.put(13, 1) }) }
        // inconsistent declared length
        expect<VxcException.InvalidHeader> { VxcHeader.parse(mutate { it.putShort(9, 100) }) }
        // salt length mismatching the header length
        expect<VxcException.InvalidHeader> { VxcHeader.parse(mutate { it.put(29, 31) }) }
        expect<VxcException.InvalidHeader> { VxcHeader.parse(mutate { it.put(29, 4) }) }
        // absurd header length in stream form
        expect<VxcException.InvalidHeader> {
            VxcHeader.read(ByteArrayInputStream(mutate { it.putShort(9, 0xFFFF.toShort()) }))
        }
    }

    @Test
    fun builderRefusesInvalidParameters() {
        expect<VxcException.InvalidHeader> { VxcHeader.forPassword(10, salt, prefix) }
        expect<VxcException.InvalidHeader> { VxcHeader.forPassword(600_000, ByteArray(8), prefix) }
        expect<VxcException.InvalidHeader> { VxcHeader.forPassword(600_000, salt, ByteArray(12)) }
        expect<VxcException.InvalidHeader> { VxcHeader.forDevice(ByteArray(4), prefix) }
    }

    @Test
    fun deviceHeader_roundTrip() {
        val wrapped = ByteArray(60) { 9 }
        val h = VxcHeader.forDevice(wrapped, prefix)
        val p = VxcHeader.parse(h.encoded)
        assertEquals(KeyProtection.DEVICE, p.protection)
        assertArrayEquals(wrapped, p.wrappedKey)
        assertEquals(0, p.iterations)
    }

    @Test
    fun exposedArraysAreDefensiveCopies() {
        val h = VxcHeader.forPassword(600_000, salt, prefix)
        h.salt[0] = 99
        h.encoded[0] = 0
        assertEquals(0, h.salt[0].toInt())
        assertEquals('V'.code, h.encoded[0].toInt())
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            fail("expected ${T::class.simpleName} but got $t")
        }
        fail("expected ${T::class.simpleName}")
        throw AssertionError()
    }
}
