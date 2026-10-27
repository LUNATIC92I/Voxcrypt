package com.voxcrypt.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VxcMetadataAndPolicyTest {
    @Test
    fun metadata_roundTrip_unicode() {
        val m = VxcMetadata("Réunion d'équipe n°3 日本語.m4a", "audio/mp4", 42L, 61_000L, 1234L)
        assertEquals(m, VxcMetadata.decode(m.encode()))
    }

    @Test
    fun metadata_rejectsGarbage() {
        val bytes = VxcMetadata("a", "b", 1, 2, 3).encode()
        try { VxcMetadata.decode(bytes.copyOf(bytes.size - 1)); fail() } catch (e: VxcException.InvalidMetadata) {}
        try { VxcMetadata.decode(bytes + 0); fail() } catch (e: VxcException.InvalidMetadata) {}
        val badVersion = bytes.copyOf().also { it[0] = 9 }
        try { VxcMetadata.decode(badVersion); fail() } catch (e: VxcException.InvalidMetadata) {}
    }

    @Test
    fun metadata_refusesOversizedNames() {
        try {
            VxcMetadata("x".repeat(5000), "audio/mp4", 0).encode()
            fail()
        } catch (e: VxcException.InvalidArgument) {}
    }

    @Test
    fun passwordPolicy() {
        assertEquals(PasswordPolicy.Strength.TOO_SHORT, PasswordPolicy.evaluate("abc".toCharArray()))
        assertEquals(PasswordPolicy.Strength.WEAK, PasswordPolicy.evaluate("aaaaaaaaaaaa".toCharArray()))
        assertFalse(PasswordPolicy.isAcceptable("abcdefghij".toCharArray()))
        assertTrue(PasswordPolicy.isAcceptable("Soleil-Bleu-2026".toCharArray()))
        assertEquals(PasswordPolicy.Strength.STRONG, PasswordPolicy.evaluate("Turquoise#Signal-77".toCharArray()))
        assertTrue(PasswordPolicy.isAcceptable("une phrase de passe assez longue".toCharArray()))
    }

    @Test
    fun pbkdf2_isDeterministicAndSaltDependent() {
        val salt1 = ByteArray(32) { 1 }
        val salt2 = ByteArray(32) { 2 }
        val a = KeyDerivation.pbkdf2(PASSWORD, salt1, TEST_ITERATIONS)
        val b = KeyDerivation.pbkdf2(PASSWORD, salt1, TEST_ITERATIONS)
        val c = KeyDerivation.pbkdf2(PASSWORD, salt2, TEST_ITERATIONS)
        assertEquals(32, a.size)
        assertTrue(a.contentEquals(b))
        assertFalse(a.contentEquals(c))
    }

    @Test
    fun pbkdf2_matchesIndependentKnownAnswer() {
        // Reference computed with Python: hashlib.pbkdf2_hmac("sha256", b"password",
        // b"salt" + 12 zero bytes, 100000, 32). VOXCRYPT requires salts of 16 bytes or more.
        val dk = KeyDerivation.pbkdf2("password".toCharArray(), "salt".toByteArray().copyOf(16), 100_000)
        assertEquals(EXPECTED_PBKDF2_HEX, dk.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun pbkdf2_refusesEmptyPasswordAndBadIterations() {
        try { KeyDerivation.pbkdf2(CharArray(0), ByteArray(32), TEST_ITERATIONS); fail() } catch (e: VxcException.InvalidArgument) {}
        try { KeyDerivation.pbkdf2(PASSWORD, ByteArray(32), 1); fail() } catch (e: VxcException.InvalidHeader) {}
    }

    companion object {
        const val EXPECTED_PBKDF2_HEX = "30b21944d0a92aff52c714d73cc8ed3e917f2fc02d6c548dbe59b09dc082449b"
    }
}
