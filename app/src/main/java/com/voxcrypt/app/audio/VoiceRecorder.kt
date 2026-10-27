package com.voxcrypt.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.voxcrypt.app.data.PendingRecording
import com.voxcrypt.app.data.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.log10

/**
 * Voice recorder: AAC in an MPEG-4 (.m4a) container, mono, 44.1 kHz, 128 kbit/s.
 *
 * Lives at application scope so a rotation or a screen change does not stop it.
 * Audio is written to a hidden ".part" file in private storage and published
 * as a pending recording only when the recording is stopped correctly; an
 * interrupted recording (process killed) is removed at next start.
 */
class VoiceRecorder(private val context: Context, private val repository: VaultRepository) {

    sealed interface State {
        data object Idle : State
        data class Recording(val elapsedMillis: Long) : State
        data class Paused(val elapsedMillis: Long, val reason: String? = null) : State
        data class Finished(val recording: PendingRecording) : State
        data class Error(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Recent input levels in [0, 1], newest last, for the visualizer. */
    private val _levels = MutableStateFlow(List(LEVEL_HISTORY) { 0f })
    val levels: StateFlow<List<Float>> = _levels.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var partFile: File? = null
    private var accumulatedMillis = 0L
    private var segmentStart = 0L
    private var ticker: Job? = null

    val isActive: Boolean get() = recorder != null

    fun start() {
        if (recorder != null) return
        val file = repository.newRecordingPartFile()
        val r = newRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(file.absolutePath)
            r.setOnErrorListener { _, _, _ -> fail("Le microphone a cessé de répondre.") }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            _state.value = State.Error("Microphone indisponible. Il est peut-être utilisé par une autre application.")
            return
        }
        recorder = r
        partFile = file
        accumulatedMillis = 0
        segmentStart = SystemClock.elapsedRealtime()
        _levels.value = List(LEVEL_HISTORY) { 0f }
        _state.value = State.Recording(0)
        startTicker()
    }

    fun pause(reason: String? = null) {
        val r = recorder ?: return
        if (_state.value !is State.Recording) return
        try {
            r.pause()
        } catch (e: IllegalStateException) {
            return
        }
        accumulatedMillis += SystemClock.elapsedRealtime() - segmentStart
        ticker?.cancel()
        _state.value = State.Paused(accumulatedMillis, reason)
    }

    fun resume() {
        val r = recorder ?: return
        if (_state.value !is State.Paused) return
        try {
            r.resume()
        } catch (e: IllegalStateException) {
            fail("Impossible de reprendre l'enregistrement.")
            return
        }
        segmentStart = SystemClock.elapsedRealtime()
        _state.value = State.Recording(accumulatedMillis)
        startTicker()
    }

    fun stop() {
        val r = recorder ?: return
        val file = partFile
        if (_state.value is State.Recording) accumulatedMillis += SystemClock.elapsedRealtime() - segmentStart
        ticker?.cancel()
        recorder = null
        partFile = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false // stop() right after start: no valid data
        } finally {
            r.release()
        }
        if (!ok || file == null || file.length() == 0L) {
            file?.let { VaultRepository.secureDelete(it) }
            _state.value = State.Error("Enregistrement trop court ou invalide. Rien n'a été conservé.")
            return
        }
        _state.value = try {
            State.Finished(repository.commitRecording(file, accumulatedMillis))
        } catch (e: Exception) {
            VaultRepository.secureDelete(file)
            State.Error("Impossible d'enregistrer le fichier.")
        }
    }

    /** Stops and destroys the current recording without keeping anything. */
    fun discard() {
        val r = recorder
        val file = partFile
        ticker?.cancel()
        recorder = null
        partFile = null
        if (r != null) {
            try { r.stop() } catch (e: RuntimeException) { /* nothing recorded */ }
            r.release()
        }
        file?.let { VaultRepository.secureDelete(it) }
        _state.value = State.Idle
    }

    /** Back to idle once the UI has consumed a Finished or Error state. */
    fun acknowledge() {
        if (recorder == null) _state.value = State.Idle
    }

    private fun fail(message: String) {
        val r = recorder
        val file = partFile
        ticker?.cancel()
        recorder = null
        partFile = null
        r?.release()
        file?.let { VaultRepository.secureDelete(it) }
        _state.value = State.Error(message)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val r = recorder ?: break
                val amplitude = try { r.maxAmplitude } catch (e: IllegalStateException) { 0 }
                val level = if (amplitude <= 0) 0f else ((20 * log10(amplitude / 32767.0) + 50) / 50).toFloat().coerceIn(0f, 1f)
                _levels.value = (_levels.value + level).takeLast(LEVEL_HISTORY)
                _state.value = State.Recording(accumulatedMillis + SystemClock.elapsedRealtime() - segmentStart)
                delay(TICK_MS)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    companion object {
        const val LEVEL_HISTORY = 48
        private const val TICK_MS = 60L
    }
}
