package com.voxcrypt.app

import android.Manifest
import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.voxcrypt.app.audio.AudioInfo
import com.voxcrypt.app.audio.VoiceRecorder
import com.voxcrypt.app.data.EncryptSource
import com.voxcrypt.app.data.ProtectionChoice
import com.voxcrypt.app.data.UnlockChoice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Microphone recording, encryption, decryption and playback on a real device
 * (an emulator with a virtual microphone works too).
 */
@RunWith(AndroidJUnit4::class)
class RecordingInstrumentedTest {
    @get:Rule
    val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val app = ApplicationProvider.getApplicationContext<VoxcryptApp>()
    private val c = app.container
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    fun record_pause_resume_stop_encrypt_decrypt_play() {
        main { c.recorder.start() }
        Thread.sleep(1500)
        main { c.recorder.pause() }
        main { assertTrue(c.recorder.state.value is VoiceRecorder.State.Paused) }
        Thread.sleep(300)
        main { c.recorder.resume() }
        Thread.sleep(1500)
        main { c.recorder.stop() }

        val state = c.recorder.state.value
        assertTrue("state=$state", state is VoiceRecorder.State.Finished)
        val rec = (state as VoiceRecorder.State.Finished).recording
        assertTrue(rec.file.length() > 0)
        assertTrue("duration ${AudioInfo.durationOf(rec.file)}", AudioInfo.durationOf(rec.file) > 2000)

        val pwd = "Turquoise#Signal-77"
        val entry = c.vault.encrypt(
            EncryptSource(rec.file.name, "audio/mp4", rec.file.length(), rec.durationMillis, rec.file) { rec.file.inputStream() },
            ProtectionChoice.Password(pwd.toCharArray(), 100_000),
            "instrumented",
        )
        assertFalse("clear recording must be deleted", rec.file.exists())

        val audio = c.vault.decrypt({ entry.file.inputStream() }, entry.sizeBytes, UnlockChoice.Password(pwd.toCharArray()))
        val player = MediaPlayer()
        try {
            audio.file.inputStream().use { player.setDataSource(it.fd) }
            player.prepare()
            assertTrue(player.duration > 2000)
        } finally {
            player.release()
            c.vault.discardDecrypted(audio)
            c.vault.delete(entry)
        }
    }

    @Test
    fun stopImmediately_keepsNothing() {
        main { c.recorder.start() }
        main { c.recorder.stop() }
        val s = c.recorder.state.value
        assertTrue("state=$s", s is VoiceRecorder.State.Error || s is VoiceRecorder.State.Finished)
        main { c.recorder.acknowledge() }
        assertTrue(c.vault.pendingDir.listFiles()!!.none { it.name.startsWith(".part-") })
    }
}
