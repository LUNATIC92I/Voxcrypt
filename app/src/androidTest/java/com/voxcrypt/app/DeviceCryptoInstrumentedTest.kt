package com.voxcrypt.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voxcrypt.app.crypto.KeyDerivation
import com.voxcrypt.app.crypto.VxcEngine
import com.voxcrypt.app.crypto.VxcException
import com.voxcrypt.app.crypto.VxcFormat
import com.voxcrypt.app.crypto.VxcHeader
import com.voxcrypt.app.crypto.VxcMetadata
import com.voxcrypt.app.security.KeystoreDeviceKeyWrapper
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore

/** Runs on a device or emulator: real Android Keystore and real PBKDF2 timings. */
@RunWith(AndroidJUnit4::class)
class DeviceCryptoInstrumentedTest {
    private val alias = "voxcrypt.test.key"
    private val otherAlias = "voxcrypt.test.other"

    @After
    fun cleanup() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        listOf(alias, otherAlias).forEach { if (ks.containsAlias(it)) ks.deleteEntry(it) }
    }

    @Test
    fun keystoreWrapper_roundTrip_andIsolation() {
        val wrapper = KeystoreDeviceKeyWrapper(alias)
        val dataKey = ByteArray(32) { it.toByte() }
        val aad = "aad".toByteArray()
        val wrapped = wrapper.wrap(dataKey, aad)
        assertEquals(12 + 32 + 16, wrapped.size)
        assertArrayEquals(dataKey, wrapper.unwrap(wrapped, aad))
        try {
            wrapper.unwrap(wrapped, "other".toByteArray())
            fail("AAD not authenticated")
        } catch (e: VxcException.DeviceKeyUnavailable) {
        }
        try {
            KeystoreDeviceKeyWrapper(otherAlias).unwrap(wrapped, aad)
            fail("another key must not unwrap")
        } catch (e: VxcException.DeviceKeyUnavailable) {
        }
    }

    @Test
    fun engine_deviceMode_withRealKeystore() {
        val engine = VxcEngine()
        val wrapper = KeystoreDeviceKeyWrapper(alias)
        val audio = ByteArray(300_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        engine.prepareDevice(wrapper).use {
            engine.encrypt(it, VxcMetadata("a.m4a", "audio/mp4", 0, -1, audio.size.toLong()), ByteArrayInputStream(audio), out)
        }
        val input = ByteArrayInputStream(out.toByteArray())
        val header = VxcHeader.read(input)
        val plain = ByteArrayOutputStream()
        engine.openDevice(header, wrapper).use { engine.decrypt(it, input, plain) }
        assertArrayEquals(audio, plain.toByteArray())
    }

    @Test
    fun engine_passwordMode_defaultIterations_onDevice() {
        val engine = VxcEngine()
        val audio = ByteArray(1_000_000) { (it * 7).toByte() }
        val pwd = "Turquoise#Signal-77".toCharArray()
        val out = ByteArrayOutputStream()
        val t0 = System.nanoTime()
        engine.preparePassword(pwd).use {
            engine.encrypt(it, VxcMetadata("a.m4a", "audio/mp4", 0), ByteArrayInputStream(audio), out)
        }
        val t1 = System.nanoTime()
        val input = ByteArrayInputStream(out.toByteArray())
        val header = VxcHeader.read(input)
        assertEquals(VxcFormat.DEFAULT_PBKDF2_ITERATIONS, header.iterations)
        val plain = ByteArrayOutputStream()
        engine.openPassword(header, pwd).use { engine.decrypt(it, input, plain) }
        assertArrayEquals(audio, plain.toByteArray())
        Log.i("VoxcryptTest", "1 MB password round trip: encrypt ${(t1 - t0) / 1_000_000} ms")
    }

    /** Calibration helper: prints PBKDF2 timings for the iteration choices. */
    @Test
    fun pbkdf2_benchmark() {
        for (it in listOf(310_000, 600_000, 1_000_000)) {
            Log.i("VoxcryptTest", "PBKDF2-HMAC-SHA256 $it iterations: ${KeyDerivation.benchmark(it)} ms")
        }
    }
}
