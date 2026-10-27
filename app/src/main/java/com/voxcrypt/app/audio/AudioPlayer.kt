package com.voxcrypt.app.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Thin MediaPlayer wrapper for private files (pending recordings, decrypted audio). */
class AudioPlayer(private val scope: CoroutineScope) {

    data class State(
        val loaded: Boolean = false,
        val playing: Boolean = false,
        val positionMillis: Int = 0,
        val durationMillis: Int = 0,
        val error: String? = null,
    )

    private var player: MediaPlayer? = null
    private var poller: Job? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(file: File) {
        release()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            file.inputStream().use { mp.setDataSource(it.fd) }
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            _state.value = State(error = "Lecture impossible : format audio non pris en charge ou fichier corrompu.")
            return
        }
        mp.setOnCompletionListener {
            poller?.cancel()
            _state.value = _state.value.copy(playing = false, positionMillis = 0)
        }
        player = mp
        _state.value = State(loaded = true, durationMillis = mp.duration.coerceAtLeast(0))
    }

    fun toggle() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            poller?.cancel()
            _state.value = _state.value.copy(playing = false)
        } else {
            mp.start()
            _state.value = _state.value.copy(playing = true)
            poller?.cancel()
            poller = scope.launch {
                while (isActive) {
                    player?.let { _state.value = _state.value.copy(positionMillis = it.currentPosition) }
                    delay(100)
                }
            }
        }
    }

    fun seekTo(fraction: Float) {
        val mp = player ?: return
        val pos = (fraction.coerceIn(0f, 1f) * mp.duration).toInt()
        mp.seekTo(pos)
        _state.value = _state.value.copy(positionMillis = pos)
    }

    fun pause() {
        val mp = player ?: return
        if (mp.isPlaying) toggle()
    }

    fun release() {
        poller?.cancel()
        player?.release()
        player = null
        _state.value = State()
    }
}
